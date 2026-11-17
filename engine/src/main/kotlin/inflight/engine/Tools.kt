package inflight.engine

import java.util.Locale
import kotlin.math.cos

/** Reads a reference track from a file the user downloaded before the flight. */
object TrackImport {
    private val COORDS = Regex("<coordinates>([\\s\\S]*?)</coordinates>", RegexOption.IGNORE_CASE)
    private val GPX_PT = Regex("<(?:trkpt|rtept|wpt)\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val LAT_ATTR = Regex("\\blat\\s*=\\s*\"([-0-9.eE+]+)\"")
    private val LON_ATTR = Regex("\\blon\\s*=\\s*\"([-0-9.eE+]+)\"")
    private val DECIMAL = Regex("-?\\d+\\.\\d+")

    /**
     * Extracts points from KML (<coordinates>lon,lat[,alt] ...</coordinates>), GPX (trkpt, rtept or wpt with lat and
     * lon attributes), or plain text / CSV with a latitude and a longitude on each line (the first two decimal
     * numbers in range, so "50.1, 14.2" and flight-tracker CSV exports both work).
     */
    fun parse(text: String): List<LatLon> {
        val kml = COORDS.findAll(text).toList()
        if (kml.isNotEmpty()) {
            val out = ArrayList<LatLon>()
            for (m in kml) {
                for (tuple in m.groupValues[1].trim().split(Regex("\\s+"))) {
                    val p = tuple.split(",")
                    if (p.size >= 2) {
                        val lon = p[0].toDoubleOrNull()
                        val lat = p[1].toDoubleOrNull()
                        if (lat != null && lon != null && valid(lat, lon)) out.add(LatLon(lat, lon))
                    }
                }
            }
            return dedupe(out)
        }
        val gpx = GPX_PT.findAll(text).toList()
        if (gpx.isNotEmpty()) {
            val out = ArrayList<LatLon>()
            for (m in gpx) {
                val lat = LAT_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = LON_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                if (lat != null && lon != null && valid(lat, lon)) out.add(LatLon(lat, lon))
            }
            return dedupe(out)
        }
        val out = ArrayList<LatLon>()
        for (line in text.lineSequence()) {
            val nums = DECIMAL.findAll(line).mapNotNull { it.value.toDoubleOrNull() }.toList()
            for (i in 0 until nums.size - 1) {
                if (valid(nums[i], nums[i + 1])) {
                    out.add(LatLon(nums[i], nums[i + 1]))
                    break
                }
            }
        }
        return dedupe(out)
    }

    private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    private fun dedupe(p: List<LatLon>): List<LatLon> {
        val out = ArrayList<LatLon>()
        for (x in p) if (out.isEmpty() || Geo.havKm(out.last().lat, out.last().lon, x.lat, x.lon) > 0.05) out.add(x)
        return out
    }

    /**
     * Turns a recorded or published track into en-route points for a FlightPlan: drops the parts within trimKm of
     * either airport (departure and arrival paths depend on the runway in use) and keeps a point every spacingKm.
     */
    fun toRoute(points: List<LatLon>, origin: LatLon, dest: LatLon, trimKm: Double = 30.0, spacingKm: Double = 20.0): List<LatLon> {
        val mid = points.filter {
            Geo.havKm(it.lat, it.lon, origin.lat, origin.lon) > trimKm && Geo.havKm(it.lat, it.lon, dest.lat, dest.lon) > trimKm
        }
        if (mid.isEmpty()) return emptyList()
        val out = ArrayList<LatLon>()
        out.add(mid.first())
        var acc = 0.0
        for (i in 1 until mid.size) {
            acc += Geo.havKm(mid[i - 1].lat, mid[i - 1].lon, mid[i].lat, mid[i].lon)
            if (acc >= spacingKm) {
                out.add(mid[i])
                acc = 0.0
            }
        }
        if (out.last() != mid.last()) out.add(mid.last())
        return out
    }
}

/** A position with a time (Unix milliseconds). */
data class TimedPoint(val tMillis: Long, val lat: Double, val lon: Double)

/** One logged estimate. */
data class LoggedEstimate(val tMillis: Long, val lat: Double, val lon: Double, val r95Km: Double)

