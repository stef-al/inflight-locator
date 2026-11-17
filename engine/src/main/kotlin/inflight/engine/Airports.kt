package inflight.engine

import java.util.Locale
import kotlin.math.abs

/** An airport with the true headings of its runway directions (may be empty). */
data class Airport(
    val ident: String,   // ICAO code where one exists, for example LKPR
    val iata: String,    // for example PRG; may be empty
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevM: Double,
    val runwayHeadings: List<Double>,
) {
    val label: String get() = if (iata.isNotEmpty()) iata else ident

    /** Runway directions as equally likely choices (four compass directions when none are known). */
    fun runways(): List<Runway> =
        if (runwayHeadings.isEmpty()) Scenario.DEFAULT_RUNWAYS else runwayHeadings.distinct().map { Runway(it) }
}

object Airports {
    /**
     * Parses data/airports.csv from the repository. Columns:
     * ident,iata,lat,lon,elev_m,runway_headings_true,name  (headings separated by semicolons).
     */
    fun parseCsv(text: String): List<Airport> {
        val out = ArrayList<Airport>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("ident,")) continue
            val f = line.split(",", limit = 7)
            if (f.size < 7) continue
            val lat = f[2].toDoubleOrNull() ?: continue
            val lon = f[3].toDoubleOrNull() ?: continue
            val headings = f[5].split(";").mapNotNull { it.toDoubleOrNull() }
            out.add(Airport(f[0], f[1], f[6].trim(), lat, lon, f[4].toDoubleOrNull() ?: 0.0, headings))
        }
        return out
    }

    /** Finds an airport by IATA or ICAO code, ignoring case. */
    fun byCode(airports: List<Airport>, code: String): Airport? {
        val c = code.trim().uppercase()
        return airports.firstOrNull { it.iata == c } ?: airports.firstOrNull { it.ident == c }
    }

    fun nearest(airports: List<Airport>, lat: Double, lon: Double): Airport? =
        airports.minByOrNull { Geo.havKm(it.lat, it.lon, lat, lon) }

    /** A position in words, for example "45 km NW of BUD (Budapest Ferenc Liszt International Airport)". */
    fun describe(airports: List<Airport>, lat: Double, lon: Double): String {
        val a = nearest(airports, lat, lon) ?: return String.format(Locale.US, "%.2f, %.2f", lat, lon)
        val d = Geo.havKm(a.lat, a.lon, lat, lon)
        if (d < 5.0) return "at ${a.label} (${a.name})"
        val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val c = points[((Geo.bearingDeg(a.lat, a.lon, lat, lon) + 22.5) / 45.0).toInt() % 8]
        return String.format(Locale.US, "%.0f km %s of %s (%s)", d, c, a.label, a.name)
    }
}

/** Result of matching a landing to an airport. */
data class LandingGuess(
    /** Best match using position and, when a landing course is given, runway direction. */
    val best: Airport?,
    /** Best match by position alone. */
    val bestByPosition: Airport?,
    /** Airports inside the error circle. */
    val inCircle: Int,
    /** Up to five candidates with their scores (lower is better). */
    val ranked: List<Pair<Airport, Double>>,
)

object LandingSnap {
    /**
     * At touchdown: ranks airports by distance from the estimate and by how well a runway lines up with
     * the landing course (true degrees). The winner is where the flight most likely landed.
     */
    fun rank(airports: List<Airport>, estLat: Double, estLon: Double, r95Km: Double, courseDeg: Double?): LandingGuess {
        val rr = maxOf(r95Km, 30.0)
        val sig = rr / 2.45
        var inCircle = 0
        var bestPos: Airport? = null
        var scorePos = Double.MAX_VALUE
        val scored = ArrayList<Pair<Airport, Double>>()
        for (a in airports) {
            if (abs(a.lat - estLat) > 3.0 * rr / 111.0 + 0.1) continue   // quick reject
            val d = Geo.havKm(a.lat, a.lon, estLat, estLon)
            if (d <= rr) inCircle++
            if (d > 3.0 * rr) continue
            val chi = (d / sig) * (d / sig)
            if (chi < scorePos) {
                scorePos = chi
                bestPos = a
            }
            var total = chi
            if (courseDeg != null) {
                val dh = a.runwayHeadings.minOfOrNull { abs(Geo.angDiff(it, courseDeg)) } ?: 30.0
                total += (dh / 8.0) * (dh / 8.0)
            }
            scored.add(Pair(a, total))
        }
        scored.sortBy { it.second }
        return LandingGuess(scored.firstOrNull()?.first, bestPos, inCircle, scored.take(5))
    }
}
