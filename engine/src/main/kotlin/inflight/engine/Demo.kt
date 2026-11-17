package inflight.engine

import kotlin.math.roundToInt

enum class DemoMode(val label: String) {
    KNOWN_ROUTE("Known route"),
    KNOWN_ROUTE_WIND("Known route + wind forecast"),
    GREAT_CIRCLE("Great circle only"),
    UNKNOWN_ROUTE("Unknown route (compass)"),
}

/**
 * A simulated W6 4747 flight played through the real LiveSession, for the app's demo screen and for tests.
 * The simulator plays the "truth"; the session only sees the events a phone would produce (takeoff, phase cues
 * about a minute late, one-minute compass and magnetometer averages, an optional GPS fix, landing).
 *
 * Call advanceTo() with seconds since liftoff (negative before takeoff), then read estimate(), truth() and cloud().
 */
class DemoRunner(
    val mode: DemoMode = DemoMode.KNOWN_ROUTE,
    val seed: Long = 1L,
    /** Minute after liftoff of a simulated GPS fix, or null for none. */
    val gpsFixMinute: Double? = null,
    val useMagnetometer: Boolean = false,
    particles: Int = 1500,
    airports: List<Airport> = emptyList(),
) {
    /** Liftoff time used for the simulated clock: 16 Nov 2026 11:04 UTC (12:04 in Prague). */
    val takeoffMillis: Long = 1_794_827_040_000L
    val flight: SimFlight
    val plan: FlightPlan
    val session: LiveSession
    val durationSeconds: Double
    private val events: List<FlightEvent>
    private var next = 0

    var simSeconds: Double = -60.0
        private set

    init {
        val base = FlightPlan.w64747()
        val sc = base.scenario()
        val rng = Rng(seed)
        flight = FlightSim.simulate(sc, Wmm2025(sc.year), rng)
        plan = when (mode) {
            DemoMode.KNOWN_ROUTE -> base
            DemoMode.KNOWN_ROUTE_WIND -> base.copy(windKmh = flight.wind0 + rng.normal(0.0, sc.perf.windFcSd))
            DemoMode.GREAT_CIRCLE -> base.copy(route = emptyList())
            DemoMode.UNKNOWN_ROUTE -> base.copy(dest = null)
        }
        session = LiveSession(plan, airports, seed = seed * 7919L + 17L, particles = particles, expectCues = true)
        durationSeconds = flight.tLand + 120.0
        val ev = ArrayList<FlightEvent>()
        fun ms(s: Double) = takeoffMillis + (s * 1000).roundToInt()
        ev.add(FlightEvent.Takeoff(ms(40.0), takeoffMillis, "simulated"))
        ev.add(FlightEvent.Heading(ms(40.0), flight.depHeading, 1))   // alignment from the takeoff roll
        flight.tTocObs?.let { ev.add(FlightEvent.TopOfClimb(ms(it + 90.0), ms(it))) }
        ev.add(FlightEvent.TopOfDescent(ms(flight.tTodObs + 60.0), ms(flight.tTodObs)))
        for (k in flight.tMin.indices) {
            ev.add(FlightEvent.Heading(ms(flight.tMin[k]), flight.heading[k], 1))
            if (useMagnetometer) ev.add(FlightEvent.Magnetic(ms(flight.tMin[k]), flight.magF[k], flight.magDip[k]))
        }
        gpsFixMinute?.let { m ->
            val t = m * 60.0
            if (t < flight.tLand) {
                val j = flight.indexAt(t)
                ev.add(FlightEvent.GpsFix(ms(t), flight.lat[j], flight.lon[j], flight.speedKmh[j], flight.trackBrg[j], "simulated"))
            }
        }
        ev.add(FlightEvent.Landing(ms(flight.tLand + 30.0), ms(flight.tLand), "simulated"))
        events = ev.sortedBy { it.tMillis }
    }

    /** Applies everything that happened up to simSec seconds after liftoff. */
    fun advanceTo(simSec: Double) {
        val now = takeoffMillis + (simSec * 1000).roundToInt()
        while (next < events.size && events[next].tMillis <= now) session.apply(events[next++])
        simSeconds = simSec
    }

    fun estimate(): SessionEstimate = session.estimate(takeoffMillis + (simSeconds * 1000).roundToInt())

    /** True position at the current simulated time. */
    fun truth(): LatLon = truthAt(simSeconds)

    fun truthAt(simSec: Double): LatLon {
        if (simSec <= 0.0) return LatLon(flight.lat[0], flight.lon[0])
        val j = flight.indexAt(minOf(simSec, flight.t.last()))
        return LatLon(flight.lat[j], flight.lon[j])
    }

    /** Flown path up to the current time, one point a minute, for drawing. */
    fun pathSoFar(): List<LatLon> {
        val out = ArrayList<LatLon>()
        var t = 0.0
        while (t <= minOf(simSeconds, flight.t.last())) {
            out.add(truthAt(t))
            t += 60.0
        }
        return out
    }

    fun errorKm(): Double {
        val e = estimate()
        val tr = truth()
        return Geo.havKm(e.lat, e.lon, tr.lat, tr.lon)
    }

    fun cloud(maxPoints: Int = 200): List<LatLon> = session.cloud(maxPoints)
}
