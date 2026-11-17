Step 5 of 7. Add flights: plan a flight before boarding, with a reference track and a wind forecast.

Do not change any file in `inflight/engine/`.

## 1. Storage

Each flight is a folder `filesDir/flights/<id>/` (id: a timestamp string) with:

- `plan.txt`: `FlightPlan.encode()` output; read back with `FlightPlan.decode(text)`.
- `log.txt`, `estimates.csv`, `calibration.txt`: written in later steps.

A `FlightStore` lists folders newest first and derives a status from `log.txt`: no log or no line starting `TKO` = Planned; `TKO` but no `LND` = In flight; `LND` = Landed.

## 2. Flights screen

Add "Flights" to the bottom navigation (after Demo). A list of flights: label, "SKP → PRG" (IATA codes, or ICAO when there is no IATA; "SKP → ?" when the destination is unknown), status, and the date. A floating "New flight" button opens the editor. When the list is empty, show a button "Add example: W6 4747 Skopje–Prague", which saves `FlightPlan.w64747(year = Wmm2025.decimalYear(System.currentTimeMillis()))`.

## 3. Flight editor

Fields:

- Label (for example the flight number).
- From: airport code (IATA or ICAO), resolved with `Airports.byCode(airports, code)`; show the airport name under the field, or "Unknown airport".
- To: airport code, optional. Leave empty when the destination is unknown; then the app estimates by compass dead reckoning and says so.
- Aircraft: a dropdown "Narrow-body (A320 / B737 family)" = cruise speed 840 km/h, cruise altitude 11.0 km; "Wide-body long-haul" = 900 km/h, 11.5 km. Stored in `Perf(tas = ..., cruiseAlt = ...)`.
- Planned departure: date and time picker (default: now + 2 hours), stored in the app's own `meta.txt` in the flight folder (key `departure_millis`).

Reference track, in a card with the current state: "Great circle only", "Route with N points", or the W6 4747 example route.

- "Import track file": the Storage Access Framework (`ActivityResultContracts.OpenDocument`, any MIME type). Read the text and call `TrackImport.parse(text)`, then `TrackImport.toRoute(points, LatLon(origin.lat, origin.lon), LatLon(dest.lat, dest.lon))`. Show how many points were read and kept. Typical files: a KML or CSV of an earlier flight on the same route from a flight tracker, or a GPX route.
- "Paste coordinates": a dialog with a text field; the same two calls on the pasted text.
- "Use great circle": clears the route.

Show a `FlightMap` preview with `plan.referenceTrack()` (converted from the `Track` arrays) and the great circle `plan.scenario().greatCircle()` in a second, thinner colour.

Wind forecast card (needs internet):

- Button "Get wind forecast". Compute the hour to ask for: departure + half of the expected flight time, where flight time = track length / cruise speed + 0.5 h; round to the hour; format as `yyyy-MM-dd'T'HH:00` in UTC.
- `val track = plan.referenceTrack()!!`, `val points = WindForecast.samplePoints(track)`, `val level = WindForecast.pressureLevelFor(plan.perf.cruiseAlt)`, `val url = WindForecast.openMeteoUrl(points, hour, level)`.
- GET the URL on Dispatchers.IO. The body is a JSON array with one object per point (a single object if there is only one point). For each object read `hourly.wind_speed_<level>hPa[0]` (km/h) and `hourly.wind_direction_<level>hPa[0]` (degrees the wind blows from) with org.json.
- `val tail = WindForecast.alongTrackKmh(track, points, speeds, directions)` and save the plan with `windKmh = tail`.
- Show "Tailwind 35 km/h at 250 hPa, forecast for 12:00 UTC" (or "Headwind" when negative), a "Remove" button, and the line "Weather data by Open-Meteo.com" linking to https://open-meteo.com/.
- On any error, show the message and keep the plan unchanged. Hide the card when the destination is unknown.

When editing an existing flight, start from the decoded plan and change it with `copy()`, so fields the editor does not show (such as the example's runway weights) are kept.

Ready-for-offline checklist at the bottom of the editor: airport data downloaded; plan saved; reference track (or "great circle only"); wind forecast (optional). A "Save" button writes `plan.txt` and `meta.txt`.

## Check

In the emulator: Flights > "Add example: W6 4747". Open it: the map shows the route bending east of the great circle via Belgrade and Vienna. Tap "Get wind forecast": a tailwind or headwind value appears. Create a new flight from VIE to LHR with no track: it shows "Great circle only".
