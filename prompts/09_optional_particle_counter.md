Optional step 9, experimental. Count cosmic-ray particle hits on the camera sensor.

Do not change any file in `inflight/engine/`.

At cruise altitude, secondary cosmic rays leave bright single-pixel or few-pixel spots on a camera sensor whose lens is covered. One published test counted about 19 hits per hour on a phone at cruise. The rate rises with altitude and with magnetic latitude, which gives the engine a weak extra cue. In the W6 4747 simulations it made no measurable difference, so this step is for experiments and stays off by default.

## 1. Setting and requirements

- A "Particle counter (experimental)" switch in Settings, off by default, with the explanation above.
- It runs only while tracking, only while the phone is charging, and only after the user confirms the lens is covered (face down on a tray table is enough).
- Add the CAMERA permission (asked when the switch is turned on), FOREGROUND_SERVICE_CAMERA, and add `camera` to the service's `foregroundServiceType` (`location|camera`). Start the camera only while the service is in the foreground.

## 2. Counting

With Camera2 on the back camera: manual exposure, the longest exposure time and highest sensitivity the device allows (`SENSOR_INFO_EXPOSURE_TIME_RANGE`, `SENSOR_INFO_SENSITIVITY_RANGE`), YUV_420_888 at the smallest size of at least 640×480, analysing the Y plane only.

- Hot-pixel mask: during the first 60 seconds, mark every pixel that is above the threshold in more than 5% of frames and ignore it afterwards.
- Threshold per frame: mean + 8 × standard deviation of the Y plane (at least 40 grey levels above the mean).
- A hit is a cluster of adjacent pixels above the threshold that are not masked; count each cluster once.
- If the frame mean rises above 40 (light reaching the sensor), pause counting and show "Cover the camera".

Every 10 minutes, on the engine thread: `record(FlightEvent.Cosmic(now, hitsInTheseTenMinutes, 600.0))`, and add the count to the Live screen ("Particles: N in the last 10 min").

## Check

On a phone, on the ground, face down and charging: the counter runs, the hot-pixel mask settles, and the count stays at a few per hour. The emulator has no camera: the switch shows "Not available on this device".
