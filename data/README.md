# Data

The app downloads these files once, before the flight, from this repository's raw URLs (for example `https://raw.githubusercontent.com/stef-al/inflight-locator/main/data/airports.csv`) and keeps them on the phone.

| File | Contents | Source and licence |
| --- | --- | --- |
| `airports.csv` | 3,270 large and medium airports with scheduled service: `ident,iata,lat,lon,elev_m,runway_headings_true,name`; runway headings are true degrees separated by semicolons | [OurAirports](https://ourairports.com/data/) via [ourairports-data](https://github.com/davidmegginson/ourairports-data), downloaded 16 Nov 2026; public domain |
| `basemap/borders-50m.txt` | coastlines (`C`) and land borders (`B`), one polyline per line as `lat,lon;lat,lon;...`, simplified to about 3 km | [Natural Earth](https://www.naturalearthdata.com/) 1:50m countries via [world-atlas](https://github.com/topojson/world-atlas) 2.0.2; public domain |
| `flights/W64747.plan` | the worked example: Wizz Air W6 4747 Skopje to Prague with its published airway route, in `FlightPlan.encode()` format | route from [Flight Plan Database plan 149187](https://flightplandatabase.com/plan/149187) |
| `wmm/WMM2025.COF` | World Magnetic Model 2025 coefficients, also compiled into `Wmm2025Coefficients.kt` | NOAA NCEI and British Geological Survey, [WMM2025](https://doi.org/10.25921/aqfd-sd83); NOAA states the WMM software is public domain and may be used freely |

`airports.csv` and `basemap/borders-50m.txt` were generated with the scripts in [`tools/`](../tools).
