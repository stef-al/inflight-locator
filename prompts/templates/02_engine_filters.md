Step 2 of 7. Add the estimation filters, the flight session and the flight simulator to the engine.

## 1. Engine files

Create the {{COUNT}} files below next to the engine files from step 1 (folder `inflight/engine/`, `package inflight.engine`). Copy each one exactly; do not change the step 1 files.

{{FILE_LIST}}

## 2. Diagnostics

Add a second card to the Diagnostics screen, below "Engine math", titled "Simulated flights", with its own "Run checks" button and result list built the same way. It calls `SelfTestSim.run()` on a background dispatcher; this takes a few seconds on a phone, so show "Flying simulated flights..." with a progress indicator while it runs.

Add a "Run all" button at the top of the screen that runs every card in turn.

## Check

In the emulator, Diagnostics > Run all: "Engine math" 34 of 34 and "Simulated flights" 5 of 5 pass. The simulated-flights details read like "median error 9.0 km over 87 samples".

{{ENGINE_FILES}}
