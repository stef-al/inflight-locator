# Notes for coding agents

This repository holds the spec, a verified estimation engine and the prompts that build the Android app in Google AI Studio. The Android app itself is generated in AI Studio from `prompts/` and is not stored here yet.

## Layout

- `docs/SPEC.md` is a copy of the product spec. Change only its last section, "Implementation in this repository"; the rest follows the spec document.
- `docs/ALGORITHM.md` describes the engine and quotes its test results. Update the numbers whenever a change moves them.
- `engine/src/main/kotlin/inflight/engine/` is the engine: pure Kotlin, no Android imports, standard library and `java.time` only. `engine/src/test/kotlin/` holds the laptop-only tests (`MonteCarlo`, `Pipeline`, `MagGrid`, `TestMain`).
- `prompts/` holds the AI Studio prompts. `01`, `02` and `04` are generated from `prompts/templates/` by `tools/build_prompts.py` and carry the engine sources; never edit them by hand.
- `prototype/inflight_sim.py` is the Python prototype the engine was ported from; `prototype/results/` are its outputs.
- `data/` holds the files the app downloads before a flight; `tools/` the scripts that made them.

## Rules for engine changes

1. Keep the engine deterministic: random numbers only from `Rng`, no `java.util.Random`, no clock reads. `LiveSession` must rebuild the same state from its log.
2. Keep the three prompt groups compiling on their own, in order: group 1 is `Rng`, `Geo`, `Wmm2025Coefficients`, `Wmm`, `Model`, `Airports`, `Tools`, `SelfTest`; group 2 adds `FlightSim`, `Filters`, `Session`, `Demo`, `SelfTestSim`; group 3 adds `Phase`, `SensorSim`, `SelfTestSensors`. A new file must be added to one group in `tools/build_prompts.py`.
3. The flight log format (`FlightEvent.encode`/`decode`) and the plan format (`FlightPlan.encode`/`decode`) are read back from old flights: add fields at the end, never reorder or remove them.
4. Before committing an engine change, run:

   ```bash
   engine/build.sh && engine/run.sh selftest
   engine/run.sh mc --runs 400 --airports data/airports.csv --check
   engine/run.sh pipeline --runs 200
   python3 tools/build_prompts.py
   ```

   and update `docs/ALGORITHM.md` and `engine/results/` if the numbers changed.
5. Write user-facing text (app strings in prompts, docs) in plain sentences; keep "Not for navigation" wherever an estimate is shown.
