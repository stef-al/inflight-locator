package inflight.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One simulated flight: the true trajectory (5-second steps from liftoff) and what a phone would observe.
 * Used by the demo mode and by the tests. Times are seconds since liftoff.
 */
class SimFlight(
    val t: DoubleArray, val lat: DoubleArray, val lon: DoubleArray, val altKm: DoubleArray,
    val trackBrg: DoubleArray, val speedKmh: DoubleArray,
    val tLand: Double, val pathKm: Double, val depHeading: Double, val arrCourse: Double,
    val tToc: Double?, val tTod: Double,
    /** Observed cue times: the true event time plus timing noise. */
    val tTocObs: Double?, val tTodObs: Double,
    /** Once a minute (60, 120, ...): field strength in microtesla, dip in degrees, true heading in degrees. */
    val tMin: DoubleArray, val magF: DoubleArray, val magDip: DoubleArray, val heading: DoubleArray,
    /** Every ten minutes: particle hits counted in the last ten minutes. */
    val tCosmic: DoubleArray, val cosmic: IntArray,
    val wind0: Double, val tas: Double,
) {
    /** Index of the first sample at or after tSec. */
    fun indexAt(tSec: Double): Int {
        var lo = 0
        var hi = t.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] >= tSec) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Heading sample to use for the 30-second step that ends at tSec. */
    fun headingForStep(tSec: Double, dt: Double = 30.0): Double {
        val tm = tSec - dt / 2
        if (heading.isEmpty() || tm < 45.0) return depHeading
        return heading[(Math.round(tm / 60.0).toInt() - 1).coerceIn(0, heading.size - 1)]
    }
}

object FlightSim {
    /** Flown path for one flight: departure leg, airway route with random shortcuts, base leg and final approach. */
    fun buildPath(sc: Scenario, rng: Rng): Triple<Track, Double, Double> {
        val o = sc.origin
        val d = sc.dest
        val depH = sc.depRunways[rng.choice(DoubleArray(sc.depRunways.size) { sc.depRunways[it].weight })].headingDeg
        val pts = ArrayList<LatLon>()
        pts.add(o)
        pts.add(Geo.destPoint(o.lat, o.lon, depH, sc.depLegKm))
        for ((i, p) in sc.routePts.withIndex()) {
            val interior = i >= sc.keepFirst && i < sc.routePts.size - sc.keepLast
            if (interior && rng.uniform() < sc.pDirect) continue
            pts.add(p)
        }
        val arrC = sc.arrRunways[rng.choice(DoubleArray(sc.arrRunways.size) { sc.arrRunways[it].weight })].headingDeg
        val back = Geo.mod360(arrC + 180.0)
        val ip = Geo.destPoint(d.lat, d.lon, back, sc.fafKm + sc.ipKm)
        val last = pts.last()
        val inbound = Geo.bearingDeg(last.lat, last.lon, d.lat, d.lon)
        if (abs(Geo.angDiff(arrC, inbound)) > 110.0) {
            // must fly past the field: downwind leg abeam the airport, then base to the intercept point
            val a1 = Geo.destPoint(d.lat, d.lon, arrC + 90.0, sc.downwindOffsetKm)
            val a2 = Geo.destPoint(d.lat, d.lon, arrC - 90.0, sc.downwindOffsetKm)
            val ab = if (Geo.havKm(last.lat, last.lon, a1.lat, a1.lon) < Geo.havKm(last.lat, last.lon, a2.lat, a2.lon)) a1 else a2
            pts.add(ab)
            pts.add(Geo.destPoint(ab.lat, ab.lon, back, sc.fafKm + sc.ipKm))
        }
        pts.add(ip)
        pts.add(d)
        return Triple(Track(pts), depH, arrC)
    }

    /**
     * Mean and sd of the extra track distance (departure and arrival manoeuvres, shortcuts)
     * relative to a reference track of length refLenKm.
     */
    fun extraKmPrior(sc: Scenario, refLenKm: Double, rng: Rng, n: Int = 1500): Pair<Double, Double> {
        var sum = 0.0
        var sum2 = 0.0
        for (i in 0 until n) {
            val ex = buildPath(sc, rng).first.length - refLenKm
            sum += ex
            sum2 += ex * ex
        }
        val mean = sum / n
        return Pair(mean, sqrt(maxOf(0.0, sum2 / n - mean * mean)))
    }

