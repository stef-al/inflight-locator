package inflight.engine

import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * End-to-end check: synthetic barometer and accelerometer streams -> PhaseDetector -> LiveSession, compared with
 * the true trajectory. Shows what the late, detector-made cues cost compared with the ideal cues of MonteCarlo.
 */
object Pipeline {
    class Out(
        val dTakeoff: Double, val dToc: Double, val dTod: Double, val dLand: Double,
        val lagToc: Double, val lagTod: Double, val lagLand: Double,
        val axisErr: Double, val movedDetected: Boolean?,
        val err: List<Double>, val r95: List<Double>, val falseEvents: Int,
        val errIdeal: List<Double>,
    )

    fun flight(
        sc: Scenario, grid: MagModel, rng: Rng, particles: Int, hasBaro: Boolean, handle: Boolean,
        plan: FlightPlan,
    ): Out {
        val sim = FlightSim.simulate(sc, grid, rng)
        val t0 = 1_794_827_040_000L
        val det = PhaseDetector(hasBaro, (sc.dElevKm - sc.oElevKm) * 1000.0)
        val sSeed = rng.nextLong()
        val session = LiveSession(plan, seed = sSeed, particles = particles, expectCues = hasBaro)
        // the same flight with ideal cues (exact times plus 45 s noise, delivered at once), as in MonteCarlo
        val ideal = LiveSession(plan, seed = sSeed, particles = particles, expectCues = hasBaro)
        val errIdeal = ArrayList<Double>()
        val idealEvents = ArrayList<FlightEvent>()
        idealEvents.add(FlightEvent.Takeoff(t0, t0, "ideal"))
        if (hasBaro) {
            sim.tTocObs?.let { idealEvents.add(FlightEvent.TopOfClimb(t0 + (it * 1000).toLong(), t0 + (it * 1000).toLong())) }
            idealEvents.add(FlightEvent.TopOfDescent(t0 + (sim.tTodObs * 1000).toLong(), t0 + (sim.tTodObs * 1000).toLong()))
        }
        idealEvents.sortBy { it.tMillis }
        var nextIdeal = 0
        val events = ArrayList<PhaseEvent>()
        val err = ArrayList<Double>()
        val r95 = ArrayList<Double>()
        var nextMin = t0 + 60_000L
        var falseEvents = 0
        fun handleEvents(list: List<PhaseEvent>) {
            for (e in list) {
                events.add(e)
                when (e) {
                    is PhaseEvent.Takeoff -> session.apply(FlightEvent.Takeoff(e.detectedMillis, e.eventMillis, e.source))
                    is PhaseEvent.TopOfClimb -> session.apply(FlightEvent.TopOfClimb(e.detectedMillis, e.eventMillis))
                    is PhaseEvent.TopOfDescent -> session.apply(FlightEvent.TopOfDescent(e.detectedMillis, e.eventMillis))
                    is PhaseEvent.Landing -> session.apply(FlightEvent.Landing(e.detectedMillis, e.eventMillis, e.source))
                    else -> {}
                }
            }
        }
        val handleAt = if (handle) rng.uniform(0.3, 0.6) * sim.tLand else null
        val truth = SensorSim.run(
            sim, sc, rng, t0, accelHz = 25.0, handleAtS = handleAt,
            onPressure = { t, p -> handleEvents(det.onPressure(t, p)) },
            onAccel = { t, x, y, z ->
                handleEvents(det.onAccel(t, x, y, z))
                while (nextIdeal < idealEvents.size && idealEvents[nextIdeal].tMillis <= t) ideal.apply(idealEvents[nextIdeal++])
                if (t >= nextMin) {
                    val s = (nextMin - t0) / 1000.0
                    if (ideal.state == SessionState.AIRBORNE && s < sim.tLand) {
                        val e = ideal.estimate(nextMin)
                        val j = sim.indexAt(s)
                        errIdeal.add(Geo.havKm(e.lat, e.lon, sim.lat[j], sim.lon[j]))
                    }
                    if (session.state == SessionState.AIRBORNE && s < sim.tLand) {
                        val e = session.estimate(nextMin)
                        val j = sim.indexAt(s)
                        err.add(Geo.havKm(e.lat, e.lon, sim.lat[j], sim.lon[j]))
                        r95.add(e.r95Km)
                    }
                    nextMin += 60_000L
                }
            },
        )
        fun first(cls: Class<*>) = events.firstOrNull { cls.isInstance(it) }
        val tko = first(PhaseEvent.Takeoff::class.java) as PhaseEvent.Takeoff?
        val toc = first(PhaseEvent.TopOfClimb::class.java) as PhaseEvent.TopOfClimb?
        val tod = first(PhaseEvent.TopOfDescent::class.java) as PhaseEvent.TopOfDescent?
        val lnd = first(PhaseEvent.Landing::class.java) as PhaseEvent.Landing?
        val roll = first(PhaseEvent.TakeoffRoll::class.java) as PhaseEvent.TakeoffRoll?
        val moved = events.filterIsInstance<PhaseEvent.PhoneMoved>()
        falseEvents += events.count { it is PhaseEvent.Takeoff } - 1
        if (!handle) falseEvents += moved.size
        fun d(a: Long?, b: Long?) = if (a == null || b == null) Double.NaN else (a - b) / 1000.0
        return Out(
            d(tko?.eventMillis, truth.liftoffMillis), d(toc?.eventMillis, truth.tocMillis), d(tod?.eventMillis, truth.todMillis),
            d(lnd?.eventMillis, truth.landingMillis),
            d(toc?.detectedMillis, truth.tocMillis), d(tod?.detectedMillis, truth.todMillis), d(lnd?.detectedMillis, truth.landingMillis),
            if (roll == null) Double.NaN else Vec.angleDeg(roll.axis, truth.noseDevice),
            if (handle) moved.isNotEmpty() else null,
            err, r95, falseEvents, errIdeal,
        )
    }