/** Reads a timestamped track of the real flight (for checking accuracy after landing). */
object TimedTrackImport {
    private val WHEN = Regex("<when>([^<]+)</when>", RegexOption.IGNORE_CASE)
    private val GX_COORD = Regex("<gx:coord>([^<]+)</gx:coord>", RegexOption.IGNORE_CASE)
    private val PLACEMARK = Regex("<Placemark\\b[\\s\\S]*?</Placemark>", RegexOption.IGNORE_CASE)
    private val COORD1 = Regex("<coordinates>\\s*([-0-9.eE+]+)\\s*,\\s*([-0-9.eE+]+)", RegexOption.IGNORE_CASE)
    private val TRKPT = Regex("<trkpt\\b([^>]*)>([\\s\\S]*?)</trkpt>", RegexOption.IGNORE_CASE)
    private val TIME = Regex("<time>([^<]+)</time>", RegexOption.IGNORE_CASE)
    private val LAT_ATTR = Regex("\\blat\\s*=\\s*\"([-0-9.eE+]+)\"")
    private val LON_ATTR = Regex("\\blon\\s*=\\s*\"([-0-9.eE+]+)\"")
    private val ISO = Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?")
    private val UNIX = Regex("(?<![\\d.])(1[5-9]\\d{8})(\\d{3})?(?![\\d.])")   // seconds, or milliseconds
    private val DECIMAL = Regex("-?\\d+\\.\\d+")

    /** Parses ISO 8601 times such as 2026-11-16T11:04:12Z (no zone means UTC); null if unreadable. */
    fun parseIso(s: String): Long? {
        var t = s.trim().replace(' ', 'T')
        if (t.length < 16) return null
        if (t.length == 16) t += ":00"
        if (!t.endsWith("Z") && !Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(t.substring(10))) t += "Z"
        return try {
            java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * KML gx:Track (when + gx:coord), KML placemarks with a TimeStamp and a Point, GPX track points with a time,
     * or CSV / text lines holding a Unix time (seconds or milliseconds) or an ISO time plus a latitude and a
     * longitude (flight-tracker CSV exports and the app's own "tMillis,lat,lon" files work). Sorted by time.
     */
    fun parse(text: String): List<TimedPoint> {
        val out = ArrayList<TimedPoint>()
        val whens = WHEN.findAll(text).map { it.groupValues[1] }.toList()
        val coords = GX_COORD.findAll(text).map { it.groupValues[1] }.toList()
        if (coords.isNotEmpty() && whens.size >= coords.size) {
            val w = whens.takeLast(coords.size)
            for (i in coords.indices) {
                val p = coords[i].trim().split(Regex("\\s+"))
                val t = parseIso(w[i])
                val lon = p.getOrNull(0)?.toDoubleOrNull()
                val lat = p.getOrNull(1)?.toDoubleOrNull()
                if (t != null && lat != null && lon != null) out.add(TimedPoint(t, lat, lon))
            }
        }
        if (out.isEmpty()) {
            for (m in PLACEMARK.findAll(text)) {
                val t = WHEN.find(m.value)?.groupValues?.get(1)?.let { parseIso(it) } ?: continue
                val c = COORD1.find(m.value) ?: continue
                val lon = c.groupValues[1].toDoubleOrNull()
                val lat = c.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null) out.add(TimedPoint(t, lat, lon))
            }
        }
        if (out.isEmpty()) {
            for (m in TRKPT.findAll(text)) {
                val lat = LAT_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = LON_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                val t = TIME.find(m.groupValues[2])?.groupValues?.get(1)?.let { parseIso(it) }
                if (lat != null && lon != null && t != null) out.add(TimedPoint(t, lat, lon))
            }
        }
        if (out.isEmpty()) {
            for (line in text.lineSequence()) {
                val u = UNIX.find(line)
                val t = u?.let { m -> m.value.toLongOrNull()?.let { if (m.groupValues[2].isEmpty()) it * 1000 else it } }
                    ?: ISO.find(line)?.value?.let { parseIso(it) } ?: continue
                val rest = line.replace(ISO, " ").replace(UNIX, " ")
                val nums = DECIMAL.findAll(rest).mapNotNull { it.value.toDoubleOrNull() }.toList()
                for (i in 0 until nums.size - 1) {
                    if (nums[i] in -90.0..90.0 && nums[i + 1] in -180.0..180.0) {
                        out.add(TimedPoint(t, nums[i], nums[i + 1]))
                        break
                    }
                }
            }
        }
        return out.sortedBy { it.tMillis }
    }
}

/** Compares logged estimates with the real track: the acceptance test for real flights. */
object Evaluation {
    data class Result(
        val n: Int, val medianKm: Double, val p90Km: Double, val maxKm: Double,
        /** Share of estimates whose 95% circle held the true position. */
        val coverage: Double,
        /** (time, error km) for every estimate inside the track's time span. */
        val errors: List<Pair<Long, Double>>,
    )

    /** True position at time t, interpolated between track points; null outside the track's time span. */
    fun truthAt(truth: List<TimedPoint>, t: Long): LatLon? {
        if (truth.isEmpty() || t < truth.first().tMillis || t > truth.last().tMillis) return null
        var lo = 0
        var hi = truth.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (truth[mid].tMillis <= t) lo = mid else hi = mid
        }
        val a = truth[lo]
        val b = truth[hi]
        if (b.tMillis == a.tMillis) return LatLon(a.lat, a.lon)
        val f = (t - a.tMillis).toDouble() / (b.tMillis - a.tMillis)
        return Geo.gcInterp(a.lat, a.lon, b.lat, b.lon, f)
    }

    fun compare(estimates: List<LoggedEstimate>, truth: List<TimedPoint>): Result {
        val errs = ArrayList<Pair<Long, Double>>()
        var inside = 0
        for (e in estimates) {
            val p = truthAt(truth, e.tMillis) ?: continue
            val d = Geo.havKm(e.lat, e.lon, p.lat, p.lon)
            errs.add(Pair(e.tMillis, d))
            if (d <= e.r95Km) inside++
        }
        if (errs.isEmpty()) return Result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, errs)
        val s = errs.map { it.second }.sorted()
        fun q(p: Double): Double {
            val pos = p * (s.size - 1)
            val i = pos.toInt()
            return if (i + 1 < s.size) s[i] + (s[i + 1] - s[i]) * (pos - i) else s[i]
        }
        return Result(errs.size, q(0.5), q(0.9), s.last(), inside.toDouble() / errs.size, errs)
    }

