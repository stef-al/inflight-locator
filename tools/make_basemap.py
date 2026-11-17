"""Usage: python3 -I make_basemap.py <countries topojson> <out file> <scale label> <simplify tolerance in degrees>
Decodes the TopoJSON as plain data (no library). Arcs used by one country are coastlines (C),
arcs shared by two countries are land borders (B). Output: one polyline per line, "C lat,lon;lat,lon;..."."""
import json, sys

src, out, label, tol = sys.argv[1], sys.argv[2], sys.argv[3], float(sys.argv[4])

def simplify(pts, eps):
    """Douglas-Peucker on (lat, lon) treated as planar (longitude scaled by cos(lat))."""
    import math
    if len(pts) < 3 or eps <= 0: return pts
    keep = [False] * len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        la, lo = pts[a]; lb, lob = pts[b]
        k = math.cos(math.radians((la + lb) / 2))
        ax, ay, bx, by = lo * k, la, lob * k, lb
        dx, dy = bx - ax, by - ay
        L = math.hypot(dx, dy)
        best, bi = -1.0, -1
        for i in range(a + 1, b):
            px, py = pts[i][1] * k, pts[i][0]
            d = math.hypot(px - ax, py - ay) if L == 0 else abs(dy * px - dx * py + bx * ay - by * ax) / L
            if d > best: best, bi = d, i
        if best > eps:
            keep[bi] = True; stack += [(a, bi), (bi, b)]
    return [p for p, kp in zip(pts, keep) if kp]
topo = json.load(open(src, encoding="utf-8"))
sc = topo["transform"]["scale"]; tr = topo["transform"]["translate"]

def decode(arc):
    x = y = 0; pts = []
    for dx, dy in arc:
        x += dx; y += dy
        pts.append((y * sc[1] + tr[1], x * sc[0] + tr[0]))  # lat, lon
    return pts

arcs = [decode(a) for a in topo["arcs"]]
use = {}
def walk(obj, gid):
    if isinstance(obj, int):
        i = obj if obj >= 0 else ~obj
        use.setdefault(i, set()).add(gid)
    else:
        for o in obj: walk(o, gid)
for gid, g in enumerate(topo["objects"]["countries"]["geometries"]):
    if "arcs" in g: walk(g["arcs"], gid)

def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s

lines = [f"# Natural Earth {label} (public domain) via world-atlas 2.0.2, simplified to {tol} deg. C = coastline, B = land border; lat,lon pairs."]
npts = 0
for i, a in enumerate(arcs):
    if i not in use: continue
    tag = "B" if len(use[i]) > 1 else "C"
    seq = []
    for lat, lon in simplify(a, tol):
        s = fmt(lat) + "," + fmt(lon)
        if not seq or seq[-1] != s: seq.append(s)
    if len(seq) >= 2:
        lines.append(tag + " " + ";".join(seq)); npts += len(seq)
open(out, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
print(out, len(lines) - 1, "polylines", npts, "points")
