package inflight.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Design values: aircraft performance, atmosphere and phone-sensor noise.
 * Defaults describe an Airbus A321neo on a short-haul leg. They are assumptions until real flights are logged.
 * Speeds in km/h, distances in km, times as named.
 */
data class Perf(
    // aircraft and atmosphere
    val tas: Double = 840.0,            // cruise true airspeed (about Mach 0.78 to 0.79)
    val tasSd: Double = 12.0,
    val windSd: Double = 60.0,          // day-to-day sd of the along-track wind at cruise
    val windVar: Double = 20.0,         // sd of the wind variation along the route
    val windWavelen: Double = 800.0,
    val climbMin: Double = 18.5,        // liftoff to top of climb, minutes
    val climbMinSd: Double = 2.0,
    val climbExp: Double = 3.0,
    val todKm: Double = 225.0,          // track distance from top of descent to touchdown
    val todKmSd: Double = 20.0,
    val descExp: Double = 3.0,
    val vLo: Double = 300.0,            // ground speed at liftoff
    val vApp: Double = 250.0,           // ground speed at touchdown
    val cruiseAlt: Double = 11.0,       // km
    val latDevSd: List<Double> = listOf(8.0, 5.0, 3.0),  // smooth lateral deviation harmonics (truth simulation)
    val devTaperKm: Double = 60.0,
    val gcLateralFrac: Double = 0.042,  // lateral sd, as a fraction of the distance, when only the great circle is known
    val trackLateralSd: Double = 12.0,  // lateral sd around a preloaded track
    val arrLatSd: Double = 18.0,        // extra lateral sd while manoeuvring for the arrival runway
    val depLatSd: Double = 7.0,         // extra lateral sd right after takeoff (runway direction unknown)
    val windFcSd: Double = 15.0,        // error sd of a preloaded winds-aloft forecast
    // phone sensors
    val cueSdS: Double = 45.0,          // timing error of the top-of-climb and top-of-descent cues, seconds
    val hbSd: Double = 5.0,             // compass bias after calibration, degrees
    val hdNoise: Double = 2.0,          // per-minute heading noise, degrees
    val bFSd: Double = 0.7,             // field-strength bias after gate calibration, microtesla
    val bISd: Double = 1.5,             // dip-angle bias, degrees
    val fNoise: Double = 0.3,           // noise of a one-minute average
    val iNoise: Double = 0.5,
    val bFRw: Double = 0.2,             // bias random walk per sqrt(30 minutes)
    val bIRw: Double = 0.4,
    val crCruisePerH: Double = 19.33,   // particle hits per hour on a phone camera at cruise (one published measurement)
    val crGroundPerH: Double = 3.68,
    val crRefRc: Double = 14.0,         // assumed mean cutoff rigidity of that measurement, GV
)

/** A runway direction (true heading in degrees) with a relative weight for how often it is used. */
data class Runway(val headingDeg: Double, val weight: Double = 1.0)

/** A flight to estimate or to simulate. Elevations in km. */
data class Scenario(
    val name: String,
    val origin: LatLon,
    val dest: LatLon,
    val oElevKm: Double,
    val dElevKm: Double,
    val routePts: List<LatLon> = emptyList(),   // typical en-route waypoints; empty means great circle only
    val depRunways: List<Runway> = DEFAULT_RUNWAYS,
    val arrRunways: List<Runway> = DEFAULT_RUNWAYS,
    val perf: Perf = Perf(),
    val pDirect: Double = 0.35,                 // chance that an interior waypoint is skipped (direct-to)
    val keepFirst: Int = 2,
    val keepLast: Int = 1,
    val depLegKm: Double = 6.0,
    val fafKm: Double = 16.0,
    val ipKm: Double = 8.0,
    val downwindOffsetKm: Double = 9.0,
    val year: Double = 2026.88,
    val zoneKm: Double = 150.0,                 // arrival zone over which extra track distance is spread
) {
    /** Great circle from origin to destination. */
    fun greatCircle(): Track = Track(listOf(origin, dest))

    /** Reference track through the typical waypoints (the great circle when there are none). */
    fun referenceTrack(): Track = Track(listOf(origin) + routePts + listOf(dest))

    companion object {
        /** Used when the runway directions of an airport are not known. */
        val DEFAULT_RUNWAYS = listOf(Runway(0.0), Runway(90.0), Runway(180.0), Runway(270.0))
    }
}