    /** Simulates one flight and the phone observations. mag supplies the true field along the way. */
    fun simulate(sc: Scenario, mag: MagModel, rng: Rng): SimFlight {
        val pf = sc.perf
        val (track, depH, arrC) = buildPath(sc, rng)
        val len = track.length
        val wind0 = rng.normal(0.0, pf.windSd)
        val windA = abs(rng.normal(0.0, pf.windVar))
        val windPhi = rng.uniform(0.0, 2 * PI)
        val xwind = rng.normal(0.0, pf.windSd)
        val tas = rng.normal(pf.tas, pf.tasSd)
        val tc = maxOf(12.0, rng.normal(pf.climbMin, pf.climbMinSd)) * 60.0
        val dTod = maxOf(0.5 * pf.todKm, rng.normal(pf.todKm, pf.todKmSd))
        val amps = DoubleArray(pf.latDevSd.size) { rng.normal(0.0, pf.latDevSd[it]) }
        val phis = DoubleArray(amps.size) { rng.uniform(0.0, 2 * PI) }

        val dt = 5.0
        var t = 0.0
        var p = 0.0
        var phase = 0
        var tToc: Double? = null
        var tTod = Double.NaN
        var rem0 = Double.NaN
        var vTod = Double.NaN
        var altTod = Double.NaN
        var alt = sc.oElevKm
        var v = pf.vLo
        val ts = ArrayList<Double>()
        val ps = ArrayList<Double>()
        val alts = ArrayList<Double>()
        val vs = ArrayList<Double>()
        while (p < len) {
            val rem = len - p
            val vcr = tas + wind0 + windA * sin(2 * PI * p / pf.windWavelen + windPhi)
            if (phase == 0 && t >= tc) {
                phase = 1
                tToc = t
            }
            if (phase == 0) {
                val x = t / tc
                v = pf.vLo + (vcr - pf.vLo) * (1.0 - (1.0 - x).pow(pf.climbExp))
                alt = sc.oElevKm + (pf.cruiseAlt - sc.oElevKm) * x.pow(0.8)
            } else if (phase == 1) {
                v = vcr
                alt = pf.cruiseAlt
            }
            if (phase < 2 && rem <= dTod) {
                phase = 2
                tTod = t
                rem0 = rem
                vTod = v
                altTod = alt
            }
            if (phase == 2) {
                val u = 1.0 - rem / rem0
                v = pf.vApp + (vTod - pf.vApp) * (1.0 - u.pow(pf.descExp))
                alt = sc.dElevKm + (altTod - sc.dElevKm) * (1.0 - u)
            }
            ts.add(t)
            ps.add(p)
            alts.add(alt)
            vs.add(v)
            p += v * dt / 3600.0
            t += dt
        }
        val n = ts.size
        val lat = DoubleArray(n)
        val lon = DoubleArray(n)
        val brg = DoubleArray(n)
        for (i in 0 until n) {
            val pp = ps[i]
            val taper = (minOf(pp, len - pp) / pf.devTaperKm).coerceIn(0.0, 1.0)
            var dev = 0.0
            for (k in amps.indices) dev += amps[k] * sin((k + 1) * PI * pp / len + phis[k])
            val q = track.pos(pp, taper * dev)
            lat[i] = q.lat
            lon[i] = q.lon
            brg[i] = q.bearing
        }
        val tArr = DoubleArray(n) { ts[it] }
        val altArr = DoubleArray(n) { alts[it] }
        val tLand = tArr[n - 1] + dt

        // phone observations
        val tTocObs = tToc?.let { it + rng.normal(0.0, pf.cueSdS) }
        val tTodObs = tTod + rng.normal(0.0, pf.cueSdS)
        val nMin = maxOf(0, kotlin.math.ceil(tLand / 60.0).toInt() - 1)
        val tMin = DoubleArray(nMin) { 60.0 * (it + 1) }
        val magF = DoubleArray(nMin)
        val magI = DoubleArray(nMin)
        val hdg = DoubleArray(nMin)
        val q = sqrt(60.0 / 1800.0)
        var bF = rng.normal(0.0, pf.bFSd)
        var bI = rng.normal(0.0, pf.bISd)
        val crab = asin((xwind / tas).coerceIn(-0.5, 0.5)) * R2D
        val hb = rng.normal(0.0, pf.hbSd)
        val out = DoubleArray(2)
        fun idx(tt: Double): Int {
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (tArr[mid] >= tt) hi = mid else lo = mid + 1
            }
            return lo
        }
        for (k in 0 until nMin) {
            val i = idx(tMin[k])
            mag.query(lat[i], lon[i], altArr[i], out)
            bF += rng.normal(0.0, pf.bFRw * q)
            bI += rng.normal(0.0, pf.bIRw * q)
            magF[k] = out[0] + bF + rng.normal(0.0, pf.fNoise)
            magI[k] = out[1] + bI + rng.normal(0.0, pf.iNoise)
            hdg[k] = Geo.mod360(brg[i] + crab + hb + rng.normal(0.0, pf.hdNoise))
        }
        val nCr = maxOf(0, kotlin.math.ceil(tLand / 600.0).toInt() - 1)
        val tCr = DoubleArray(nCr) { 600.0 * (it + 1) }
        val cr = IntArray(nCr)
        for (k in 0 until nCr) {
            val i = idx(tCr[k] - 300.0)
            cr[k] = rng.poisson(Cosmic.ratePerHour(lat[i], lon[i], altArr[i], pf) / 6.0)
        }
        return SimFlight(
            tArr, lat, lon, altArr, brg, DoubleArray(n) { vs[it] },
            tLand, len, depH, arrC, tToc, tTod, tTocObs, tTodObs,
            tMin, magF, magI, hdg, tCr, cr, wind0, tas,
        )
    }
}
