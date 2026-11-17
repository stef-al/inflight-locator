# Python prototype

`inflight_sim.py` is the Monte Carlo prototype behind the spec's accuracy figures: it simulates flights (true trajectory and phone observations) and runs the particle filters with different sensor mixes. The Kotlin engine is a port of it and is checked against these results.

```bash
pip install -r requirements.txt
python3 inflight_sim.py --runs 400 --jobs 2 --seed 4747 --airports airports.csv --runways runways.csv --out out
```

The script's header lists the exact runs used. The landing-snap test needs the full OurAirports `airports.csv` and `runways.csv` (not the compact file in `data/`): download them from [ourairports-data](https://github.com/davidmegginson/ourairports-data).

`results/` holds the outputs: `summary_w64747.json` (400 flights of W6 4747, 13 estimators, error statistics per minute), `summary_long_ns.json` and `summary_long_ew.json` (80 flights each of the two synthetic long-haul legs), and the console logs.
