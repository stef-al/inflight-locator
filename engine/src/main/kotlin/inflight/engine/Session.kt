package inflight.engine

import java.util.Locale

/** How the position is estimated. */
enum class RouteMode(val label: String) {
    KNOWN_ROUTE("Known route"),        // preloaded reference track
    GREAT_CIRCLE("Great circle"),      // only origin and destination known
    UNKNOWN_ROUTE("Unknown route"),    // compass and clock from the origin (or from the last GPS fix)
}

enum class SessionState { PRE_FLIGHT, AIRBORNE, LANDED }

/** Everything the app knows about a flight before departure. */
data class FlightPlan(
    val label: String,
    val origin: Airport,
    val dest: Airport? = null,
    /** En-route points of the expected track, without the two airports; empty when unknown. */
    val route: List<LatLon> = emptyList(),
    /** Forecast mean along-track wind at cruise, km/h, positive for a tailwind; null without a forecast. */
    val windKmh: Double? = null,
    /** Decimal year of the flight (for the magnetic model). */
    val year: Double = 2026.88,
    val perf: Perf = Perf(),
    val depRunways: List<Runway>? = null,
    val arrRunways: List<Runway>? = null,
) {
    val mode: RouteMode
        get() = when {
            dest == null -> RouteMode.UNKNOWN_ROUTE
            route.isEmpty() -> RouteMode.GREAT_CIRCLE
            else -> RouteMode.KNOWN_ROUTE
        }

    fun scenario(): Scenario {
        val d = dest ?: origin
        return Scenario(
            name = label,
            origin = LatLon(origin.lat, origin.lon),
            dest = LatLon(d.lat, d.lon),
            oElevKm = origin.elevM / 1000.0,
            dElevKm = d.elevM / 1000.0,
            routePts = route,
            depRunways = depRunways ?: origin.runways(),
            arrRunways = arrRunways ?: d.runways(),
            perf = perf,
            year = year,
        )
    }

    /** Reference track the known-route filter follows (null in unknown-route mode). */
    fun referenceTrack(): Track? = when (mode) {
        RouteMode.UNKNOWN_ROUTE -> null
        RouteMode.GREAT_CIRCLE -> scenario().greatCircle()
        RouteMode.KNOWN_ROUTE -> scenario().referenceTrack()
    }

    /** Plain-text form, one key=value per line; decode() reads it back. */
    fun encode(): String {
        val sb = StringBuilder()
        sb.append("label=").append(label.replace("\n", " ")).append('\n')
        sb.append("origin=").append(encodeAirport(origin)).append('\n')
        if (dest != null) sb.append("dest=").append(encodeAirport(dest)).append('\n')
        if (route.isNotEmpty()) sb.append("route=").append(route.joinToString(";") { fmt(it.lat, 5) + "," + fmt(it.lon, 5) }).append('\n')
        if (windKmh != null) sb.append("wind_kmh=").append(fmt(windKmh, 1)).append('\n')
        sb.append("year=").append(fmt(year, 3)).append('\n')
        sb.append("tas_kmh=").append(fmt(perf.tas, 1)).append('\n')
        sb.append("cruise_alt_km=").append(fmt(perf.cruiseAlt, 2)).append('\n')
        if (depRunways != null) sb.append("dep_runways=").append(depRunways.joinToString(";") { fmt(it.headingDeg, 1) + ":" + fmt(it.weight, 3) }).append('\n')
        if (arrRunways != null) sb.append("arr_runways=").append(arrRunways.joinToString(";") { fmt(it.headingDeg, 1) + ":" + fmt(it.weight, 3) }).append('\n')
        return sb.toString()
    }

    companion object {
        private fun fmt(x: Double, d: Int) = String.format(Locale.US, "%.${d}f", x)

        private fun encodeAirport(a: Airport) = listOf(
            a.ident, a.iata, fmt(a.lat, 5), fmt(a.lon, 5), fmt(a.elevM, 0),
            a.runwayHeadings.joinToString(";") { fmt(it, 1) }, a.name.replace("|", "/"),
        ).joinToString("|")

        private fun decodeAirport(s: String): Airport {
            val f = s.split("|")
            return Airport(
                f[0], f[1], f.getOrElse(6) { "" }, f[2].toDouble(), f[3].toDouble(), f[4].toDouble(),
                f[5].split(";").mapNotNull { it.toDoubleOrNull() },
            )
        }

        private fun decodeRunways(s: String) = s.split(";").filter { it.isNotBlank() }.map {
            val p = it.split(":")
            Runway(p[0].toDouble(), p.getOrNull(1)?.toDoubleOrNull() ?: 1.0)
        }

        fun decode(text: String): FlightPlan {
            val kv = text.lineSequence().filter { it.contains('=') }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            val perf = Perf(
                tas = kv["tas_kmh"]?.toDoubleOrNull() ?: Perf().tas,
                cruiseAlt = kv["cruise_alt_km"]?.toDoubleOrNull() ?: Perf().cruiseAlt,
            )
            return FlightPlan(
                label = kv["label"] ?: "",
                origin = decodeAirport(kv.getValue("origin")),
                dest = kv["dest"]?.let { decodeAirport(it) },
                route = kv["route"]?.split(";")?.filter { it.contains(',') }?.map {
                    LatLon(it.substringBefore(',').toDouble(), it.substringAfter(',').toDouble())
                } ?: emptyList(),
                windKmh = kv["wind_kmh"]?.toDoubleOrNull(),
                year = kv["year"]?.toDoubleOrNull() ?: 2026.88,
                perf = perf,
                depRunways = kv["dep_runways"]?.let { decodeRunways(it) },
                arrRunways = kv["arr_runways"]?.let { decodeRunways(it) },
            )
        }

        /** The worked example: Wizz Air W6 4747 Skopje to Prague along its published airway route. */
        fun w64747(year: Double = 2026.88): FlightPlan {
            val sc = Scenarios.w64747()
            return FlightPlan(
                label = "W6 4747 SKP-PRG",
                origin = Airport("LWSK", "SKP", "Skopje International Airport", sc.origin.lat, sc.origin.lon, 238.0, listOf(165.0, 345.0)),
                dest = Airport("LKPR", "PRG", "Václav Havel Airport Prague", sc.dest.lat, sc.dest.lon, 380.0, listOf(65.0, 245.0, 127.0, 307.0)),
                route = sc.routePts,
                year = year,
                depRunways = sc.depRunways,
                arrRunways = sc.arrRunways,
            )
        }
    }
}

