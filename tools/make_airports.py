"""Build the compact airports file from the OurAirports CSVs (paths given as arguments)."""
import csv, sys, math

air, rwy, out = sys.argv[1], sys.argv[2], sys.argv[3]

def bearing(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0

aps = {}
with open(air, newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if r.get("type") in ("large_airport", "medium_airport") and r.get("scheduled_service") == "yes":
            try:
                elev_ft = float(r["elevation_ft"]) if r.get("elevation_ft") else 0.0
                aps[r["ident"]] = dict(ident=r["ident"], iata=r.get("iata_code", "") or "", name=r.get("name", ""),
                                       lat=float(r["latitude_deg"]), lon=float(r["longitude_deg"]),
                                       elev=round(elev_ft * 0.3048), hdg=[])
            except ValueError:
                pass
with open(rwy, newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        a = aps.get(r.get("airport_ident"))
        if a is None or r.get("closed") == "1":
            continue
        got = False
        for key in ("le_heading_degT", "he_heading_degT"):
            try:
                a["hdg"].append(float(r[key])); got = True
            except (ValueError, KeyError, TypeError):
                pass
        if not got:
            try:
                b = bearing(float(r["le_latitude_deg"]), float(r["le_longitude_deg"]),
                            float(r["he_latitude_deg"]), float(r["he_longitude_deg"]))
                a["hdg"] += [b, (b + 180.0) % 360.0]
            except (ValueError, KeyError, TypeError):
                pass
rows = sorted(aps.values(), key=lambda a: a["ident"])
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write("ident,iata,lat,lon,elev_m,runway_headings_true,name\n")
    for a in rows:
        hd = ";".join(f"{h:.1f}".rstrip("0").rstrip(".") for h in a["hdg"])
        name = a["name"].replace("\n", " ").replace("\r", " ").strip()
        f.write(f'{a["ident"]},{a["iata"]},{a["lat"]:.5f},{a["lon"]:.5f},{a["elev"]},{hd},{name}\n')
print(len(rows), "airports;", sum(1 for a in rows if a["hdg"]), "with runway headings")
