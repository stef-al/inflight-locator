"""Charts for the repository docs. Usage: python3 -I make_charts.py <summary_w64747.json> <repo dir>"""
import json, math, sys
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

summ_path, repo = sys.argv[1], sys.argv[2]
S = json.load(open(summ_path))
SURF, INK, INK2, MUTED, GRID, AXIS = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7"
SLOTS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"]
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 11, "axes.edgecolor": AXIS,
                     "axes.labelcolor": INK2, "xtick.color": MUTED, "ytick.color": MUTED})

# ---------------- accuracy chart ----------------
series = [
    ("baseline_clock_gc", "Clock x cruise speed on the great circle"),
    ("A4_track_cues", "Published track + flight-phase cues"),
    ("A7_track_cues_windfc", "... + wind forecast"),
    ("B1_unknown_compass", "No route: compass dead reckoning"),
    ("B4_unknown_compass_fix", "No route + one GPS fix at minute 40"),
]
fig, ax = plt.subplots(figsize=(10, 6.4), dpi=160)
fig.patch.set_facecolor(SURF); ax.set_facecolor(SURF)
ends = []
for (key, label), col in zip(series, SLOTS):
    rows = S["series"][key]
    xs = [r["minute"] for r in rows if r["minute"] <= 80]
    ys = [r["median"] for r in rows if r["minute"] <= 80]
    if key == "B4_unknown_compass_fix":   # draw the reset as a drop at minute 40
        xs2, ys2 = [], []
        for x, y in zip(xs, ys):
            if x == 40:
                xs2 += [40, 40]; ys2 += [S["series"][key][38]["median"], 0.0]
            else:
                xs2.append(x); ys2.append(y)
        xs, ys = xs2, ys2
    ax.plot(xs, ys, color=col, lw=2, solid_joinstyle="round", solid_capstyle="round", label=label, zorder=3)
    ax.plot([xs[-1]], [ys[-1]], "o", ms=7, color=col, mec=SURF, mew=2, zorder=4)
    ends.append([ys[-1], ys[-1], label, xs[-1]])
# direct labels at line ends, spread apart, with leader lines
ends.sort(key=lambda e: e[0])
gap = 7.0
for i in range(1, len(ends)):
    if ends[i][1] - ends[i - 1][1] < gap:
        ends[i][1] = ends[i - 1][1] + gap
for yv, ylab, label, xe in ends:
    short = {"Clock x cruise speed on the great circle": "clock x speed",
             "Published track + flight-phase cues": "track + cues",
             "... + wind forecast": "+ wind forecast",
             "No route: compass dead reckoning": "compass only",
             "No route + one GPS fix at minute 40": "compass + GPS fix"}[label]
    ax.plot([xe + 0.6, xe + 2.2], [yv, ylab], color=AXIS, lw=1, zorder=2)
    ax.text(xe + 2.6, ylab, f"{short}  {yv:.0f} km", va="center", ha="left", color=INK2, fontsize=10)
ax.axvline(40, color=AXIS, lw=1, zorder=1)
ax.text(40.6, 108, "GPS fix", color=INK2, fontsize=10, va="top")
ax.set_xlim(0, 104); ax.set_ylim(0, 110)
ax.set_xticks(range(0, 81, 10)); ax.set_yticks(range(0, 101, 20))
ax.grid(axis="y", color=GRID, lw=1); ax.set_axisbelow(True)
for s in ("top", "right"): ax.spines[s].set_visible(False)
ax.spines["left"].set_visible(False)
ax.tick_params(length=0)
ax.set_xlabel("Minutes after liftoff"); ax.set_ylabel("Median position error, km")
fig.text(0.06, 0.965, "A track plus a wind forecast, or one GPS fix, keeps the median error near 20 km",
         fontsize=14, color=INK, ha="left", va="top", weight="bold")
fig.text(0.06, 0.915, f"W6 4747 Skopje-Prague, {S['runs']} simulated flights, median error at each minute",
         fontsize=11, color=INK2, ha="left", va="top")
leg = ax.legend(loc="upper center", frameon=False, fontsize=9.5, labelcolor=INK2, ncol=2, bbox_to_anchor=(0.45, -0.12))
fig.subplots_adjust(left=0.08, right=0.97, top=0.86, bottom=0.25)
fig.savefig(f"{repo}/docs/img/accuracy_w64747.png", facecolor=SURF)
plt.close(fig)

