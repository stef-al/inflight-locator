Step 3 of 7. Add offline data, a map, and a Demo screen that flies simulated W6 4747 flights through the engine.

Do not change any file in `inflight/engine/`.

## 1. Offline data

Create a `DataRepository` (app code) that downloads two files into `filesDir/data/` and loads them:

- `https://raw.githubusercontent.com/stef-al/inflight-locator/main/data/airports.csv` (about 230 KB), parsed with `Airports.parseCsv(text)` into `List<Airport>`.
- `https://raw.githubusercontent.com/stef-al/inflight-locator/main/data/basemap/borders-50m.txt` (about 410 KB): coastlines and land borders. Lines starting with `#` are comments. Every other line is `C ` (coastline) or `B ` (land border) followed by points `lat,lon` separated by `;`. Parse each line into a FloatArray of alternating lat, lon.

Download with HttpURLConnection on Dispatchers.IO (add the INTERNET permission), write to a temporary file and rename when complete, and keep the files for later runs. Download automatically on first launch when online. Add a "Data" card at the top of Diagnostics showing each file's status (missing / downloading / size and date) and a "Download again" button. Everything else must work without these files: no airports means no labels and no landing match, no basemap means a plain background.

## 2. Map composable

Create a reusable `FlightMap` composable drawn with Compose Canvas (no map SDK, no tiles, no network). It takes a state object with:

- `referenceTrack: List<LatLon>?` (blue, 3 dp)
- `truthPath: List<LatLon>?` (demo only: grey, 2 dp)
- `cloud: List<LatLon>` (candidate positions: 2 dp dots in the primary colour at 35% alpha)
- `estimate: LatLon?` and `r95Km: Double` (a filled 10 dp dot with a 2 dp outline in the surface colour, and a circle of radius r95Km: 2 dp stroke plus 15% alpha fill, both in the primary colour)
- `truth: LatLon?` (demo only: a small dark diamond)
- `fixes: List<LatLon>` (GPS fixes: small green dots)
- airports and basemap from the DataRepository

Projection: equirectangular around a centre (lat0, lon0): x = (lon - lon0) * cos(lat0), y = -(lat - lat0), scaled to pixels. Build the basemap as two `Path`s (coastlines, borders) once in projected degree units and draw them with a canvas transform for zoom and pan, so panning does not rebuild paths. Coastlines in the outline colour, borders lighter. Draw order: basemap, reference track, truth path, cloud, circle, airports, fixes, truth, estimate.

Airports: draw every airport inside the view as a 3 dp dot in the on-surface colour at 60% alpha. Label airports that have an IATA code with that code in labelSmall, skipping a label when it would overlap one already drawn; draw at most 40 labels.

The circle radius in pixels: convert r95Km to degrees of latitude (divide by 111.2) and then to pixels with the current scale.

Gestures: pinch to zoom and drag to pan (`detectTransformGestures`), double tap to zoom in. A small "Fit" icon button in a corner fits the reference track (or the truth path, or the estimate with its circle) with 10% padding. Show "Not for navigation" in labelSmall at the bottom left of the map.

## 3. Demo screen

Add "Demo" to the bottom navigation, first in order. The screen has the map on top (about 60% of the height) and a panel below.

Panel controls:

- Mode: a dropdown of `DemoMode.values()` showing `mode.label`.
- Switches: "GPS fix at minute 40" and "Magnetometer".
- Speed: segmented buttons 30×, 120×, 600× (default 120×).
- Buttons: Play / Pause, and "New flight" (a different simulated flight: seed + 1).

Engine use: on Play, if there is no runner, create `DemoRunner(mode, seed, gpsFixMinute = if (fixSwitch) 40.0 else null, useMagnetometer = magSwitch, particles = 1500, airports = airports)` on a background thread (it takes about a second). Then run a loop on that thread: every 100 ms of real time advance simulated time by speed × 0.1 seconds, starting from -60 s, call `runner.advanceTo(simSeconds)`, and publish a UI state built from `runner.estimate()` (a `SessionEstimate`), `runner.truth()`, `runner.cloud(200)`, `runner.errorKm()`, and every 5 simulated minutes `runner.pathSoFar()`. Reference track: `runner.session.referenceTrack` (a `Track` with `lat` and `lon` arrays; null in unknown-route mode). Stop at `runner.durationSeconds`. Changing mode, switches or "New flight" discards the runner.

Readouts in the panel, one per line, in this order:

- Time since liftoff as h:mm:ss, or "Before takeoff" when negative.
- `estimate.mode.label`, then the phase: 0 Climb, 1 Cruise, 2 Descent, from `estimate.phase`.
- Position in words: `Airports.describe(airports, estimate.lat, estimate.lon)`, then "± N km" from `estimate.r95Km`.
- "Error vs simulated truth: N km", and "inside the circle" or "outside the circle".
- Speed (km/h) and, when not null, "N km to go" from `estimate.remainingKm`.
- After landing (`estimate.state == SessionState.LANDED`): "Landed at PRG (Václav Havel Airport Prague)" from `estimate.landedAt`.

## Check

In the emulator: Demo > Play at 600×. The dot leaves Skopje, follows the grey truth line north-west via Belgrade and Vienna, and lands at PRG; the error readout stays mostly between 5 and 30 km and the truth stays inside the circle most of the time. With mode "Unknown route (compass)" the circle grows steadily; with "GPS fix at minute 40" on, the dot jumps onto the truth line at 0:40 and the circle shrinks.