object Scenarios {
    /** Wizz Air W6 4747, Skopje (SKP/LWSK) to Prague (PRG/LKPR), with a published airway route. */
    fun w64747(): Scenario = Scenario(
        name = "W6 4747 Skopje-Prague",
        origin = LatLon(41.96162, 21.62137),
        dest = LatLon(50.10114, 14.26296),
        oElevKm = 0.24,
        dElevKm = 0.38,
        routePts = listOf(
            LatLon(42.15944, 21.39722),  // LONTA
            LatLon(43.71750, 20.81033),  // VTN
            LatLon(43.85467, 20.75311),  // GONAR
            LatLon(44.21206, 20.60267),  // NEVAK
            LatLon(44.49189, 20.48333),  // ORVAN
            LatLon(44.80703, 20.33494),  // BEO
            LatLon(46.04731, 19.35593),  // TONDO
            LatLon(47.62377, 17.40891),  // TEKNO
            LatLon(47.83071, 17.11443),  // BEGLA
            LatLon(47.92196, 16.95406),  // GESGI
            LatLon(48.10511, 16.62926),  // FMD
            LatLon(48.41714, 16.01859),  // STO
            LatLon(48.88811, 15.61579),  // LANUX
        ),
        depRunways = listOf(Runway(345.0, 0.7), Runway(165.0, 0.3)),                                     // LWSK 34 / 16
        arrRunways = listOf(Runway(245.0, 0.60), Runway(65.0, 0.25), Runway(307.0, 0.12), Runway(127.0, 0.03)),  // LKPR 24 / 06 / 30 / 12
    )

    /** Indicative long-haul legs: the typical route is the great circle bent sideways by up to 120 km. */
    fun longHaul(northSouth: Boolean): Scenario {
        val o = LatLon(50.0333, 8.5706)  // Frankfurt
        val d = if (northSouth) LatLon(-26.1392, 28.2460) else LatLon(40.6413, -73.7781)
        val n = 24
        val route = ArrayList<LatLon>()
        var b0 = Double.NaN
        for (i in 1 until n) {
            val f = i.toDouble() / n
            val a = Geo.gcInterp(o.lat, o.lon, d.lat, d.lon, f)
            val a2 = Geo.gcInterp(o.lat, o.lon, d.lat, d.lon, minOf(1.0, f + 0.01))
            val b = Geo.bearingDeg(a.lat, a.lon, a2.lat, a2.lon)
            val off = 120.0 * sin(PI * f) * (if (northSouth) 1 else -1)
            route.add(Geo.destPoint(a.lat, a.lon, b + 90.0, off))
            if (b0.isNaN()) b0 = b
        }
        val bEnd = Geo.bearingDeg(route.last().lat, route.last().lon, d.lat, d.lon)
        val perf = Perf(
            tas = 900.0, tasSd = 15.0, windSd = 50.0, windVar = 45.0, windWavelen = 2500.0,
            climbMin = 25.0, climbMinSd = 3.0, todKm = 250.0, todKmSd = 25.0, cruiseAlt = 11.5,
            latDevSd = listOf(60.0, 35.0, 20.0), devTaperKm = 150.0, trackLateralSd = 60.0,
        )
        return Scenario(
            name = if (northSouth) "Long-haul north-south (Frankfurt-Johannesburg)" else "Long-haul east-west (Frankfurt-New York)",
            origin = o, dest = d, oElevKm = 0.1, dElevKm = if (northSouth) 1.7 else 0.0,
            routePts = route,
            depRunways = listOf(Runway(b0, 0.6), Runway(Geo.mod360(b0 + 180.0), 0.4)),
            arrRunways = listOf(
                Runway(bEnd, 0.4), Runway(Geo.mod360(bEnd + 90.0), 0.2),
                Runway(Geo.mod360(bEnd + 180.0), 0.2), Runway(Geo.mod360(bEnd + 270.0), 0.2),
            ),
            perf = perf, pDirect = 0.15, zoneKm = 200.0,
        )
    }
}

/** Cosmic-ray hit rate on a phone camera sensor. The dependence on cutoff rigidity is an assumed shape. */
object Cosmic {
    private const val POLE_LAT = 80.85     // centred-dipole north pole, about 2025
    private const val POLE_LON = -72.76
    private val RC = doubleArrayOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0, 17.0)
    private val REL = doubleArrayOf(1.00, 1.00, 0.88, 0.74, 0.60, 0.48, 0.40, 0.33, 0.30)

    /** Vertical cutoff rigidity in GV, centred-dipole approximation. */
    fun cutoffRigidity(lat: Double, lon: Double): Double {
        val sinm = sin(lat * D2R) * sin(POLE_LAT * D2R) +
            cos(lat * D2R) * cos(POLE_LAT * D2R) * cos((lon - POLE_LON) * D2R)
        return 14.9 * (1.0 - sinm * sinm).pow(2)
    }

    private fun rel(rc: Double): Double {
        if (rc <= RC[0]) return REL[0]
        for (i in 1 until RC.size) {
            if (rc <= RC[i]) return REL[i - 1] + (REL[i] - REL[i - 1]) * (rc - RC[i - 1]) / (RC[i] - RC[i - 1])
        }
        return REL[REL.size - 1]
    }

    /** Expected hits per hour at a position and altitude. */
    fun ratePerHour(lat: Double, lon: Double, altKm: Double, perf: Perf): Double {
        val cruise = perf.crCruisePerH * rel(cutoffRigidity(lat, lon)) / rel(perf.crRefRc)
        return perf.crGroundPerH + (cruise - perf.crGroundPerH) * (altKm / 11.0).coerceIn(0.0, 1.2).pow(3)
    }
}