# ---------------- route map ----------------
def slerp(a, b, f):
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    A = [math.cos(la1) * math.cos(lo1), math.cos(la1) * math.sin(lo1), math.sin(la1)]
    B = [math.cos(la2) * math.cos(lo2), math.cos(la2) * math.sin(lo2), math.sin(la2)]
    om = math.acos(max(-1, min(1, sum(x * y for x, y in zip(A, B)))))
    if om < 1e-12: return a
    k1, k2 = math.sin((1 - f) * om) / math.sin(om), math.sin(f * om) / math.sin(om)
    v = [k1 * x + k2 * y for x, y in zip(A, B)]
    return (math.degrees(math.asin(v[2])), math.degrees(math.atan2(v[1], v[0])))

def dense(pts, n=20):
    out = [pts[0]]
    for a, b in zip(pts[:-1], pts[1:]):
        out += [slerp(a, b, i / n) for i in range(1, n + 1)]
    return out

plan = dict(l.split("=", 1) for l in open(f"{repo}/data/flights/W64747.plan", encoding="utf-8").read().splitlines() if "=" in l)
o = tuple(map(float, plan["origin"].split("|")[2:4])); d = tuple(map(float, plan["dest"].split("|")[2:4]))
route = [tuple(map(float, p.split(","))) for p in plan["route"].split(";")]
lat0, lat1, lon0, lon1 = 40.3, 51.6, 11.6, 24.6
fig, ax = plt.subplots(figsize=(8, 8.6), dpi=160)
fig.patch.set_facecolor(SURF); ax.set_facecolor(SURF)
for line in open(f"{repo}/data/basemap/borders-50m.txt", encoding="utf-8"):
    if not line[:1] in "CB": continue
    pts = [tuple(map(float, p.split(","))) for p in line[2:].strip().split(";")]
    if not any(lat0 - 2 < la < lat1 + 2 and lon0 - 2 < lo < lon1 + 2 for la, lo in pts): continue
    ax.plot([p[1] for p in pts], [p[0] for p in pts], color=AXIS if line[0] == "B" else MUTED, lw=0.8 if line[0] == "B" else 1.0, zorder=1)
gc = dense([o, d], 60)
ax.plot([p[1] for p in gc], [p[0] for p in gc], color=SLOTS[1], lw=2, zorder=3, label="Great circle, 1,067 km")
tr = dense([o] + route + [d], 10)
ax.plot([p[1] for p in tr], [p[0] for p in tr], color=SLOTS[0], lw=2, zorder=4, label="Published airway route, 1,085 km")
ax.plot([p[1] for p in route], [p[0] for p in route], "o", ms=3.5, color=SLOTS[0], mec=SURF, mew=1.0, zorder=5)
labels = {"SKP", "PRN", "BEG", "INI", "TSR", "BUD", "BTS", "VIE", "BRQ", "PRG", "ZAG", "SJJ", "TGD", "OSI", "LJU", "GRZ", "DEB", "KSC", "SOF", "TIA", "OHD", "SZG", "LNZ", "PED", "OSR"}
for row in open(f"{repo}/data/airports.csv", encoding="utf-8").read().splitlines()[1:]:
    f = row.split(",", 6)
    la, lo = float(f[2]), float(f[3])
    if not (lat0 < la < lat1 and lon0 < lo < lon1): continue
    big = f[1] in labels
    ax.plot([lo], [la], "o", ms=4 if big else 2.5, color=INK if big else MUTED, zorder=6)
    if big:
        ax.text(lo + 0.12, la + 0.08, f[1], fontsize=9, color=INK2, zorder=7)
ax.set_xlim(lon0, lon1); ax.set_ylim(lat0, lat1)
ax.set_aspect(1 / math.cos(math.radians((lat0 + lat1) / 2)))
ax.set_xticks([]); ax.set_yticks([])
for s in ax.spines.values(): s.set_visible(False)
ax.legend(loc="upper right", frameon=True, facecolor=SURF, edgecolor=SURF, framealpha=1.0, fontsize=10, labelcolor=INK2).set_zorder(20)
fig.text(0.05, 0.975, "W6 4747: the airway route bends up to 72 km off the great circle",
         fontsize=12.5, color=INK, ha="left", va="top", weight="bold")
fig.text(0.05, 0.945, "Base map: Natural Earth 1:50m. Airports with scheduled service from OurAirports.",
         fontsize=10, color=INK2, ha="left", va="top")
fig.subplots_adjust(left=0.02, right=0.98, top=0.93, bottom=0.02)
fig.savefig(f"{repo}/docs/img/route_w64747.png", facecolor=SURF)
plt.close(fig)
print("ok")
