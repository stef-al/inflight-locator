#!/usr/bin/env python3
"""
Offline in-flight location estimator: Monte Carlo prototype.

Simulates a phone deep in an aircraft cabin (no satellite signal) and runs the
particle filter proposed in the app spec with different sensor mixes.

Default scenario: Wizz Air W6 4747, Skopje (SKP/LWSK) to Prague (PRG/LKPR).

Simulated "truth"
  - Flown path: departure runway leg, the published airway route with random
    direct-to shortcuts and smooth lateral deviations, then a base leg and final
    approach to a randomly chosen arrival runway.
  - Ground speed: climb ramp, cruise at true airspeed plus an along-track wind
    that varies along the route, descent ramp from a top-of-descent point.
  - Phone observations: flight-phase cue times (top of climb, top of descent),
    compass heading each minute, magnetic field strength F and dip angle I each
    minute (World Magnetic Model 2025 plus bias and noise), cosmic-ray hit counts
    every 10 minutes (Poisson), and optionally one GPS fix mid-flight.

Estimators compared (see all_configs)
  baseline_clock_gc         clock x cruise speed along the great circle, no filter
  A1/A2  gc_*               particle filter, great circle as reference track
  A3/A4  track_*            particle filter, preloaded (published) track
  A5/A6  ..._mag[_cosmic]   plus magnetometer, plus particle counter
  A7/A8  ..._windfc[_mag]   plus a winds-aloft forecast
  A9     ..._fix            plus one GPS fix (minute 40 on W6 4747)
  B1-B3  unknown_*          no route: dead reckoning from compass + clock
  B4     unknown_compass_fix  the same with one GPS fix

Magnetometer biases are handled by a small Kalman filter inside every particle
(Rao-Blackwellised), so they are not sampled.

All design values (noise levels, performance numbers) live in the Perf
dataclass. The simulated truth is drawn from the same family of models the
filter assumes, so results are optimistic about model mismatch.

Requires: Python 3.9+, numpy, pygeomag (pip install numpy pygeomag).
Optional data for the landing-snap test: airports.csv and runways.csv from
https://github.com/davidmegginson/ourairports-data

Runs used in the spec:
  python3 inflight_sim.py --runs 400 --jobs 2 --seed 4747 \
      --airports airports.csv --runways runways.csv --out out
  python3 inflight_sim.py --scenario long_ns --runs 80 --jobs 2 --seed 11 --particles 1200 --out out \
      --only baseline_clock_gc,A4_track_cues,A5_track_cues_mag,A6_track_cues_mag_cosmic,A7_track_cues_windfc,A9_track_cues_fix,B1_unknown_compass,B2_unknown_compass_mag,B3_unknown_compass_mag_cosmic
  python3 inflight_sim.py --scenario long_ew --runs 80 --jobs 2 --seed 12 --particles 1200 --out out \
      --only baseline_clock_gc,A4_track_cues,A5_track_cues_mag,A7_track_cues_windfc,B1_unknown_compass,B2_unknown_compass_mag

Each run writes out/summary_<scenario>.json with error statistics per minute.
"""
import argparse
import csv
import json
import math
import os
import time
from dataclasses import dataclass, field, asdict

import numpy as np

R_EARTH = 6371.0088


