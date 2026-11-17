Step 4 of 7. Add sensor processing to the engine and a sensor check to Diagnostics.

## 1. Engine files

Create the {{COUNT}} files below next to the other engine files (folder `inflight/engine/`, `package inflight.engine`). Copy each one exactly; do not change the earlier engine files.

{{FILE_LIST}}

What they do, so you can wire them up in the next step:

- `PhaseDetector(hasBarometer, elevChangeM)` takes raw barometer samples (`onPressure(timeMillis, hPa)`) and raw accelerometer samples (`onAccel(timeMillis, x, y, z)`, gravity included) and returns `PhaseEvent`s: `TakeoffRoll`, `Takeoff`, `TopOfClimb`, `TopOfDescent`, `Landing`, `PhoneMoved`. Its `handling` flag is true while the phone is being moved, and `pressureTrend` is the cabin-pressure trend in hPa per minute.
- `HeadingTracker` turns the phone's rotation matrix into the aircraft's true heading after an alignment (from the takeoff roll, or manual).
- `MagMath.fieldAndDip()` and `MagCalibration` turn magnetometer readings into gate-calibrated field strength and dip angle.
- `SensorSim` produces synthetic sensor streams for the self-test.

## 2. Diagnostics

Add a third card, "Sensor processing", whose "Run checks" calls `SelfTestSensors.run()` on a background dispatcher (2 checks). Include it in "Run all".

Add a fourth card, "Sensors on this phone", that lists, with a check or a cross: barometer (`Sensor.TYPE_PRESSURE`), accelerometer, gyroscope, rotation vector, uncalibrated magnetometer (`Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED`), and GPS (`LocationManager.GPS_PROVIDER` present). While the card is visible, show live values: pressure in hPa with two decimals, the cabin-pressure trend from a `PhaseDetector(hasBarometer = true)` fed with the pressure and accelerometer samples (hPa/min, one decimal, "-" until it has a minute of data), the accelerometer magnitude in m/s², and "Phone moving" when the detector's `handling` is true. Register the listeners in onResume-equivalent lifecycle and unregister when the card leaves the screen. Convert `SensorEvent.timestamp` to wall-clock milliseconds with `System.currentTimeMillis() + (event.timestamp - SystemClock.elapsedRealtimeNanos()) / 1_000_000`.

## Check

In the emulator, Run all: 34 + 5 + 2 = 41 checks pass. "Sensors on this phone" shows crosses for what the emulator lacks without crashing. On a real phone with a barometer, the pressure updates every second and the trend settles near 0.0 hPa/min on a desk.

{{ENGINE_FILES}}