/**
 * One observation, as written to the flight log. tMillis is when it became known (log order); phase events also
 * carry eventMillis, the estimated time of the event itself. All times are Unix milliseconds.
 */
sealed class FlightEvent {
    abstract val tMillis: Long

    data class Takeoff(override val tMillis: Long, val eventMillis: Long, val source: String = "manual") : FlightEvent()
    data class TopOfClimb(override val tMillis: Long, val eventMillis: Long) : FlightEvent()
    data class TopOfDescent(override val tMillis: Long, val eventMillis: Long) : FlightEvent()

    /** Aircraft true heading (about a minute's average). alignmentId comes from HeadingTracker. */
    data class Heading(override val tMillis: Long, val trueDeg: Double, val alignmentId: Int) : FlightEvent()

    /** The phone moved: headings are unavailable until the next alignment. */
    data class HeadingLost(override val tMillis: Long) : FlightEvent()

    /** Gate-calibrated field strength (microtesla) and dip angle (degrees), averaged over about a minute. */
    data class Magnetic(override val tMillis: Long, val fMicroTesla: Double, val dipDeg: Double) : FlightEvent()

    /** Particle hits on the camera sensor during the last windowS seconds. */
    data class Cosmic(override val tMillis: Long, val count: Int, val windowS: Double) : FlightEvent()

    /** An exact position: a GPS fix, or a position from the cabin Wi-Fi moving map (source "cabin"). */
    data class GpsFix(
        override val tMillis: Long, val lat: Double, val lon: Double,
        val speedKmh: Double? = null, val courseDeg: Double? = null, val source: String = "gps",
    ) : FlightEvent()

    data class Landing(override val tMillis: Long, val eventMillis: Long, val source: String = "manual") : FlightEvent()

    /** One line of the flight log. */
    fun encode(): String = when (this) {
        is Takeoff -> "TKO,$tMillis,$eventMillis,$source"
        is TopOfClimb -> "TOC,$tMillis,$eventMillis"
        is TopOfDescent -> "TOD,$tMillis,$eventMillis"
        is Heading -> "HDG,$tMillis,${f(trueDeg, 2)},$alignmentId"
        is HeadingLost -> "HDL,$tMillis"
        is Magnetic -> "MAG,$tMillis,${f(fMicroTesla, 3)},${f(dipDeg, 3)}"
        is Cosmic -> "CR,$tMillis,$count,${f(windowS, 0)}"
        is GpsFix -> "FIX,$tMillis,${f(lat, 6)},${f(lon, 6)},${speedKmh?.let { f(it, 1) } ?: ""},${courseDeg?.let { f(it, 1) } ?: ""},$source"
        is Landing -> "LND,$tMillis,$eventMillis,$source"
    }

