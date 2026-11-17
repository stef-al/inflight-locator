Step 1 of 7. Create the app and add the first part of its estimation engine.

Create a new Android app named "Inflight Locator": Kotlin, Jetpack Compose, Material 3, one activity, light and dark theme following the system. If the project's system instructions are empty, apply these rules: the package `inflight.engine` is a verified reference engine that must be copied exactly and never edited; app code calls it from a background thread; minSdk 26; no Google Play services.

## 1. Engine files

Create the {{COUNT}} files below in the same source root as MainActivity (app/src/main/java or app/src/main/kotlin), in the folder `inflight/engine/`. Copy each one exactly, character for character, with its `package inflight.engine` line. Do not edit, reformat, shorten or merge them. They use only the Kotlin standard library and java.time.

{{FILE_LIST}}

## 2. Navigation shell

A Scaffold with a bottom NavigationBar. For now it has one destination, "Diagnostics" (icon: a checklist). Later prompts add "Demo", "Flights" and "Live". On first launch show a one-time dialog: "Inflight Locator estimates your position in flight without satellite signals. Estimates can be tens of kilometres off. Not for navigation." with an OK button (remember the answer in SharedPreferences).

## 3. Diagnostics screen

A scrolling screen of cards. The first card is titled "Engine math".

- A button "Run checks" calls `SelfTest.math()` on a background dispatcher. While it runs, show a progress indicator in place of the button.
- The result is a list of `Check` objects (fields: `name`, `passed`, `detail`). Show each as a row: a green check icon or a red cross icon, the name in body text, and the detail underneath in a smaller, muted style.
- Above the list, a summary line: "34 of 34 checks passed" (use the real counts), in red when anything failed.

## Check

Open the app in the emulator, go to Diagnostics, tap "Run checks". All 34 checks pass. If any fail, compare the engine files with this prompt and recopy them exactly.

{{ENGINE_FILES}}
