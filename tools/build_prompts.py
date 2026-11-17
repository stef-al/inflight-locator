"""Builds the AI Studio prompts that carry engine source code.

Usage (from the repository root): python3 tools/build_prompts.py
Reads prompts/templates/*.md and the engine sources, writes prompts/01_engine_core.md,
prompts/02_engine_filters.md and prompts/04_engine_sensors.md. Run it after any engine change,
so the prompts always carry exactly the code that the tests checked.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "engine", "src", "main", "kotlin", "inflight", "engine")

PARTS = {
    "01_engine_core.md": ["Rng.kt", "Geo.kt", "Wmm2025Coefficients.kt", "Wmm.kt", "Model.kt", "Airports.kt", "Tools.kt", "SelfTest.kt"],
    "02_engine_filters.md": ["FlightSim.kt", "Filters.kt", "Session.kt", "Demo.kt", "SelfTestSim.kt"],
    "04_engine_sensors.md": ["Phase.kt", "SensorSim.kt", "SelfTestSensors.kt"],
}


def main():
    used = set()
    for out_name, files in PARTS.items():
        tpl = open(os.path.join(ROOT, "prompts", "templates", out_name), encoding="utf-8").read()
        blocks = []
        listing = []
        for f in files:
            code = open(os.path.join(SRC, f), encoding="utf-8").read().rstrip("\n")
            n = code.count("\n") + 1
            listing.append(f"- `inflight/engine/{f}` ({n} lines)")
            blocks.append(f"### File: inflight/engine/{f}\n\n```kotlin\n{code}\n```\n")
            used.add(f)
        text = (tpl.replace("{{COUNT}}", str(len(files)))
                   .replace("{{FILE_LIST}}", "\n".join(listing))
                   .replace("{{ENGINE_FILES}}", "## Engine files to copy exactly\n\n" + "\n".join(blocks)))
        with open(os.path.join(ROOT, "prompts", out_name), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text.rstrip("\n") + "\n")
        print(f"{out_name}: {len(files)} files, {len(text):,} characters")
    missing = sorted(f for f in os.listdir(SRC) if f.endswith(".kt") and f not in used)
    if missing:
        raise SystemExit(f"engine files not carried by any prompt: {missing}")


if __name__ == "__main__":
    main()