    /** Reads lines "tMillis,lat,lon,r95Km" (the app's estimates log); other lines are skipped. */
    fun parseEstimates(text: String): List<LoggedEstimate> = text.lineSequence().mapNotNull { line ->
        val p = line.trim().split(",")
        if (p.size < 4) return@mapNotNull null
        val t = p[0].toLongOrNull() ?: return@mapNotNull null
        val lat = p[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
        val r = p[3].toDoubleOrNull() ?: return@mapNotNull null
        LoggedEstimate(t, lat, lon, r)
    }.toList()
}

/** Winds aloft from a forecast service, reduced to the one number the filter uses: the mean tailwind. */
object WindForecast {
    /** Points along the cruise part of a track (15% to 85% of its length) where to ask for the wind. */
    fun samplePoints(track: Track, count: Int = 8): List<LatLon> = (0 until count).map {
        val f = 0.15 + 0.7 * (it + 0.5) / count
        val p = track.pos(f * track.length)
        LatLon(p.lat, p.lon)
    }

    /** Standard pressure level nearest to a cruise altitude: 300 hPa (about 9.2 km), 250 (10.4 km) or 200 (11.8 km). */
    fun pressureLevelFor(cruiseAltKm: Double): Int = when {
        cruiseAltKm < 9.8 -> 300
        cruiseAltKm < 11.1 -> 250
        else -> 200
    }

    /**
     * Open-Meteo forecast request for wind speed (km/h) and direction (degrees the wind blows from) at one pressure
     * level, for all points at one hour. hourUtc: "yyyy-MM-ddTHH:00". With several points the response is a JSON
     * array with one object per point, each holding hourly.wind_speed_<level>hPa and hourly.wind_direction_<level>hPa.
     */
    fun openMeteoUrl(points: List<LatLon>, hourUtc: String, levelHpa: Int = 250): String {
        val lat = points.joinToString(",") { String.format(Locale.US, "%.3f", it.lat) }
        val lon = points.joinToString(",") { String.format(Locale.US, "%.3f", it.lon) }
        return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=wind_speed_${levelHpa}hPa,wind_direction_${levelHpa}hPa" +
            "&wind_speed_unit=kmh&timezone=GMT&start_hour=$hourUtc&end_hour=$hourUtc"
    }

    /**
     * Mean along-track wind in km/h (positive = tailwind) from wind speeds and the directions the wind blows FROM,
     * at points along the track.
     */
    fun alongTrackKmh(track: Track, points: List<LatLon>, speedKmh: List<Double>, fromDeg: List<Double>): Double {
        var sum = 0.0
        var n = 0
        for (i in points.indices) {
            val v = speedKmh.getOrNull(i) ?: continue
            val d = fromDeg.getOrNull(i) ?: continue
            if (v.isNaN() || d.isNaN()) continue
            val s = track.project(points[i].lat, points[i].lon).first
            val brg = track.bearingAt(s)
            sum += -v * cos((d - brg) * D2R)
            n++
        }
        return if (n == 0) 0.0 else sum / n
    }
}
