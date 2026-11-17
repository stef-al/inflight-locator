You are building "Inflight Locator", a native Android app in Kotlin with Jetpack Compose and Material 3.

What it does: estimates where an airliner is during a flight from the phone's own sensors and a few files downloaded before boarding, and shows the estimate as a dot inside an error circle that holds the true position about 95% of the time. It is not for navigation.

Rules for every change in this project:

1. The package `inflight.engine` is a verified reference engine. When a prompt gives engine files, create them exactly as given: same file names, same package line, every line unchanged. Never edit, reformat, shorten, split, merge, rename or "improve" an engine file, and never re-implement engine logic in app code. If a build error points into an engine file, the cause is app code or an inexact copy: recopy that file from the prompt that gave it.
2. Put the engine files in the same source root as MainActivity (app/src/main/java or app/src/main/kotlin), in the folder inflight/engine/, keeping `package inflight.engine` even though the app's own package differs.
3. Keep app code in the app's own package, split by role (ui, data, tracking).
4. Run engine work off the main thread. Everything that touches one flight's LiveSession, PhaseDetector or HeadingTracker runs on one single background thread (these classes are not thread-safe). Self-test and demo work may use any background dispatcher.
5. Engine units: km, km/h, degrees, microtesla, hPa, Unix milliseconds. Show distances in km.
6. minSdk 26. No Google Play services, no Firebase, no analytics, no ads, no sign-in. Location comes from android.location.LocationManager with GPS_PROVIDER.
7. Use the network only before a flight (data download, wind forecast). Nothing leaves the phone unless the user shares a file.
8. Every screen must work in the browser emulator, which has no camera and no real GPS and may lack other sensors such as the barometer: a missing sensor shows "Not available on this device" instead of failing.
9. Show "Not for navigation" on first launch and on every map screen.
10. When a self-test check fails, fix app code. Change an engine file only to restore an exact copy.
11. After each prompt, list the files you created or changed.
