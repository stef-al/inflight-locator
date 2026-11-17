# Building the app with Google AI Studio

These prompts build the Android app in Google AI Studio's Build mode, which generates native Android apps in Kotlin with Jetpack Compose. Paste them one at a time, in order, and run each prompt's check before the next. Three of them carry the engine's source code, so they are long; the others describe the app around it.

| Prompt | What it adds | Check in the emulator |
| --- | --- | --- |
| [00_system_instructions.md](00_system_instructions.md) | rules for the whole project | none |
| [01_engine_core.md](01_engine_core.md) | the app shell, engine part 1, Diagnostics | 34 of 34 checks pass |
| [02_engine_filters.md](02_engine_filters.md) | engine part 2: filters, flight session, simulator | 5 more checks pass |
| [03_demo_and_map.md](03_demo_and_map.md) | offline data download, the map, the Demo screen | a simulated W6 4747 flies from Skopje to Prague |
| [04_engine_sensors.md](04_engine_sensors.md) | engine part 3: phase detection, heading, magnetometer | all 41 checks pass; sensors listed |
| [05_flights_setup.md](05_flights_setup.md) | flights: airports, track import, wind forecast | the W6 4747 example and a forecast tailwind |
| [06_live_tracking.md](06_live_tracking.md) | the tracking service and the Live screen | a manual takeoff moves the dot; a restart restores it |
| [07_after_landing.md](07_after_landing.md) | flight summary, sharing, accuracy check | a saved demo flight compared with its truth |
| [08_optional_cabin_wifi.md](08_optional_cabin_wifi.md) | optional: cabin Wi-Fi moving-map feed | a test feed moves the dot |
| [09_optional_particle_counter.md](09_optional_particle_counter.md) | optional and experimental: camera particle counter | phone only |

## Steps

1. Use a computer with Chrome or Edge (needed later to install on a phone over USB). Open [aistudio.google.com](https://aistudio.google.com), go to **Build**, and choose **Android** as the platform.
2. If the Build settings offer a system instructions field, paste [00_system_instructions.md](00_system_instructions.md) there. Prompt 01 repeats its key rules, so this step is optional.
3. Open [01_engine_core.md](01_engine_core.md) on GitHub, use **Copy raw file**, paste it as the first prompt and send. Wait until the build finishes and the emulator shows the app.
4. Run the prompt's check. Then paste the next prompt into the same project.

## When something fails

- **A self-test check fails** or **an engine file does not compile**: the copy of an engine file is probably not exact (the model shortened or "fixed" it). Reply: "The engine file inflight/engine/NAME.kt is not an exact copy. Replace it with the exact content from the earlier prompt, change nothing else, and run the checks again." Then paste that prompt's file block again if needed.
- **The answer stops halfway through a long file**: reply "Continue exactly where you stopped in NAME.kt."
- **App code does not compile**: paste the error and ask for a fix in app code only.
- The engine in these prompts is the code in [`engine/`](../engine), checked on a laptop with `engine/run.sh selftest` and the Monte Carlo comparisons. After changing the engine, run `python3 tools/build_prompts.py` to rebuild prompts 01, 02 and 04.

## Emulator and phone

The browser emulator has no camera and no real GPS and may lack a barometer, so live tracking can only be tried with the manual "We took off" and "We landed" buttons there; the Demo and Diagnostics screens work fully. To try a real flight, install the app on an Android phone with **Install on Device** (Chrome or Edge, USB debugging on), or publish it to Play internal testing from the project's publish settings. AI Studio exports Android projects as a ZIP (GitHub export is not available for Android projects yet); the ZIP opens in Android Studio.
