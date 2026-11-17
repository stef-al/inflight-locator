Step 6 of 7. Track a real flight: a foreground service that feeds the phone's sensors and GPS into the engine, and a Live screen.

Do not change any file in `inflight/engine/`.

## 1. Manifest

Permissions: ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS, WAKE_LOCK, INTERNET (already there). Declare `FlightTrackingService` with `android:foregroundServiceType="location"` and `android:exported="false"`.

## 2. Starting

On a Planned flight in the Flights screen, add a "Start tracking" button. It asks for fine location and, on Android 13+, notification permission. Location is required (for the foreground service type and for GPS fixes): if it is denied, explain that and do not start. Only one flight can be tracked at a time. Start the service with `startForegroundService` and the flight id, then open the Live screen.

## 3. The service

`FlightTrackingService` runs everything on one `HandlerThread` named "engine": sensor listeners, the GPS listener, timers and every engine call are registered with or posted to this thread's Handler. No other thread touches the engine objects.

On start:

- Call `startForeground` within 5 seconds with an ongoing notification on a low-importance channel "Flight tracking"; tapping it opens the Live screen.
- Acquire a PARTIAL_WAKE_LOCK (tag "InflightLocator:tracking"); release it when the service stops.
- Load `plan = FlightPlan.decode(plan.txt)` and the airports from the DataRepository (empty list if missing).
- `hasBarometer` = the phone has `Sensor.TYPE_PRESSURE`.
- If `log.txt` exists: `session = LiveSession.fromLog(plan, logText, airports)`. If `session.state == SessionState.AIRBORNE`, call `detector.resumeAirborne(session.takeoffMillis!!, phase, SensorSim.isaPressure(plan.origin.elevM))` with phase DESCENT if the log has a line starting `TOD`, CRUISE if it has `TOC` (or if there is no barometer), otherwise CLIMB. If the state is LANDED, stop the service.
- Otherwise: `session = LiveSession(plan, airports, seed = flightId.hashCode().toLong(), particles = 1500, expectCues = hasBarometer)` and write `session.encodeLog()` (just a header line) to `log.txt`.
- `detector = PhaseDetector(hasBarometer, elevChangeM = (plan.dest?.elevM ?: plan.origin.elevM) - plan.origin.elevM)`, `heading = HeadingTracker()`, `wmm = Wmm2025(plan.year)`.

Recording an observation: `fun record(e: FlightEvent) { session.apply(e); append e.encode() + "\n" to log.txt and flush }`. Every observation goes through `record`.

Sensors (register with the engine Handler, batching `maxReportLatencyUs = 2_000_000`). Convert `event.timestamp` to wall-clock milliseconds with `System.currentTimeMillis() + (event.timestamp - SystemClock.elapsedRealtimeNanos()) / 1_000_000`.

- `TYPE_PRESSURE` at 1 Hz → `detector.onPressure(t, values[0])`.
- `TYPE_ACCELEROMETER` at 50 Hz → `detector.onAccel(t, x, y, z)`; also keep a low-pass gravity vector (alpha 0.05) for the magnetometer.
- `TYPE_ROTATION_VECTOR` at 10 Hz → `SensorManager.getRotationMatrixFromVector(r, values)`; keep `latestR = HeadingTracker.toDouble(r)`. If `heading.aligned` and not `detector.handling`, add `heading.trueHeading(latestR, declination)` to this minute's heading samples (as sin and cos sums). `declination` = `wmm.declination(lat, lon)` at the latest estimate, refreshed each minute.
- `TYPE_MAGNETIC_FIELD_UNCALIBRATED` at 10 Hz, if present: see calibration below.

Handle every `PhaseEvent` the detector returns:

- `Takeoff` → `record(FlightEvent.Takeoff(e.detectedMillis, e.eventMillis, e.source))`; likewise `TopOfClimb`, `TopOfDescent` and `Landing` → the matching `FlightEvent` with `(e.detectedMillis, e.eventMillis)` (plus `e.source` for Landing).
- `TakeoffRoll` → `heading.alignFromRoll(e.axis, latestR, wmm.declination(plan.origin.lat, plan.origin.lon), plan.origin.runwayHeadings)`.
- `PhoneMoved` → `heading.invalidate()` and `record(FlightEvent.HeadingLost(e.detectedMillis))`.

