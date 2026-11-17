# Engine

Pure Kotlin (package `inflight.engine`, standard library and `java.time` only, no Android imports). The app copies the files in `src/main/kotlin/inflight/engine/` unchanged and calls them; everything that estimates position lives here so it can be tested on a laptop. How it works: [docs/ALGORITHM.md](../docs/ALGORITHM.md).

## Files

| File | Contents |
| --- | --- |
| `Rng.kt` | seeded random numbers (SplitMix64), so runs and replays repeat exactly |
| `Geo.kt` | great-circle maths, `Track` (reference track with along-track and cross-track positions) |
| `Wmm.kt`, `Wmm2025Coefficients.kt` | World Magnetic Model 2025: field, dip, declination |
| `Model.kt` | `Perf` design values, `Scenario`, the W6 4747 and long-haul scenarios, cosmic-ray rate |
| `Airports.kt` | airport list parsing, lookup, `describe()`, `LandingSnap` |
| `Tools.kt` | track import (KML, GPX, CSV), timed track import, `Evaluation`, `WindForecast` (Open-Meteo) |
| `FlightSim.kt` | flight simulator: true trajectory and phone observations |
| `Filters.kt` | `KnownRouteFilter`, `UnknownRouteFilter` |
| `Session.kt` | `FlightPlan`, `FlightEvent` (the log format), `LiveSession` |
| `Demo.kt` | `DemoRunner`: a simulated flight played through `LiveSession` |
| `Phase.kt` | `PhaseDetector`, `HeadingTracker`, `MagMath`, `MagCalibration` |
| `SensorSim.kt` | synthetic barometer and accelerometer streams |
| `SelfTest.kt`, `SelfTestSim.kt`, `SelfTestSensors.kt` | the checks behind the app's Diagnostics screen |

The AI Studio prompts add the files in three groups, each of which compiles on its own: prompt 01 carries `Rng`, `Geo`, `Wmm2025Coefficients`, `Wmm`, `Model`, `Airports`, `Tools` and `SelfTest`; prompt 02 adds `FlightSim`, `Filters`, `Session`, `Demo` and `SelfTestSim`; prompt 04 adds `Phase`, `SensorSim` and `SelfTestSensors`.

## Using it from the app

Units: km, km/h, degrees (true north unless named magnetic), microtesla, hPa, Unix milliseconds. Call the engine from one background thread (for example a single-threaded coroutine dispatcher); none of the classes are thread-safe.

```kotlin
// Before the flight (online)
val airports = Airports.parseCsv(File(filesDir, "airports.csv").readText())
val origin = Airports.byCode(airports, "SKP")!!
val dest = Airports.byCode(airports, "PRG")
val route = TrackImport.toRoute(TrackImport.parse(importedText), LatLon(origin.lat, origin.lon), LatLon(dest!!.lat, dest.lon))
val plan = FlightPlan(
    label = "W6 4747", origin = origin, dest = dest, route = route,
    windKmh = null,                                   // or WindForecast.alongTrackKmh(...) from Open-Meteo
    year = Wmm2025.decimalYear(System.currentTimeMillis()),
)                                                     // FlightPlan.w64747() is the built-in example
File(flightDir, "plan.txt").writeText(plan.encode())

// When the flight starts (at the gate)
val session = LiveSession(plan, airports, seed = flightId, expectCues = hasBarometer)
val detector = PhaseDetector(hasBarometer, elevChangeM = (dest?.elevM ?: origin.elevM) - origin.elevM)
val heading = HeadingTracker()

fun record(e: FlightEvent) {
    session.apply(e)
    File(flightDir, "log.txt").appendText(e.encode() + "\n")
}

fun onPhaseEvent(e: PhaseEvent) = when (e) {
    is PhaseEvent.Takeoff -> record(FlightEvent.Takeoff(e.detectedMillis, e.eventMillis, e.source))
    is PhaseEvent.TopOfClimb -> record(FlightEvent.TopOfClimb(e.detectedMillis, e.eventMillis))
    is PhaseEvent.TopOfDescent -> record(FlightEvent.TopOfDescent(e.detectedMillis, e.eventMillis))
    is PhaseEvent.Landing -> record(FlightEvent.Landing(e.detectedMillis, e.eventMillis, e.source))
    is PhaseEvent.TakeoffRoll -> {
        val decl = Wmm2025(plan.year).declination(origin.lat, origin.lon)
        heading.alignFromRoll(e.axis, latestRotationMatrix, decl, origin.runwayHeadings)
        Unit
    }
    is PhaseEvent.PhoneMoved -> {
        heading.invalidate()
        record(FlightEvent.HeadingLost(e.detectedMillis))
    }
}

// Sensor callbacks (raw values, as delivered)
detector.onPressure(timeMillis, hPa).forEach(::onPhaseEvent)
detector.onAccel(timeMillis, ax, ay, az).forEach(::onPhaseEvent)

// Once a minute, while the phone is still: heading (if aligned) and magnetic reading (gate-calibrated)
heading.trueHeading(rotationMatrix, declinationAtEstimate)?.let { record(FlightEvent.Heading(now, it, heading.alignmentId)) }
record(FlightEvent.Magnetic(now, calibration.correctF(fMean), calibration.correctDip(dipMean)))

// GPS (any fix with accuracy under 100 m)
record(FlightEvent.GpsFix(now, lat, lon, speedKmh, courseDeg, "gps"))

// For the screen, as often as needed
val est: SessionEstimate = session.estimate(System.currentTimeMillis())

// After a restart mid-flight
val restored = LiveSession.fromLog(FlightPlan.decode(planText), logText, airports)
```

The demo needs no sensors: `DemoRunner(DemoMode.KNOWN_ROUTE, seed = 1)`, then `advanceTo(seconds)` and read `estimate()`, `truth()`, `pathSoFar()` and `cloud()`.

## Testing on a laptop

Needs a JDK 17 or newer and either `kotlinc` on the path or a Gradle distribution (its bundled Kotlin compiler is used; set `GRADLE_LIB` if it is not in `/opt/gradle/lib`).

```bash
engine/build.sh                                   # compile main and test sources into engine/build
engine/run.sh selftest                            # the 41 checks the app's Diagnostics screen runs
engine/run.sh mc --runs 400 --airports data/airports.csv --check     # Monte Carlo, compared with the Python prototype
engine/run.sh pipeline --runs 200                 # synthetic sensors -> PhaseDetector -> LiveSession
engine/run.sh pipeline --runs 200 --no-baro --handle
```

Results of these runs are in [results/](results).