    private fun f(x: Double, d: Int = 1) = if (x.isNaN()) "-" else String.format(Locale.US, "%.${d}f", x)

    private fun stats(name: String, v: List<Double>) {
        val ok = v.filter { !it.isNaN() }
        val miss = v.size - ok.size
        if (ok.isEmpty()) {
            println(String.format("%-30s all %d missed", name, v.size))
            return
        }
        val mean = ok.average()
        val sd = sqrt(ok.map { (it - mean) * (it - mean) }.average())
        val absP90 = MonteCarlo.percentile(ok.map { abs(it) }, 90.0)
        println(String.format(Locale.US, "%-30s mean %7s s  sd %6s s  |err| p90 %6s s  max %6s s  missed %d/%d",
            name, f(mean), f(sd), f(absP90), f(ok.maxOf { abs(it) }), miss, v.size))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        var runs = 60
        var seed = 77L
        var particles = 1500
        var hasBaro = true
        var handle = false
        var modeName = "known"
        var jobs = Runtime.getRuntime().availableProcessors()
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--runs" -> runs = args[++i].toInt()
                "--seed" -> seed = args[++i].toLong()
                "--particles" -> particles = args[++i].toInt()
                "--no-baro" -> hasBaro = false
                "--handle" -> handle = true
                "--mode" -> modeName = args[++i]
                "--jobs" -> jobs = args[++i].toInt()
                else -> error("unknown argument ${args[i]}")
            }
            i++
        }
        val base = FlightPlan.w64747()
        val plan = when (modeName) {
            "known" -> base
            "gc" -> base.copy(route = emptyList())
            else -> error("mode must be known or gc")
        }
        val sc = base.scenario()
        val grid = MagGrid.around(listOf(sc.greatCircle(), sc.referenceTrack()), sc.year)
        val t0 = System.currentTimeMillis()
        val results = Array<List<Out>>(jobs) { emptyList() }
        (0 until jobs).map { j ->
            thread {
                val n = runs / jobs + (if (j < runs % jobs) 1 else 0)
                val rng = Rng(seed + 1000L * j)
                results[j] = (0 until n).map { flight(sc, grid, rng, particles, hasBaro, handle, plan) }
            }
        }.forEach { it.join() }
        val all = results.flatMap { it }
        println("pipeline: ${all.size} flights, mode $modeName, barometer $hasBaro, phone moved mid-flight $handle, ${(System.currentTimeMillis() - t0) / 1000} s")
        stats("takeoff time error", all.map { it.dTakeoff })
        stats("top of climb time error", all.map { it.dToc })
        stats("top of descent time error", all.map { it.dTod })
        stats("landing time error", all.map { it.dLand })
        stats("top of climb reported after", all.map { it.lagToc })
        stats("top of descent reported after", all.map { it.lagTod })
        stats("landing reported after", all.map { it.lagLand })
        val ax = all.map { it.axisErr }.filter { !it.isNaN() }
        println("nose axis error: median ${f(MonteCarlo.median(ax))} deg, max ${f(ax.maxOrNull() ?: Double.NaN)} deg, found in ${ax.size}/${all.size}")
        if (handle) println("phone move detected in ${all.count { it.movedDetected == true }}/${all.size}")
        println("extra takeoff or phone-moved events: ${all.sumOf { it.falseEvents }}")
        val e = all.flatMap { it.err }
        var inside = 0
        var n = 0
        for (o in all) for (k in o.err.indices) {
            n++
            if (o.err[k] <= o.r95[k]) inside++
        }
        println("position error: median ${f(MonteCarlo.median(e))} km, p90 ${f(MonteCarlo.percentile(e, 90.0))} km, " +
            "inside the 95% circle ${f(100.0 * inside / maxOf(1, n))}%")
        val ei = all.flatMap { it.errIdeal }
        println("same flights with ideal cues: median ${f(MonteCarlo.median(ei))} km, p90 ${f(MonteCarlo.percentile(ei, 90.0))} km")
        val last5 = all.map { o -> o.err.takeLast(5).average() }
        println("last 5 minutes: median ${f(MonteCarlo.median(last5))} km, p90 ${f(MonteCarlo.percentile(last5, 90.0))} km")
    }
}