Magnetometer gate calibration (before takeoff only; skip if there is no uncalibrated magnetometer). When the phone has been still (not `detector.handling`) for 20 seconds, collect 20 seconds of samples: for each, `m = values[0..2] - bias` where `bias = values[3..5]` frozen from the first sample, and `MagMath.fieldAndDip(mx, my, mz, gx, gy, gz)` with the low-pass gravity. Then `cal = MagCalibration.atGate(meanF, meanDip, plan.origin, plan.year)`; save `biasF`, `biasDip` and the frozen bias to `calibration.txt` (one `key=value` per line) and reload it after a restart. In flight, keep this minute's F and dip samples (same formula with the frozen bias) only while the phone has been still for at least 60 seconds.

GPS: `locationManager.requestLocationUpdates(GPS_PROVIDER, 5000L, 0f, listener, engineLooper)` for the whole session. For each location with accuracy under 100 m and at most one per 60 seconds: `record(FlightEvent.GpsFix(now, lat, lon, if (hasSpeed) speed * 3.6 else null, if (hasBearing) bearing.toDouble() else null, "gps"))`.

Every 60 seconds (Handler.postDelayed loop):

- If there were at least 30 heading samples: `record(FlightEvent.Heading(now, meanHeading, heading.alignmentId))`.
- If calibrated and there were at least 100 magnetic samples: `record(FlightEvent.Magnetic(now, cal.correctF(meanF), cal.correctDip(meanDip)))`.
- Clear the minute's samples.
- When airborne, append `"$now,${e.lat},${e.lon},${e.r95Km},${e.mode},${e.phase}"` from `session.estimate(now)` to `estimates.csv`.
- Update the notification: "Waiting for takeoff", or `Airports.describe(airports, lat, lon)` + " · ±N km", or "Landed at PRG".

Every second, publish a `LiveState` to a singleton `TrackingState` object (`MutableStateFlow<LiveState?>`): the `SessionEstimate` from `session.estimate(now)`, `session.cloud(150)` (refresh every 5 seconds), the reference track, the GPS fixes so far, `hasBarometer`, `detector.pressureTrend`, `heading.aligned` and `heading.source`, calibration state, `detector.handling`, seconds since the last GPS fix, and battery use: percent per hour since the service started (BatteryManager).

Commands (Intent actions handled on the engine thread):

- TOOK_OFF: if not airborne, `record(FlightEvent.Takeoff(now, now, "manual"))`.
- LANDED: if airborne, `record(FlightEvent.Landing(now, now, "manual"))`.
- ALIGN: `heading.alignManual()` (phone lying flat, top edge toward the cockpit).
- STOP: stop the service.

After a landing (detected or manual), keep running 10 more minutes for GPS, append the final estimate, then stop. On stop: unregister everything, release the wake lock, remove the notification, set `TrackingState` to null.

## 4. Live screen

Add "Live" to the bottom navigation. Without a tracked flight it says "No flight in progress. Start one from Flights."

- `FlightMap` with the reference track, the cloud, the estimate with its circle, and the GPS fixes. Auto-fit on first show.
- A card: position in words and "± N km"; a status line built from the inputs in use, for example "Known route: clock, phase cues, compass, magnetometer, wind forecast" (`estimate.mode.label`, then: "clock" always; "phase cues" with a barometer; "compass" when aligned; "magnetometer" when calibrated; "wind forecast" when `plan.windKmh` is set); the phase and time since takeoff; speed; "N km to go"; "Last GPS fix N min ago".
- Status chips: Barometer (trend in hPa/min, or "No barometer"), Heading ("Aligned on runway", "Aligned by hand", "Not aligned"), Magnetometer ("Calibrated", "Calibrating: keep still", "Not available"), GPS ("Searching", "Fix N min ago"), "Phone moving" when handling, and battery %/h.
- Buttons: "We took off" (before takeoff), "Align" (opens a dialog: "Lay the phone flat with its top edge pointing to the front of the aircraft, then tap Align. Leave it there."), "We landed" (in flight), "Stop tracking" (with confirmation).
- "Not for navigation" at the bottom.

## Check

In the emulator: Flights > W6 4747 > Start tracking. The notification says "Waiting for takeoff"; Live shows the dot at SKP, and the Barometer chip shows either a flat trend or "No barometer". Tap "We took off": during the next minutes the dot moves north-west along the route at about 14 km per minute and the circle grows. Swipe the app away and reopen it: Live shows the same flight, restored from its log. Tap "We landed": the estimate snaps to an airport and the service stops 10 minutes later.

On a phone on the ground, "Barometer" shows a trend near 0.0 hPa/min, carrying the phone up or down a few floors moves it, and "Phone moving" appears when you pick the phone up.