# --------------------------------------------------------------------------
# Geometry on a sphere
# --------------------------------------------------------------------------
def hav_km(lat1, lon1, lat2, lon2):
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dphi = p2 - p1
    dl = np.radians(np.asarray(lon2) - np.asarray(lon1))
    a = np.sin(dphi / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * R_EARTH * np.arcsin(np.sqrt(np.clip(a, 0.0, 1.0)))


def bearing_deg(lat1, lon1, lat2, lon2):
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dl = np.radians(np.asarray(lon2) - np.asarray(lon1))
    y = np.sin(dl) * np.cos(p2)
    x = np.cos(p1) * np.sin(p2) - np.sin(p1) * np.cos(p2) * np.cos(dl)
    return (np.degrees(np.arctan2(y, x)) + 360.0) % 360.0


def dest_point(lat, lon, brg, dist_km):
    p1 = np.radians(lat)
    l1 = np.radians(lon)
    b = np.radians(brg)
    d = np.asarray(dist_km) / R_EARTH
    sp2 = np.sin(p1) * np.cos(d) + np.cos(p1) * np.sin(d) * np.cos(b)
    p2 = np.arcsin(np.clip(sp2, -1.0, 1.0))
    l2 = l1 + np.arctan2(np.sin(b) * np.sin(d) * np.cos(p1), np.cos(d) - np.sin(p1) * sp2)
    return np.degrees(p2), (np.degrees(l2) + 540.0) % 360.0 - 180.0


def _unit(lat, lon):
    p, l = np.radians(lat), np.radians(lon)
    return np.array([np.cos(p) * np.cos(l), np.cos(p) * np.sin(l), np.sin(p)])


def gc_interp(lat1, lon1, lat2, lon2, f):
    a, b = _unit(lat1, lon1), _unit(lat2, lon2)
    om = math.acos(max(-1.0, min(1.0, float(np.dot(a, b)))))
    if om < 1e-12:
        return lat1, lon1
    v = (math.sin((1 - f) * om) * a + math.sin(f * om) * b) / math.sin(om)
    return math.degrees(math.asin(max(-1.0, min(1.0, v[2])))), math.degrees(math.atan2(v[1], v[0]))


def ang_diff(a, b):
    """Smallest signed difference a-b in degrees."""
    return (np.asarray(a) - np.asarray(b) + 180.0) % 360.0 - 180.0


class Track:
    """A polyline of great-circle legs, densified so that linear interpolation is accurate."""

    def __init__(self, pts, step_km=4.0):
        lat = [pts[0][0]]
        lon = [pts[0][1]]
        for (la1, lo1), (la2, lo2) in zip(pts[:-1], pts[1:]):
            d = float(hav_km(la1, lo1, la2, lo2))
            n = max(1, int(math.ceil(d / step_km)))
            for i in range(1, n + 1):
                la, lo = gc_interp(la1, lo1, la2, lo2, i / n)
                lat.append(la)
                lon.append(lo)
        self.lat = np.array(lat)
        self.lon = np.unwrap(np.radians(np.array(lon)))
        self.lon = np.degrees(self.lon)
        seg = hav_km(self.lat[:-1], self.lon[:-1], self.lat[1:], self.lon[1:])
        self.cum = np.concatenate([[0.0], np.cumsum(seg)])
        self.length = float(self.cum[-1])
        brg = bearing_deg(self.lat[:-1], self.lon[:-1], self.lat[1:], self.lon[1:])
        self.brg = np.concatenate([brg, brg[-1:]])

    def pos(self, s, c=None):
        s = np.clip(s, 0.0, self.length)
        la = np.interp(s, self.cum, self.lat)
        lo = np.interp(s, self.cum, self.lon)
        idx = np.clip(np.searchsorted(self.cum, s, side="right") - 1, 0, len(self.brg) - 1)
        b = self.brg[idx]
        if c is not None:
            la, lo = dest_point(la, lo, b + 90.0, c)
        return la, lo, b


# --------------------------------------------------------------------------
# Geomagnetic field (World Magnetic Model 2025 via pygeomag) on a grid
# --------------------------------------------------------------------------
class MagGrid:
    def __init__(self, lat0, lat1, lon0, lon1, step, year, alts=(0.0, 13.0)):
        from pygeomag import GeoMag

        gm = GeoMag(coefficients_file="wmm/WMM_2025.COF")
        self.lat0, self.lon0, self.step = lat0, lon0, step
        self.lats = np.arange(lat0, lat1 + 1e-9, step)
        self.lons = np.arange(lon0, lon1 + 1e-9, step)
        self.alts = alts
        self.F = np.zeros((2, len(self.lats), len(self.lons)))
        self.I = np.zeros_like(self.F)
        for k, a in enumerate(alts):
            for i, la in enumerate(self.lats):
                for j, lo in enumerate(self.lons):
                    r = gm.calculate(glat=float(la), glon=float((lo + 180.0) % 360.0 - 180.0), alt=float(a), time=year)
                    self.F[k, i, j] = r.f / 1000.0  # microtesla
                    self.I[k, i, j] = r.i  # degrees
        self.year = year

    def _bilin(self, A, lat, lon):
        fi = (np.asarray(lat) - self.lat0) / self.step
        fj = (np.asarray(lon) - self.lon0) / self.step
        i0 = np.clip(np.floor(fi).astype(int), 0, A.shape[0] - 2)
        j0 = np.clip(np.floor(fj).astype(int), 0, A.shape[1] - 2)
        di = np.clip(fi - i0, 0.0, 1.0)
        dj = np.clip(fj - j0, 0.0, 1.0)
        return (A[i0, j0] * (1 - di) * (1 - dj) + A[i0 + 1, j0] * di * (1 - dj)
                + A[i0, j0 + 1] * (1 - di) * dj + A[i0 + 1, j0 + 1] * di * dj)

    def query(self, lat, lon, alt):
        w = np.clip((np.asarray(alt) - self.alts[0]) / (self.alts[1] - self.alts[0]), 0.0, 1.0)
        F = self._bilin(self.F[0], lat, lon) * (1 - w) + self._bilin(self.F[1], lat, lon) * w
        I = self._bilin(self.I[0], lat, lon) * (1 - w) + self._bilin(self.I[1], lat, lon) * w
        return F, I


# --------------------------------------------------------------------------
# Cosmic-ray hit rate on a phone camera sensor (events per hour)
# --------------------------------------------------------------------------
GEOMAG_POLE = (80.85, -72.76)  # centred-dipole north pole, about 2025
_RC = np.array([0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0, 17.0])
_REL = np.array([1.00, 1.00, 0.88, 0.74, 0.60, 0.48, 0.40, 0.33, 0.30])  # assumed shape


def cutoff_rigidity(lat, lon):
    """Vertical cutoff rigidity in GV, centred-dipole approximation."""
    p, l = np.radians(lat), np.radians(lon)
    pp, lp = math.radians(GEOMAG_POLE[0]), math.radians(GEOMAG_POLE[1])
    sinm = np.sin(p) * math.sin(pp) + np.cos(p) * math.cos(pp) * np.cos(l - lp)
    return 14.9 * (1.0 - sinm ** 2) ** 2


def cosmic_rate(lat, lon, alt_km, P):
    rel = np.interp(cutoff_rigidity(lat, lon), _RC, _REL) / np.interp(P.cr_ref_rc, _RC, _REL)
    cruise = P.cr_cruise_per_h * rel
    return P.cr_ground_per_h + (cruise - P.cr_ground_per_h) * np.clip(np.asarray(alt_km) / 11.0, 0.0, 1.2) ** 3


# --------------------------------------------------------------------------
# Scenario and design values
# --------------------------------------------------------------------------
@dataclass
class Perf:
    # aircraft and atmosphere
    tas: float = 840.0          # cruise true airspeed, km/h (A321neo, about Mach 0.78-0.79)
    tas_sd: float = 12.0
    wind_sd: float = 60.0       # day-to-day sd of along-track wind at cruise, km/h
    wind_var: float = 20.0      # amplitude sd of wind variation along the route, km/h
    wind_wavelen: float = 800.0
    climb_min: float = 18.5     # liftoff to top of climb, minutes
    climb_min_sd: float = 2.0
    climb_exp: float = 3.0
    tod_km: float = 225.0       # track distance from top of descent to touchdown
    tod_km_sd: float = 20.0
    desc_exp: float = 3.0
    v_lo: float = 300.0         # ground speed at liftoff, km/h
    v_app: float = 250.0        # ground speed at touchdown, km/h
    cruise_alt: float = 11.0    # km
    lat_dev_sd: tuple = (8.0, 5.0, 3.0)   # smooth lateral deviation harmonics, km
    dev_taper_km: float = 60.0
    gc_lateral_frac: float = 0.042   # lateral sd assumed when only the great circle is known
    track_lateral_sd: float = 12.0   # lateral sd assumed around a preloaded track, km
    arr_lat_sd: float = 18.0         # extra lateral sd while manoeuvring for the arrival runway, km
    dep_lat_sd: float = 7.0          # extra lateral sd right after takeoff (runway direction unknown), km
    wind_fc_sd: float = 15.0         # error sd of a preloaded winds-aloft forecast, km/h
    # phone sensors
    cue_sd_s: float = 45.0      # timing error of top-of-climb / top-of-descent cues
    hb_sd: float = 5.0          # compass bias after gate calibration, degrees
    hd_noise: float = 2.0       # per-minute heading noise, degrees
    bF_sd: float = 0.7          # field-strength bias after gate calibration, microtesla
    bI_sd: float = 1.5          # dip-angle bias, degrees
    F_noise: float = 0.3        # per-minute averaged noise
    I_noise: float = 0.5
    bF_rw: float = 0.2          # bias random walk per sqrt(30 min)
    bI_rw: float = 0.4
    cr_cruise_per_h: float = 19.33   # measured on an iPhone 13 Pro, BKK-HND cruise
    cr_ground_per_h: float = 3.68
    cr_ref_rc: float = 14.0          # assumed mean cutoff rigidity of that flight, GV


@dataclass
class Scenario:
    name: str
    origin: tuple
    dest: tuple
    o_elev: float
    d_elev: float
    route_pts: list                  # typical en-route waypoints (lat, lon)
    dep_runways: list                # (takeoff heading, probability)
    arr_runways: list                # (landing course, probability)
    perf: Perf = field(default_factory=Perf)
    p_direct: float = 0.35           # chance that an interior waypoint is skipped (direct-to)
    keep_first: int = 2
    keep_last: int = 1
    dep_leg_km: float = 6.0
    faf_km: float = 16.0
    ip_km: float = 8.0
    downwind_offset_km: float = 9.0
    year: float = 2026.88
    zone_km: float = 150.0           # arrival zone over which extra track miles are spread
    fix_min: float = 0.0             # minute of the simulated satellite fix (0 = use fix_frac)
    fix_frac: float = 0.45           # ... or its time as a fraction of the flight


def scenario_w64747():
    route = [
        (42.15944, 21.39722),  # LONTA
        (43.71750, 20.81033),  # VTN
        (43.85467, 20.75311),  # GONAR
        (44.21206, 20.60267),  # NEVAK
        (44.49189, 20.48333),  # ORVAN
        (44.80703, 20.33494),  # BEO
        (46.04731, 19.35593),  # TONDO
        (47.62377, 17.40891),  # TEKNO
        (47.83071, 17.11443),  # BEGLA
        (47.92196, 16.95406),  # GESGI
        (48.10511, 16.62926),  # FMD
        (48.41714, 16.01859),  # STO
        (48.88811, 15.61579),  # LANUX
    ]
    return Scenario(
        name="W6 4747 Skopje-Prague",
        origin=(41.96162, 21.62137), dest=(50.10114, 14.26296),
        o_elev=0.24, d_elev=0.38,
        route_pts=route,
        dep_runways=[(345.0, 0.7), (165.0, 0.3)],            # LWSK runway 34 / 16
        arr_runways=[(245.0, 0.60), (65.0, 0.25), (307.0, 0.12), (127.0, 0.03)],  # LKPR 24 / 06 / 30 / 12
        fix_min=40.0,
    )


def scenario_longhaul(kind):
    """Indicative long-haul legs. The 'typical route' is the great circle bent by a fixed offset."""
    if kind == "ns":
        name, o, d = "Long-haul north-south (Frankfurt-Johannesburg)", (50.0333, 8.5706), (-26.1392, 28.2460)
    else:
        name, o, d = "Long-haul east-west (Frankfurt-New York)", (50.0333, 8.5706), (40.6413, -73.7781)
    n = 24
    route = []
    b0 = None
    for i in range(1, n):
        f = i / n
        la, lo = gc_interp(o[0], o[1], d[0], d[1], f)
        la2, lo2 = gc_interp(o[0], o[1], d[0], d[1], min(1.0, f + 0.01))
        b = float(bearing_deg(la, lo, la2, lo2))
        off = 120.0 * math.sin(math.pi * f) * (1 if kind == "ns" else -1)  # typical routing offset
        la, lo = dest_point(la, lo, b + 90.0, off)
        route.append((float(la), float(lo)))
        if b0 is None:
            b0 = b
    b_end = float(bearing_deg(route[-1][0], route[-1][1], d[0], d[1]))
    perf = Perf(tas=900.0, tas_sd=15.0, wind_sd=50.0, wind_var=45.0, wind_wavelen=2500.0,
                climb_min=25.0, climb_min_sd=3.0, tod_km=250.0, tod_km_sd=25.0,
                cruise_alt=11.5, lat_dev_sd=(60.0, 35.0, 20.0), dev_taper_km=150.0,
                track_lateral_sd=60.0)
    return Scenario(
        name=name, origin=o, dest=d, o_elev=0.1, d_elev=1.7 if kind == "ns" else 0.0,
        route_pts=route,
        dep_runways=[(b0, 0.6), ((b0 + 180.0) % 360.0, 0.4)],
        arr_runways=[(b_end, 0.4), ((b_end + 90.0) % 360.0, 0.2), ((b_end + 180.0) % 360.0, 0.2),
                     ((b_end + 270.0) % 360.0, 0.2)],
        perf=perf, p_direct=0.15, zone_km=200.0,
    )


# --------------------------------------------------------------------------
# Truth simulation
# --------------------------------------------------------------------------
def build_truth_path(sc, rng):
    o, d = sc.origin, sc.dest
    hd = [h for h, _ in sc.dep_runways]
    pr = np.array([p for _, p in sc.dep_runways])
    dep_h = hd[rng.choice(len(hd), p=pr / pr.sum())]
    la, lo = dest_point(o[0], o[1], dep_h, sc.dep_leg_km)
    pts = [o, (float(la), float(lo))]
    enr = list(sc.route_pts)
    for i, p in enumerate(enr):
        interior = sc.keep_first <= i < len(enr) - sc.keep_last
        if interior and rng.random() < sc.p_direct:
            continue
        pts.append(p)
    cr = [c for c, _ in sc.arr_runways]
    pr = np.array([p for _, p in sc.arr_runways])
    arr_c = cr[rng.choice(len(cr), p=pr / pr.sum())]
    back = (arr_c + 180.0) % 360.0
    ip = dest_point(d[0], d[1], back, sc.faf_km + sc.ip_km)
    last = pts[-1]
    inbound = float(bearing_deg(last[0], last[1], d[0], d[1]))
    if abs(float(ang_diff(arr_c, inbound))) > 110.0:
        # must fly past the field: downwind leg abeam the airport, then base to the intercept point
        a1 = dest_point(d[0], d[1], arr_c + 90.0, sc.downwind_offset_km)
        a2 = dest_point(d[0], d[1], arr_c - 90.0, sc.downwind_offset_km)
        ab = a1 if hav_km(last[0], last[1], a1[0], a1[1]) < hav_km(last[0], last[1], a2[0], a2[1]) else a2
        dw = dest_point(ab[0], ab[1], back, sc.faf_km + sc.ip_km)
        pts += [(float(ab[0]), float(ab[1])), (float(dw[0]), float(dw[1]))]
    pts += [(float(ip[0]), float(ip[1])), d]
    return Track(pts), dep_h, arr_c


def simulate_truth(sc, grid, rng):
    P = sc.perf
    track, dep_h, arr_c = build_truth_path(sc, rng)
    L = track.length
    wind0 = rng.normal(0.0, P.wind_sd)
    windA = abs(rng.normal(0.0, P.wind_var))
    windphi = rng.uniform(0, 2 * np.pi)
    xwind = rng.normal(0.0, P.wind_sd)
    tas = rng.normal(P.tas, P.tas_sd)
    Tc = max(12.0, rng.normal(P.climb_min, P.climb_min_sd)) * 60.0
    Dtod = max(0.5 * P.tod_km, rng.normal(P.tod_km, P.tod_km_sd))
    amps = [rng.normal(0.0, a) for a in P.lat_dev_sd]
    phis = rng.uniform(0, 2 * np.pi, len(amps))

    def dev(p):
        taper = np.clip(np.minimum(p, L - p) / P.dev_taper_km, 0.0, 1.0)
        tot = 0.0
        for k, (a, ph) in enumerate(zip(amps, phis)):
            tot = tot + a * np.sin((k + 1) * np.pi * p / L + ph)
        return taper * tot

    dt = 5.0
    t, p, phase = 0.0, 0.0, 0
    t_toc = None
    t_tod = None
    rem0 = vtod = alt_tod = None
    alt = sc.o_elev
    v = P.v_lo
    T, PP, ALT = [], [], []
    while p < L:
        rem = L - p
        vcr = tas + wind0 + windA * math.sin(2 * math.pi * p / P.wind_wavelen + windphi)
        if phase == 0 and t >= Tc:
            phase = 1
            t_toc = t
        if phase == 0:
            x = t / Tc
            v = P.v_lo + (vcr - P.v_lo) * (1.0 - (1.0 - x) ** P.climb_exp)
            alt = sc.o_elev + (P.cruise_alt - sc.o_elev) * x ** 0.8
        elif phase == 1:
            v = vcr
            alt = P.cruise_alt
        if phase < 2 and rem <= Dtod:
            phase = 2
            t_tod, rem0, vtod, alt_tod = t, rem, v, alt
        if phase == 2:
            u = 1.0 - rem / rem0
            v = P.v_app + (vtod - P.v_app) * (1.0 - u ** P.desc_exp)
            alt = sc.d_elev + (alt_tod - sc.d_elev) * (1.0 - u)
        T.append(t)
        PP.append(p)
        ALT.append(alt)
        p += v * dt / 3600.0
        t += dt
    T = np.array(T)
    PP = np.array(PP)
    ALT = np.array(ALT)
    lat, lon, brg = track.pos(PP, dev(PP))
    t_land = float(T[-1] + dt)

    # --- phone observations -------------------------------------------------
    obs = {}
    obs["t_toc"] = None if t_toc is None else float(t_toc + rng.normal(0.0, P.cue_sd_s))
    obs["t_tod"] = float(t_tod + rng.normal(0.0, P.cue_sd_s))
    tm = np.arange(60.0, t_land, 60.0)
    im = np.clip(np.searchsorted(T, tm), 0, len(T) - 1)
    Ft, It = grid.query(lat[im], lon[im], ALT[im])
    nrw = len(tm)
    q = math.sqrt(60.0 / 1800.0)
    bF = rng.normal(0.0, P.bF_sd) + np.cumsum(rng.normal(0.0, P.bF_rw * q, nrw))
    bI = rng.normal(0.0, P.bI_sd) + np.cumsum(rng.normal(0.0, P.bI_rw * q, nrw))
    obs["t_min"] = tm
    obs["F"] = Ft + bF + rng.normal(0.0, P.F_noise, nrw)
    obs["I"] = It + bI + rng.normal(0.0, P.I_noise, nrw)
    crab = math.degrees(math.asin(max(-0.5, min(0.5, xwind / tas))))
    hb = rng.normal(0.0, P.hb_sd)
    obs["hdg"] = (brg[im] + crab + hb + rng.normal(0.0, P.hd_noise, nrw)) % 360.0
    tc = np.arange(600.0, t_land, 600.0)
    cnt = []
    for te in tc:
        j = int(np.clip(np.searchsorted(T, te - 300.0), 0, len(T) - 1))
        lam = float(cosmic_rate(lat[j], lon[j], ALT[j], P)) / 6.0
        cnt.append(rng.poisson(lam))
    obs["t_cr"] = tc
    obs["cr"] = np.array(cnt)
    return dict(T=T, lat=lat, lon=lon, alt=ALT, brg=brg, t_land=t_land, L=L, dep_h=dep_h, arr_c=arr_c,
                t_toc=t_toc, t_tod=t_tod, obs=obs, wind0=wind0, tas=tas)


# --------------------------------------------------------------------------
# Particle filters
# --------------------------------------------------------------------------
def systematic_resample(w, rng):
    n = len(w)
    cs = np.cumsum(w)
    cs[-1] = 1.0
    return np.searchsorted(cs, (rng.random() + np.arange(n)) / n)


def mean_and_r95(lat, lon, w):
    p, l = np.radians(lat), np.radians(lon)
    x = np.sum(w * np.cos(p) * np.cos(l))
    y = np.sum(w * np.cos(p) * np.sin(l))
    z = np.sum(w * np.sin(p))
    mlat = math.degrees(math.atan2(z, math.hypot(x, y)))
    mlon = math.degrees(math.atan2(y, x))
    d = hav_km(lat, lon, mlat, mlon)
    o = np.argsort(d)
    cw = np.cumsum(w[o])
    r95 = float(d[o][min(len(d) - 1, int(np.searchsorted(cw, 0.95)))])
    return mlat, mlon, r95


def _norm_w(logw):
    w = np.exp(logw - np.max(logw))
    return w / np.sum(w)


class _Common:
    """State and updates shared by both filters."""

    def __init__(self, sc, grid, truth, opt, rng, n):
        self.sc, self.P, self.grid, self.truth, self.opt, self.rng, self.n = sc, sc.perf, grid, truth, opt, rng, n
        P = self.P
        if opt.get("wind_fc"):
            fc = truth["wind0"] + rng.normal(0.0, P.wind_fc_sd)   # forecast = truth + forecast error
            self.wind = rng.normal(fc, P.wind_fc_sd, n)
        else:
            self.wind = rng.normal(0.0, P.wind_sd, n)
        self.wdev = np.zeros(n)
        self.tas = rng.normal(P.tas, P.tas_sd, n)
        self.Tc = np.maximum(12.0, rng.normal(P.climb_min, P.climb_min_sd, n)) * 60.0
        self.Dtod = np.maximum(0.5 * P.tod_km, rng.normal(P.tod_km, P.tod_km_sd, n))
        # Rao-Blackwellised magnetometer biases: mean per particle, shared variance
        self.mF = np.zeros(n)
        self.mI = np.zeros(n)
        self.PF = P.bF_sd ** 2
        self.PI = P.bI_sd ** 2
        self.phase = np.zeros(n, dtype=int)
        self.rem0 = np.full(n, np.nan)
        self.vtod = np.full(n, np.nan)
        self.alt_tod = np.full(n, P.cruise_alt)
        self.logw = np.zeros(n)
        self.toc_done = False
        self.tod_done = False

    def arrays(self):
        return ["wind", "wdev", "tas", "Tc", "Dtod", "mF", "mI", "phase", "rem0", "vtod", "alt_tod"]

    def resample_if_needed(self, force=False):
        w = _norm_w(self.logw)
        ess = 1.0 / np.sum(w ** 2)
        if force or ess < 0.5 * self.n:
            idx = systematic_resample(w, self.rng)
            for name in self.arrays():
                setattr(self, name, getattr(self, name)[idx].copy())
            self.logw = np.zeros(self.n)
            r, n = self.rng, self.n
            self.wind += r.normal(0.0, 2.0, n)
            self.tas += r.normal(0.0, 1.0, n)
            self.Dtod += r.normal(0.0, 2.0, n)
            self.after_resample_jitter()

    def after_resample_jitter(self):
        pass

    def climb_speed(self, t):
        P = self.P
        x = np.clip(t / self.Tc, 0.0, 1.0)
        vcr = self.tas + self.wind + self.wdev
        return P.v_lo + (vcr - P.v_lo) * (1.0 - (1.0 - x) ** P.climb_exp)

    def step_wind(self, v, dt):
        P = self.P
        ell = P.wind_wavelen / (2 * np.pi)
        rho = np.exp(-v * dt / 3600.0 / ell)
        sd = 0.7 * P.wind_var
        self.wdev = self.wdev * rho + self.rng.normal(0.0, 1.0, self.n) * sd * np.sqrt(1 - rho ** 2)

    def toc_cue(self, t):
        if self.opt["cues"] and not self.toc_done and self.truth["obs"]["t_toc"] is not None \
                and t >= self.truth["obs"]["t_toc"]:
            self.logw += -0.5 * ((self.Tc - self.truth["obs"]["t_toc"]) / 60.0) ** 2
            self.toc_done = True
            self.resample_if_needed()

    def fix_time(self, dt):
        """Time of the single simulated satellite fix, or None."""
        if not self.opt.get("fix"):
            return None
        if self.sc.fix_min:
            return round(self.sc.fix_min * 60.0 / dt) * dt
        return round(self.sc.fix_frac * self.truth["t_land"] / dt) * dt

    def fix_position(self, t):
        tr = self.truth
        j = int(np.clip(np.searchsorted(tr["T"], t), 0, len(tr["T"]) - 1))
        return float(tr["lat"][j]), float(tr["lon"][j])

    def weight_by_fix(self, lat, lon, flat, flon):
        d = hav_km(lat, lon, flat, flon)
        sig = 8.0
        base = self.logw.copy()
        for _ in range(12):
            lw = base - 0.5 * (d / sig) ** 2
            w = _norm_w(lw)
            if 1.0 / np.sum(w ** 2) >= 0.05 * self.n:
                break
            sig *= 1.5
        self.logw = lw
        self.resample_if_needed(force=True)
        self.wind += self.rng.normal(0.0, 4.0, self.n)

    def altitude(self, t, u):
        P, sc = self.P, self.sc
        x = np.clip(t / self.Tc, 0.0, 1.0)
        alt = np.where(self.phase == 0, sc.o_elev + (P.cruise_alt - sc.o_elev) * x ** 0.8, P.cruise_alt)
        return np.where(self.phase == 2, sc.d_elev + (self.alt_tod - sc.d_elev) * (1.0 - np.clip(u, 0, 1)), alt)

    def sensor_updates(self, t, lat, lon, alt):
        P, o = self.P, self.truth["obs"]
        if self.opt["mag"]:
            k = int(round(t / 60.0)) - 1
            if abs(t - (k + 1) * 60.0) < 1e-6 and 0 <= k < len(o["t_min"]):
                self.PF += P.bF_rw ** 2 * (60.0 / 1800.0)
                self.PI += P.bI_rw ** 2 * (60.0 / 1800.0)
                Fp, Ip = self.grid.query(lat, lon, alt)
                SF = self.PF + P.F_noise ** 2
                SI = self.PI + P.I_noise ** 2
                nuF = o["F"][k] - Fp - self.mF
                nuI = o["I"][k] - Ip - self.mI
                self.logw += -0.5 * nuF ** 2 / SF - 0.5 * nuI ** 2 / SI
                KF, KI = self.PF / SF, self.PI / SI
                self.mF += KF * nuF
                self.mI += KI * nuI
                self.PF *= (1.0 - KF)
                self.PI *= (1.0 - KI)
        if self.opt["cosmic"]:
            k = int(round(t / 600.0)) - 1
            if abs(t - (k + 1) * 600.0) < 1e-6 and 0 <= k < len(o["t_cr"]):
                lam = np.maximum(1e-6, cosmic_rate(lat, lon, alt, P) / 6.0)
                self.logw += o["cr"][k] * np.log(lam) - lam


class KnownRouteFilter(_Common):
    def __init__(self, sc, grid, truth, ref, e_prior, opt, rng, n=1500):
        super().__init__(sc, grid, truth, opt, rng, n)
        self.ref = ref
        self.s = np.zeros(n)
        self.c_sd = opt["c_sd"]
        self.c = rng.normal(0.0, 2.0, n)
        self.c_arr = rng.normal(0.0, sc.perf.arr_lat_sd, n)
        self.c_dep = rng.normal(0.0, sc.perf.dep_lat_sd, n)
        self.E = np.maximum(0.0, rng.normal(e_prior[0], e_prior[1], n))
        self.negfac = np.zeros(n)

    def arrays(self):
        return super().arrays() + ["s", "c", "c_arr", "c_dep", "E", "negfac"]

    def after_resample_jitter(self):
        self.E = np.maximum(0.0, self.E + self.rng.normal(0.0, 1.0, self.n))

    def remaining(self):
        L, z = self.ref.length, self.sc.zone_km
        return (L - self.s) + self.E * np.clip((L - self.s) / z, 0.0, 1.0)

    def lateral(self):
        """Cross-track offset: en-route wander plus departure and arrival manoeuvring terms."""
        L, z = self.ref.length, self.sc.zone_km
        g = np.clip((self.s - (L - z)) / (0.5 * z), 0.0, 1.0) * np.clip((L - self.s) / 30.0, 0.0, 1.0)
        return self.c + self.c_arr * g + self.c_dep * np.exp(-self.s / 40.0)

    def run(self, dt=30.0):
        P, sc, ref = self.P, self.sc, self.ref
        L, z = ref.length, sc.zone_km
        tr = self.truth
        times = np.arange(dt, tr["t_land"], dt)
        out = np.full((len(times), 3), np.nan)
        cues = self.opt["cues"]
        t_tod_obs = tr["obs"]["t_tod"]
        ell_c = max(150.0, 0.15 * L)
        fix_t = self.fix_time(dt)
        for k, t in enumerate(times):
            tm = t - dt / 2
            self.phase = np.where((self.phase == 0) & (tm >= self.Tc), 1, self.phase)
            rem = self.remaining()
            vcr = self.tas + self.wind + self.wdev
            v = np.where(self.phase == 0, self.climb_speed(tm), vcr)
            if not cues:
                start = (self.phase < 2) & (rem <= self.Dtod)
                if np.any(start):
                    self.rem0[start] = rem[start]
                    self.vtod[start] = v[start]
                    self.alt_tod[start] = self.altitude(tm, 0.0)[start]
                    self.phase[start] = 2
            in2 = self.phase == 2
            u = np.clip(np.where(in2, 1.0 - rem / np.where(in2, self.rem0, 1.0), 0.0), 0.0, 1.0)
            v = np.where(in2, P.v_app + (self.vtod - P.v_app) * (1.0 - u ** P.desc_exp), v)
            v = np.where(np.isnan(v), vcr, v)
            kfac = np.where(self.s >= L - z, z / (z + self.E), 1.0)
            self.s = np.minimum(L, self.s + v * dt / 3600.0 * kfac)
            self.step_wind(v, dt)
            rho = np.exp(-v * dt / 3600.0 / ell_c)
            self.c = self.c * rho + self.rng.normal(0.0, 1.0, self.n) * self.c_sd * np.sqrt(1 - rho ** 2)
            # flight-phase cues
            self.toc_cue(t)
            if cues:
                rem = self.remaining()
                sig = np.maximum(200.0, self.tas + self.wind) * 75.0 / 3600.0
                if not self.tod_done and t >= t_tod_obs:
                    self.logw += -0.5 * ((rem - self.Dtod) / sig) ** 2 - self.negfac
                    self.negfac[:] = 0.0
                    self.tod_done = True
                    self.resample_if_needed(force=True)
                    self.rem0 = self.remaining().copy()
                    self.vtod = (self.tas + self.wind + self.wdev).copy()
                    self.alt_tod = np.full(self.n, P.cruise_alt)
                    self.phase[:] = 2
                elif not self.tod_done:
                    nf = -0.5 * (np.maximum(0.0, self.Dtod - rem) / sig) ** 2
                    self.logw += nf - self.negfac
                    self.negfac = nf
            # satellite fix: learn from it, then continue from the exact position
            if fix_t is not None and abs(t - fix_t) < 1e-6:
                flat, flon = self.fix_position(t)
                lat, lon, _ = ref.pos(self.s, self.lateral())
                self.weight_by_fix(lat, lon, flat, flon)
                j = int(np.argmin(hav_km(ref.lat, ref.lon, flat, flon)))
                d0 = float(hav_km(ref.lat[j], ref.lon[j], flat, flon))
                th = math.radians(float(bearing_deg(ref.lat[j], ref.lon[j], flat, flon)) - float(ref.brg[j]))
                s_fix = float(np.clip(ref.cum[j] + d0 * math.cos(th), 0.0, L))
                self.s = np.full(self.n, s_fix)
                self.c = np.zeros(self.n)
                self.c = d0 * math.sin(th) - self.lateral()
            # sensors
            rem = self.remaining()
            in2 = self.phase == 2
            u = np.where(in2, 1.0 - rem / np.where(in2, self.rem0, 1.0), 0.0)
            lat, lon, _ = ref.pos(self.s, self.lateral())
            self.sensor_updates(t, lat, lon, self.altitude(t, u))
            if k % 2 == 1:
                out[k] = mean_and_r95(lat, lon, _norm_w(self.logw))
            self.resample_if_needed()
        return times, out


class UnknownRouteFilter(_Common):
    def __init__(self, sc, grid, truth, opt, rng, n=1500):
        super().__init__(sc, grid, truth, opt, rng, n)
        P = self.P
        self.lat, self.lon = dest_point(np.full(n, sc.origin[0]), np.full(n, sc.origin[1]),
                                        rng.uniform(0.0, 360.0, n), np.abs(rng.normal(0.0, 4.0, n)))
        crab_sd = math.degrees(math.asin(P.wind_sd / P.tas))
        self.hb = rng.normal(0.0, math.hypot(P.hb_sd, crab_sd), n)
        self.dsince = np.zeros(n)

    def arrays(self):
        return super().arrays() + ["lat", "lon", "hb", "dsince"]

    def after_resample_jitter(self):
        self.hb += self.rng.normal(0.0, 0.2, self.n)

    def run(self, dt=30.0):
        P = self.P
        tr = self.truth
        o = tr["obs"]
        times = np.arange(dt, tr["t_land"], dt)
        out = np.full((len(times), 3), np.nan)
        t_tod_obs = o["t_tod"]
        nh = len(o["hdg"])
        fix_t = self.fix_time(dt)
        for k, t in enumerate(times):
            tm = t - dt / 2
            self.phase = np.where((self.phase == 0) & (tm >= self.Tc), 1, self.phase)
            vcr = self.tas + self.wind + self.wdev
            v = np.where(self.phase == 0, self.climb_speed(tm), vcr)
            if not self.tod_done and t >= t_tod_obs:
                self.tod_done = True
                self.phase[:] = 2
                self.rem0 = self.Dtod.copy()
                self.vtod = vcr.copy()
                self.alt_tod = np.full(self.n, P.cruise_alt)
                self.dsince[:] = 0.0
            in2 = self.phase == 2
            u = np.clip(np.where(in2, self.dsince / np.where(in2, self.rem0, 1.0), 0.0), 0.0, 1.0)
            v = np.where(in2, P.v_app + (self.vtod - P.v_app) * (1.0 - u ** P.desc_exp), v)
            v = np.where(np.isnan(v), vcr, v)
            if nh == 0 or tm < 45.0:
                h_meas = tr["dep_h"]
            else:
                h_meas = o["hdg"][int(np.clip(round(tm / 60.0) - 1, 0, nh - 1))]
            h = h_meas - self.hb + self.rng.normal(0.0, P.hd_noise, self.n)
            d = v * dt / 3600.0
            self.lat, self.lon = dest_point(self.lat, self.lon, h, d)
            self.dsince += np.where(in2, d, 0.0)
            self.step_wind(v, dt)
            self.toc_cue(t)
            if fix_t is not None and abs(t - fix_t) < 1e-6:
                flat, flon = self.fix_position(t)
                self.weight_by_fix(self.lat, self.lon, flat, flon)
                self.hb += self.rng.normal(0.0, 0.5, self.n)
                self.lat = np.full(self.n, flat)
                self.lon = np.full(self.n, flon)
            self.sensor_updates(t, self.lat, self.lon, self.altitude(t, u))
            if k % 2 == 1 or k == len(times) - 1:
                out[k] = mean_and_r95(self.lat, self.lon, _norm_w(self.logw))
            self.resample_if_needed()
        self.final_hb = float(np.sum(_norm_w(self.logw) * self.hb))
        return times, out


# --------------------------------------------------------------------------
# Experiment driver
# --------------------------------------------------------------------------
def e_prior_for(sc, ref_len, rng, n=1500):
    """Prior for extra track miles relative to a reference track (departure and arrival manoeuvres)."""
    ex = np.array([build_truth_path(sc, rng)[0].length - ref_len for _ in range(n)])
    return float(np.mean(ex)), float(np.std(ex))


def load_airports(path_air, path_rwy):
    aps = {}
    with open(path_air, newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r.get("type") in ("large_airport", "medium_airport") and r.get("scheduled_service") == "yes":
                try:
                    aps[r["ident"]] = dict(ident=r["ident"], iata=r.get("iata_code", ""), name=r.get("name", ""),
                                           lat=float(r["latitude_deg"]), lon=float(r["longitude_deg"]), hdg=[])
                except ValueError:
                    pass
    if path_rwy:
        with open(path_rwy, newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                a = aps.get(r.get("airport_ident"))
                if a is None or r.get("closed") == "1":
                    continue
                got = False
                for key in ("le_heading_degT", "he_heading_degT"):
                    try:
                        a["hdg"].append(float(r[key]))
                        got = True
                    except (ValueError, KeyError, TypeError):
                        pass
                if not got:
                    try:
                        b = float(bearing_deg(float(r["le_latitude_deg"]), float(r["le_longitude_deg"]),
                                              float(r["he_latitude_deg"]), float(r["he_longitude_deg"])))
                        a["hdg"] += [b, (b + 180.0) % 360.0]
                    except (ValueError, KeyError, TypeError):
                        pass
    return list(aps.values())


def landing_snap(airports, est_lat, est_lon, r95, course, dest):
    """Rank candidate airports at touchdown by distance from the estimate and runway alignment."""
    la = np.array([a["lat"] for a in airports])
    lo = np.array([a["lon"] for a in airports])
    d = hav_km(la, lo, est_lat, est_lon)
    rr = max(r95, 30.0)
    sig = rr / 2.45
    near = np.where(d <= rr)[0]
    cand = np.where(d <= 3.0 * rr)[0]
    best_pos = best_both = None
    sc_pos = sc_both = 1e18
    for i in cand:
        chi = (d[i] / sig) ** 2
        if chi < sc_pos:
            sc_pos, best_pos = chi, i
        hd = airports[i]["hdg"]
        dh = min([abs(float(ang_diff(h, course))) for h in hd]) if hd else 30.0
        chi2 = chi + (dh / 8.0) ** 2
        if chi2 < sc_both:
            sc_both, best_both = chi2, i
    true_i = int(np.argmin(hav_km(la, lo, dest[0], dest[1])))
    return dict(n_in_r95=int(len(near)), hit_pos=bool(best_pos == true_i), hit_both=bool(best_both == true_i))


def all_configs(sc, gc_len):
    P = sc.perf
    cg = P.gc_lateral_frac * gc_len
    ct = P.track_lateral_sd
    return {
        "baseline_clock_gc": None,
        "A1_gc_clock": dict(kind="known", ref="gc", cues=False, mag=False, cosmic=False, c_sd=cg),
        "A2_gc_cues": dict(kind="known", ref="gc", cues=True, mag=False, cosmic=False, c_sd=cg),
        "A3_track_clock": dict(kind="known", ref="typ", cues=False, mag=False, cosmic=False, c_sd=ct),
        "A4_track_cues": dict(kind="known", ref="typ", cues=True, mag=False, cosmic=False, c_sd=ct),
        "A5_track_cues_mag": dict(kind="known", ref="typ", cues=True, mag=True, cosmic=False, c_sd=ct),
        "A6_track_cues_mag_cosmic": dict(kind="known", ref="typ", cues=True, mag=True, cosmic=True, c_sd=ct),
        "A7_track_cues_windfc": dict(kind="known", ref="typ", cues=True, mag=False, cosmic=False, c_sd=ct, wind_fc=True),
        "A8_track_cues_windfc_mag": dict(kind="known", ref="typ", cues=True, mag=True, cosmic=False, c_sd=ct, wind_fc=True),
        "A9_track_cues_fix": dict(kind="known", ref="typ", cues=True, mag=False, cosmic=False, c_sd=ct, fix=True),
        "B1_unknown_compass": dict(kind="unknown", cues=True, mag=False, cosmic=False),
        "B2_unknown_compass_mag": dict(kind="unknown", cues=True, mag=True, cosmic=False),
        "B3_unknown_compass_mag_cosmic": dict(kind="unknown", cues=True, mag=True, cosmic=True),
        "B4_unknown_compass_fix": dict(kind="unknown", cues=True, mag=False, cosmic=False, fix=True),
    }


def run_experiment(sc, runs, seed, airports=None, n_particles=1500, only=None, verbose=True,
                   raw_only=False, raw=None):
    rng = np.random.default_rng(seed)
    o, d = sc.origin, sc.dest
    gc = Track([o, d])
    typ = Track([o] + list(sc.route_pts) + [d])
    pad = 8.0
    lats = np.concatenate([gc.lat, typ.lat])
    lons = np.concatenate([gc.lon, typ.lon])
    lat0, lat1 = math.floor(lats.min() - pad), math.ceil(lats.max() + pad)
    lon0, lon1 = math.floor(lons.min() - pad - 4), math.ceil(lons.max() + pad + 4)
    step = 0.5 if (lat1 - lat0) * (lon1 - lon0) < 2500 else 1.0
    t0 = time.time()
    grid = MagGrid(max(-89.0, lat0), min(89.0, lat1), lon0, lon1, step, sc.year)
    if verbose:
        print(f"magnetic grid {grid.F.shape} built in {time.time() - t0:.1f}s", flush=True)
    ep_gc = e_prior_for(sc, gc.length, rng)
    ep_typ = e_prior_for(sc, typ.length, rng)
    P = sc.perf
    sep = np.array([hav_km(gc.lat, gc.lon, a, b).min() for a, b in zip(typ.lat[::5], typ.lon[::5])])

    configs = all_configs(sc, gc.length)
    if only:
        configs = {k: v for k, v in configs.items() if k in only}
    if raw is not None:
        res, meta, max_min = raw
        runs = len(meta["air_min"])
        loop_runs = 0
    else:
        max_min = 0
        res = {k: dict(err=[], r95=[]) for k in configs}
        meta = dict(air_min=[], path_km=[], cr_total=[], snaps={k: [] for k in configs if k.startswith("B")})
        loop_runs = runs
    for r in range(loop_runs):
        tr = simulate_truth(sc, grid, rng)
        meta["air_min"].append(tr["t_land"] / 60.0)
        meta["path_km"].append(tr["L"])
        meta["cr_total"].append(int(np.sum(tr["obs"]["cr"])))
        times = np.arange(30.0, tr["t_land"], 30.0)
        it = np.clip(np.searchsorted(tr["T"], times), 0, len(tr["T"]) - 1)
        tlat, tlon = tr["lat"][it], tr["lon"][it]
        for name, cfg in configs.items():
            if cfg is None:
                s = np.minimum(gc.length, P.tas * times / 3600.0)
                la, lo, _ = gc.pos(s)
                est = np.column_stack([la, lo, np.full(len(times), np.nan)])
            elif cfg["kind"] == "known":
                ref = gc if cfg["ref"] == "gc" else typ
                ep = ep_gc if cfg["ref"] == "gc" else ep_typ
                _, est = KnownRouteFilter(sc, grid, tr, ref, ep, cfg, rng, n_particles).run()
            else:
                f = UnknownRouteFilter(sc, grid, tr, cfg, rng, n_particles)
                _, est = f.run()
                if airports is not None:
                    course = (tr["obs"]["hdg"][-1] - f.final_hb) % 360.0
                    meta["snaps"][name].append(landing_snap(airports, est[-1, 0], est[-1, 1], est[-1, 2], course, d))
            res[name]["err"].append(hav_km(est[:, 0], est[:, 1], tlat, tlon))
            res[name]["r95"].append(est[:, 2])
        max_min = max(max_min, int(tr["t_land"] // 60))
        if verbose and (r + 1) % max(1, runs // 10) == 0:
            print(f"  run {r + 1}/{runs}  elapsed {time.time() - t0:.0f}s", flush=True)

    if raw_only:
        return res, meta, max_min

    # --- aggregate on a 1-minute grid (step index 2m-1 is minute m) ---------
    am = np.array(meta["air_min"])
    pk = np.array(meta["path_km"])
    summary = dict(scenario=sc.name, runs=runs, particles=n_particles, seed=seed, perf=asdict(P),
                   gc_km=gc.length, typical_track_km=typ.length,
                   track_vs_gc_lateral_km=dict(max=float(sep.max()), mean=float(sep.mean())),
                   extra_km_vs_gc=dict(mean=ep_gc[0], sd=ep_gc[1]),
                   extra_km_vs_track=dict(mean=ep_typ[0], sd=ep_typ[1]),
                   airborne_min=dict(median=float(np.median(am)), p10=float(np.percentile(am, 10)),
                                     p90=float(np.percentile(am, 90)), min=float(am.min()), max=float(am.max())),
                   path_km=dict(median=float(np.median(pk)), p10=float(np.percentile(pk, 10)),
                                p90=float(np.percentile(pk, 90))),
                   cosmic_hits_per_flight=dict(median=float(np.median(meta["cr_total"])),
                                               mean=float(np.mean(meta["cr_total"]))),
                   mag=dict(F_origin=float(grid.query(o[0], o[1], 0.0)[0]), I_origin=float(grid.query(o[0], o[1], 0.0)[1]),
                            F_dest=float(grid.query(d[0], d[1], 0.0)[0]), I_dest=float(grid.query(d[0], d[1], 0.0)[1])),
                   cutoff_gv=dict(origin=float(cutoff_rigidity(o[0], o[1])), dest=float(cutoff_rigidity(d[0], d[1]))),
                   series={}, overall={}, snaps={})
    for name in configs:
        E = np.full((runs, max_min + 1), np.nan)
        Rr = np.full((runs, max_min + 1), np.nan)
        fracE = np.full((runs, 21), np.nan)
        for i, (e, rr) in enumerate(zip(res[name]["err"], res[name]["r95"])):
            m = np.arange(1, len(e) // 2 + 1)
            E[i, m] = e[2 * m - 1]
            Rr[i, m] = rr[2 * m - 1]
            idx = 2 * np.clip(np.round(np.linspace(0.05, 0.95, 19) * (len(e) // 2)).astype(int), 1, len(e) // 2) - 1
            fracE[i, 1:20] = e[idx]
        alive = np.sum(~np.isnan(E), axis=0)
        ser = []
        for m in range(1, max_min + 1):
            if alive[m] >= 0.9 * runs:
                row = dict(minute=m, n=int(alive[m]), median=float(np.nanmedian(E[:, m])),
                           p90=float(np.nanpercentile(E[:, m], 90)), mean=float(np.nanmean(E[:, m])))
                ok = ~np.isnan(E[:, m]) & ~np.isnan(Rr[:, m])
                if np.any(ok):
                    row["r95_median"] = float(np.median(Rr[ok, m]))
                    row["coverage"] = float(np.mean(E[ok, m] <= Rr[ok, m]))
                ser.append(row)
        summary["series"][name] = ser
        summary.setdefault("by_fraction", {})[name] = [
            dict(frac=round(0.05 * j, 2), median=float(np.nanmedian(fracE[:, j])), p90=float(np.nanpercentile(fracE[:, j], 90)))
            for j in range(1, 20)]
        allE = E[~np.isnan(E)]
        ov = dict(median=float(np.median(allE)), p90=float(np.percentile(allE, 90)), p99=float(np.percentile(allE, 99)),
                  max=float(np.max(allE)))
        okm = ~np.isnan(E) & ~np.isnan(Rr)
        if np.any(okm):
            ov["coverage95"] = float(np.mean(E[okm] <= Rr[okm]))
            ov["r95_median"] = float(np.median(Rr[okm]))
        last = [float(np.nanmean(e[-10:])) for e in res[name]["err"]]
        ov["last5min_median"] = float(np.median(last))
        ov["last5min_p90"] = float(np.percentile(last, 90))
        ov["midflight_median"] = float(np.nanmedian(fracE[:, 10]))
        ov["midflight_p90"] = float(np.nanpercentile(fracE[:, 10], 90))
        summary["overall"][name] = ov
    for name, lst in meta["snaps"].items():
        if lst:
            summary["snaps"][name] = dict(
                airports_in_r95_median=float(np.median([x["n_in_r95"] for x in lst])),
                hit_rate_position_only=float(np.mean([x["hit_pos"] for x in lst])),
                hit_rate_with_runway=float(np.mean([x["hit_both"] for x in lst])))
    return summary


SCENARIOS = {"w64747": scenario_w64747, "long_ns": lambda: scenario_longhaul("ns"),
             "long_ew": lambda: scenario_longhaul("ew")}


def _worker(args):
    scen, runs, seed, air, rwy, n_particles, only, verbose = args
    airports = load_airports(air, rwy) if air else None
    return run_experiment(SCENARIOS[scen](), runs, seed, airports, n_particles, only, verbose=verbose, raw_only=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--seed", type=int, default=4747)
    ap.add_argument("--particles", type=int, default=1500)
    ap.add_argument("--scenario", default="w64747", choices=["w64747", "long_ns", "long_ew"])
    ap.add_argument("--only", default="", help="comma-separated config names to run")
    ap.add_argument("--airports")
    ap.add_argument("--runways")
    ap.add_argument("--out", default="out")
    ap.add_argument("--jobs", type=int, default=1, help="parallel worker processes")
    a = ap.parse_args()
    sc = SCENARIOS[a.scenario]()
    airports = load_airports(a.airports, a.runways) if a.airports else None
    if airports is not None:
        print(f"{len(airports)} airports with scheduled service loaded")
    only = [x for x in a.only.split(",") if x] or None
    if a.jobs > 1:
        import multiprocessing as mp
        per = [a.runs // a.jobs + (1 if i < a.runs % a.jobs else 0) for i in range(a.jobs)]
        tasks = [(a.scenario, n, a.seed + 1000 * i, a.airports, a.runways, a.particles, only, i == 0)
                 for i, n in enumerate(per) if n > 0]
        with mp.get_context("fork").Pool(len(tasks)) as pool:
            parts = pool.map(_worker, tasks)
        res, meta, max_min = parts[0]
        for r2, m2, mm in parts[1:]:
            for k in res:
                res[k]["err"] += r2[k]["err"]
                res[k]["r95"] += r2[k]["r95"]
            for k in ("air_min", "path_km", "cr_total"):
                meta[k] += m2[k]
            for k in meta["snaps"]:
                meta["snaps"][k] += m2["snaps"][k]
            max_min = max(max_min, mm)
        summ = run_experiment(sc, a.runs, a.seed, airports, a.particles, only, verbose=False,
                              raw=(res, meta, max_min))
    else:
        summ = run_experiment(sc, a.runs, a.seed, airports, a.particles, only)
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, f"summary_{a.scenario}.json")
    with open(path, "w") as f:
        json.dump(summ, f, indent=1)
    print("wrote", path)
    keys = ("gc_km", "typical_track_km", "track_vs_gc_lateral_km", "extra_km_vs_gc", "extra_km_vs_track",
            "airborne_min", "path_km", "cosmic_hits_per_flight", "mag", "cutoff_gv", "snaps")
    print(json.dumps({k: summ[k] for k in keys}, indent=1))
    for name, ov in summ["overall"].items():
        print(f"{name:30s} " + "  ".join(f"{k}={v:.1f}" if abs(v) >= 1 else f"{k}={v:.2f}" for k, v in ov.items()))


if __name__ == "__main__":
    main()
