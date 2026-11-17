Step 7 of 7. After the flight: a summary, sharing the flight files, and the accuracy check against the real track.

Do not change any file in `inflight/engine/`.

## 1. Flight summary

Opening a Landed flight in Flights shows a summary screen:

- "Landed at PRG (Václav Havel Airport Prague)", or "Landed near ..." from `Airports.describe` when no airport was matched. Rebuild the session with `LiveSession.fromLog(plan, logText, airports)` and read `landedAt`, `takeoffMillis` and `landingMillis`.
- Takeoff and landing times (local time), flight time, and the times of top of climb and top of descent (from the `TOC` and `TOD` log lines, their `eventMillis`), with how each was found (`source` of the takeoff and landing lines: barometer, accelerometer, manual).
- Number of GPS fixes and of heading and magnetic readings in the log.
- A `FlightMap` with the reference track, the logged estimates from `estimates.csv` as a line, their 95% circles every 10 minutes (thin outlines), and the GPS fixes.

## 2. Share

A "Share flight files" button zips `plan.txt`, `log.txt`, `estimates.csv`, `calibration.txt` and `meta.txt` (those that exist) into `<label>-<date>.zip` in the cache directory and shares it through a FileProvider with ACTION_SEND. These files are what a later version of the engine needs to replay the flight.

## 3. Accuracy check

A card "Check accuracy against the real track":

- "Import real track": the Storage Access Framework, any file type. Parse with `TimedTrackImport.parse(text)` (KML with timestamps, GPX with times, or a flight-tracker CSV with Unix or ISO times). Show the number of points and their time span. Save the parsed points as `truth.csv` (`tMillis,lat,lon` per line) in the flight folder.
- When `truth.csv` exists (read it back with `TimedTrackImport.parse` too), compute three results on a background thread:
  - "As flown": `Evaluation.compare(Evaluation.parseEstimates(estimatesCsv), truth)`.
  - "Replayed": `Evaluation.compare(LiveSession.replayEstimates(plan, logText, airports), truth)`.
  - "Replayed without GPS fixes": `Evaluation.compare(LiveSession.replayEstimates(plan, logText, airports, dropFixes = true), truth)`.
- For each, show the number of minutes compared, the median, 90th-percentile and largest error in km, and the share of minutes with the truth inside the 95% circle, as a small table.
- Below it, a line chart (Compose Canvas) of error in km over flight time for the three results, with a legend; axis labels in minutes after takeoff and km.
- One line of guidance under the table: the spec's release targets are a known-route median under 40 km without a wind forecast (25 km with one), an unknown-route error at landing under 10% of the distance flown, and at least 90% of minutes inside the circle.

## 4. Save a demo flight

On the Demo screen, add "Save as flight" (enabled after a demo flight has landed). It creates a flight folder with `plan.txt` (`runner.plan.encode()`), `log.txt` (`runner.session.encodeLog()`), `estimates.csv` (built by `LiveSession.replayEstimates(runner.plan, logText)`, one line `tMillis,lat,lon,r95Km` per estimate), and `truth.csv` with one point per minute from `runner.truthAt(seconds)` at time `runner.takeoffMillis + seconds * 1000`. The flight appears in Flights as Landed with the label "Demo: <mode>", so the summary and the accuracy check can be tried in the emulator.

## 5. Flight list actions

Long-press a flight for "Rename" and "Delete" (with confirmation; deleting a flight that is being tracked is not allowed).

## Check

In the emulator: Demo > "Unknown route (compass)" with "GPS fix at minute 40" > Play at 600× until it lands > "Save as flight". Flights now lists "Demo: Unknown route (compass)" as Landed. Its summary shows the takeoff and landing times and a map. The accuracy check shows three rows; "Replayed without GPS fixes" has a clearly larger median error than "Replayed".