    companion object {
        private fun f(x: Double, d: Int) = String.format(Locale.US, "%.${d}f", x)

        /** Parses one log line; null for comments, blank lines and anything unknown. */
        fun decode(line: String): FlightEvent? {
            val p = line.trim().split(",")
            if (p.size < 2 || p[0].startsWith("#")) return null
            return try {
                val t = p[1].toLong()
                when (p[0]) {
                    "TKO" -> Takeoff(t, p[2].toLong(), p.getOrElse(3) { "manual" })
                    "TOC" -> TopOfClimb(t, p[2].toLong())
                    "TOD" -> TopOfDescent(t, p[2].toLong())
                    "HDG" -> Heading(t, p[2].toDouble(), p.getOrNull(3)?.toIntOrNull() ?: 0)
                    "HDL" -> HeadingLost(t)
                    "MAG" -> Magnetic(t, p[2].toDouble(), p[3].toDouble())
                    "CR" -> Cosmic(t, p[2].toInt(), p[3].toDouble())
                    "FIX" -> GpsFix(t, p[2].toDouble(), p[3].toDouble(), p.getOrNull(4)?.toDoubleOrNull(), p.getOrNull(5)?.toDoubleOrNull(), p.getOrElse(6) { "gps" })
                    "LND" -> Landing(t, p[2].toLong(), p.getOrElse(3) { "manual" })
                    else -> null
                }
            } catch (e: RuntimeException) {
                null
            }
        }
    }
}

/** What the app shows. */
data class SessionEstimate(
    val state: SessionState,
    val mode: RouteMode,
    val lat: Double,
    val lon: Double,
    /** The aircraft should be inside this circle about 95% of the time. */
    val r95Km: Double,
    val minutesSinceTakeoff: Double?,
    /** 0 climb, 1 cruise, 2 descent; null on the ground. */
    val phase: Int?,
    val speedKmh: Double?,
    val altKm: Double?,
    /** Track distance still to fly (known route and great circle only). */
    val remainingKm: Double?,
    /** Minutes since the last exact position (GPS fix or cabin feed), if any. */
    val minutesSinceFix: Double?,
    /** After landing: the airport the flight most likely landed at. */
    val landedAt: Airport?,
)

/**
 * One flight, from boarding to landing. Feed it FlightEvents in the order they become known; ask for an estimate
 * whenever the screen needs one. Every event is kept in `log`; replay the log (fromLog) to rebuild the same state
 * after the app is restarted, because the session is deterministic for a given seed.
 *
 * expectCues: true when the phone has a barometer, so top-of-climb and top-of-descent cues will come. Without
 * cues the filter does not wait for them (it would otherwise keep candidates in cruise too long).
 */
