Optional step 8. Read the aircraft's position from a cabin Wi-Fi moving-map feed.

Do not change any file in `inflight/engine/`.

Some cabin Wi-Fi portals serve the passenger moving map from a small JSON endpoint on the aircraft's own network, readable without buying internet access. Endpoints differ by vendor and change without notice, so this step adds a generic, user-configured adapter rather than vendor-specific code.

## 1. Settings

A "Cabin feed" section in a new Settings screen (reached from a gear icon on the Live screen):

- Enabled (default off).
- Feed URL, for example `http://<portal-host>/<path>.json`.
- JSON paths for latitude and longitude (dot-separated, array indices allowed: `flight.position.lat`), and optional paths for ground speed and track, each with a unit selector (km/h, knots, m/s; degrees).
- A "Test" button that fetches the URL once and shows the values it found, or the error.

Rules: plain GET only, no credentials, no cookies, no form submission, and never try to get around a portal's login or payment page. If the feed asks for anything, stop and show "This feed needs a login; not supported."

## 2. Polling

While a flight is being tracked and the feed is enabled: every 30 seconds, if a Wi-Fi network is connected, request the URL through that network (`ConnectivityManager` → the Wi-Fi `Network` → `network.openConnection(url)`, because a portal without internet access is often not the default route), with 5-second timeouts, on a background dispatcher. On a valid position (latitude within ±90, longitude within ±180, not both zero), post to the engine thread: `record(FlightEvent.GpsFix(now, lat, lon, speedKmh, trackDeg, "cabin"))`. Keep at most one cabin fix per 30 seconds in the log.

## 3. Display

When the last cabin fix is under two minutes old, the Live status line starts with "Cabin feed" instead of the mode label, and a chip shows "Cabin feed: N s ago". In the flight summary, count cabin fixes separately from GPS fixes (source "cabin").

## Check

Point the feed URL at any public JSON file that contains a latitude and longitude, set the paths, and tap Test: the values appear. During a tracked flight in the emulator, the dot jumps to that position every 30 seconds and the status line says "Cabin feed".
