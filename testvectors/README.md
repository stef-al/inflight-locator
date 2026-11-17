# Test vectors

`vectors.json` holds reference values computed with the Python prototype and pygeomag 1.1.0 (NOAA's WMM2025 coefficients) for decimal year 2026.88:

- `wmm`: field strength, dip, declination and the north, east and down components at ten places from the equator to 64° north and 55° south, on the ground and at cruise altitude;
- `geo`: distances, bearings, destination points, great-circle interpolation and positions on the W6 4747 reference track.

`SelfTest.math()` in the engine checks the same values (field strength to 1 nT, angles to 0.002°, positions to 1e-5°).