class LiveSession(
    plan: FlightPlan,
    private val airports: List<Airport> = emptyList(),
    val seed: Long = 4747L,
    val particles: Int = 1500,
    val expectCues: Boolean = true,
) {
    /** The plan as stored (encoded and decoded once), so a replay starts from exactly the same numbers. */
    val plan: FlightPlan = FlightPlan.decode(plan.encode())

    var state: SessionState = SessionState.PRE_FLIGHT
        private set
    var mode: RouteMode = plan.mode
        private set
    var takeoffMillis: Long? = null
        private set
    var landingMillis: Long? = null
        private set
    var lastFix: FlightEvent.GpsFix? = null
        private set
    var landedAt: Airport? = null
        private set
    var landingGuess: LandingGuess? = null
        private set
    val log = ArrayList<FlightEvent>()

    private val sc = plan.scenario()
    private val rng = Rng(seed)
    private val wmm = Wmm2025(plan.year)
    private var filter: PositionFilter? = null
    private var ref: Track? = null
    private var cSd = 0.0
    private var windMean = 0.0
    private var windSd = sc.perf.windSd
    private var lastHeading: Double? = null
    private var alignId = Int.MIN_VALUE
    private var tocEvent: Double? = null
    private var todEvent: Double? = null
    private var landedEstimate: Estimate? = null

    /** The track the estimate follows, for drawing (null in unknown-route mode). */
    val referenceTrack: Track? get() = if (mode == RouteMode.UNKNOWN_ROUTE) null else ref ?: plan.referenceTrack()

    fun apply(event: FlightEvent) {
        // process the logged form of the event, so a replay of the log repeats this exactly
        val e = FlightEvent.decode(event.encode()) ?: return
        log.add(e)
        if (state == SessionState.LANDED) return
        if (e is FlightEvent.Takeoff) {
            if (state == SessionState.PRE_FLIGHT) start(e.eventMillis)
            return
        }
        if (state != SessionState.AIRBORNE) return   // observations before takeoff are only logged
        val f = filter ?: return
        f.advanceTo(tSec(e.tMillis))
        when (e) {
            is FlightEvent.TopOfClimb -> {
                tocEvent = tSec(e.eventMillis)
                f.onTopOfClimb(tocEvent!!)
            }
            is FlightEvent.TopOfDescent -> {
                todEvent = tSec(e.eventMillis)
                f.onTopOfDescent(todEvent!!)
            }
            is FlightEvent.Heading -> {
                if (e.alignmentId != alignId) {
                    if (alignId != Int.MIN_VALUE) (f as? UnknownRouteFilter)?.resetHeadingBias()
                    alignId = e.alignmentId
                }
                lastHeading = e.trueDeg
                f.setHeading(e.trueDeg)
            }
            is FlightEvent.HeadingLost -> {
                (f as? UnknownRouteFilter)?.headingLost()
                lastHeading = null
            }
            is FlightEvent.Magnetic -> f.onMagnetic(e.fMicroTesla, e.dipDeg)
            is FlightEvent.Cosmic -> f.onCosmic(e.count, e.windowS)
            is FlightEvent.GpsFix -> onFix(f, e)
            is FlightEvent.Landing -> land(f, e)
            else -> {}
        }
    }

    private fun tSec(ms: Long): Double = (ms - (takeoffMillis ?: ms)) / 1000.0

    private fun start(liftoffMillis: Long) {
        takeoffMillis = liftoffMillis
        state = SessionState.AIRBORNE
        val pf = sc.perf
        windMean = plan.windKmh ?: 0.0
        windSd = if (plan.windKmh != null) pf.windFcSd else pf.windSd
        filter = when (mode) {
            RouteMode.UNKNOWN_ROUTE -> UnknownRouteFilter(sc, wmm, rng, windMean, windSd, particles)
            else -> {
                val track = plan.referenceTrack()!!
                ref = track
                cSd = if (mode == RouteMode.KNOWN_ROUTE) pf.trackLateralSd else pf.gcLateralFrac * track.length
                val ep = FlightSim.extraKmPrior(sc, track.length, Rng(seed xor 0x5DEECE66DL), 400)
                KnownRouteFilter(
                    sc, track, wmm, rng, cSd, ep.first, ep.second, cues = expectCues,
                    windMean = windMean, windSd = windSd, n = particles, cueLagS = 100.0,
                )
            }
        }
    }

    private fun onFix(f: PositionFilter, e: FlightEvent.GpsFix) {
        lastFix = e
        val track = ref
        if (f is KnownRouteFilter && track != null && track.distanceKm(e.lat, e.lon) > maxOf(60.0, 4.0 * cSd)) {
            // far off the expected route: carry on by dead reckoning from the fix
            mode = RouteMode.UNKNOWN_ROUTE
            val nf = UnknownRouteFilter(
                sc, wmm, rng, windMean, windSd, particles,
                startLat = e.lat, startLon = e.lon, startT = f.t, startSpreadKm = 0.5,
            )
            tocEvent?.let { nf.onTopOfClimb(it) }
            todEvent?.let { nf.onTopOfDescent(it) }
            lastHeading?.let { nf.setHeading(it) }
            nf.onGpsFix(e.lat, e.lon, e.speedKmh, e.courseDeg)
            filter = nf
            return
        }
        f.onGpsFix(e.lat, e.lon, e.speedKmh, e.courseDeg)
    }

    private fun land(f: PositionFilter, e: FlightEvent.Landing) {
        f.advanceTo(tSec(e.eventMillis))
        landingMillis = e.eventMillis
        state = SessionState.LANDED
        val est = f.estimate()
        landedEstimate = est
        val guess = if (airports.isNotEmpty()) LandingSnap.rank(airports, est.lat, est.lon, est.r95Km, lastHeading) else null
        landingGuess = guess
        var at = guess?.best
        val d = plan.dest
        if (d != null && (at == null || at.ident != d.ident) &&
            Geo.havKm(d.lat, d.lon, est.lat, est.lon) <= 1.5 * maxOf(est.r95Km, 30.0)
        ) {
            at = d   // the planned destination is consistent with the estimate: most flights land where planned
        }
        landedAt = at
    }

    /** The estimate at nowMillis (moves the filter forward to that time). */
    fun estimate(nowMillis: Long): SessionEstimate {
        val fixAgo = lastFix?.let { (nowMillis - it.tMillis) / 60000.0 }
        when (state) {
            SessionState.PRE_FLIGHT -> return SessionEstimate(
                state, mode, plan.origin.lat, plan.origin.lon, 3.0, null, null, null, null, null, fixAgo, null,
            )
            SessionState.LANDED -> {
                val est = landedEstimate!!
                val at = landedAt
                return SessionEstimate(
                    state, mode, at?.lat ?: est.lat, at?.lon ?: est.lon, if (at != null) 3.0 else est.r95Km,
                    (landingMillis!! - takeoffMillis!!) / 60000.0, null, 0.0, 0.0, null, fixAgo, at,
                )
            }
            SessionState.AIRBORNE -> {
                val f = filter!!
                f.advanceTo(tSec(nowMillis))
                val e = f.estimate()
                return SessionEstimate(
                    state, mode, e.lat, e.lon, e.r95Km, (nowMillis - takeoffMillis!!) / 60000.0, e.phase,
                    e.speedKmh, e.altKm, (f as? KnownRouteFilter)?.remainingKm(), fixAgo, null,
                )
            }
        }
    }

    /** A thinned copy of the candidate positions, for drawing. */
    fun cloud(maxPoints: Int = 200): List<LatLon> = filter?.cloud(maxPoints) ?: emptyList()

    /** The whole log as text: a header line with the session settings, then one event per line. */
    fun encodeLog(): String {
        val sb = StringBuilder()
        sb.append("#session,seed=").append(seed).append(",particles=").append(particles)
            .append(",cues=").append(expectCues).append('\n')
        for (e in log) sb.append(e.encode()).append('\n')
        return sb.toString()
    }

    companion object {
        /**
         * Replays a flight log and records the estimate every stepMs from takeoff to landing, for the accuracy check
         * after a flight. With dropFixes the GPS and cabin-feed positions are left out: the spec's test of how the
         * estimate would have done without them.
         */
        fun replayEstimates(
            plan: FlightPlan, logText: String, airports: List<Airport> = emptyList(),
            dropFixes: Boolean = false, stepMs: Long = 60_000L,
        ): List<LoggedEstimate> {
            val header = logText.lineSequence().firstOrNull { it.startsWith("#session") } ?: ""
            val events = logText.lineSequence().mapNotNull { FlightEvent.decode(it) }
                .filter { !(dropFixes && it is FlightEvent.GpsFix) }.toList()
            val s = fromLog(plan, header, airports)
            val takeoff = events.firstOrNull { it is FlightEvent.Takeoff } as FlightEvent.Takeoff? ?: return emptyList()
            val landing = events.firstOrNull { it is FlightEvent.Landing } as FlightEvent.Landing?
            val end = landing?.eventMillis ?: events.last().tMillis
            val out = ArrayList<LoggedEstimate>()
            var i = 0
            var t = takeoff.eventMillis + stepMs
            while (t <= end) {
                while (i < events.size && events[i].tMillis <= t) s.apply(events[i++])
                if (s.state == SessionState.AIRBORNE) {
                    val e = s.estimate(t)
                    out.add(LoggedEstimate(t, e.lat, e.lon, e.r95Km))
                }
                t += stepMs
            }
            return out
        }

        /** Rebuilds a session from encodeLog() output (for example after the app was restarted mid-flight). */
        fun fromLog(plan: FlightPlan, logText: String, airports: List<Airport> = emptyList()): LiveSession {
            val header = logText.lineSequence().firstOrNull { it.startsWith("#session") } ?: ""
            val kv = header.split(",").filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val s = LiveSession(
                plan, airports,
                seed = kv["seed"]?.toLongOrNull() ?: 4747L,
                particles = kv["particles"]?.toIntOrNull() ?: 1500,
                expectCues = kv["cues"]?.toBooleanStrictOrNull() ?: true,
            )
            for (line in logText.lineSequence()) FlightEvent.decode(line)?.let { s.apply(it) }
            return s
        }
    }
}
