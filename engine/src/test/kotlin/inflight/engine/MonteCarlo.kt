package inflight.engine

import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Monte Carlo check of the Kotlin engine: the same experiment as prototype/inflight_sim.py.
 * Random numbers differ from the Python run, so results agree statistically, not digit for digit.
 */
object MonteCarlo {
    class Cfg(
        val name: String, val kind: String,             // "baseline", "known" or "unknown"
        val ref: String = "typ",                        // "gc" or "typ" (preloaded track)
        val cues: Boolean = true, val mag: Boolean = false, val cosmic: Boolean = false,
        val windFc: Boolean = false, val fix: Boolean = false,
        val fixVelocity: Boolean = false,               // the fix also carries ground speed and course
        val lateCues: Boolean = false,                  // cues are delivered late, as a real detector would
    )

    val CONFIGS = listOf(
        Cfg("baseline_clock_gc", "baseline"),
        Cfg("A1_gc_clock", "known", ref = "gc", cues = false),
        Cfg("A2_gc_cues", "known", ref = "gc"),
        Cfg("A3_track_clock", "known", cues = false),
        Cfg("A4_track_cues", "known"),
        Cfg("A5_track_cues_mag", "known", mag = true),
        Cfg("A6_track_cues_mag_cosmic", "known", mag = true, cosmic = true),
        Cfg("A7_track_cues_windfc", "known", windFc = true),
        Cfg("A8_track_cues_windfc_mag", "known", windFc = true, mag = true),
        Cfg("A9_track_cues_fix", "known", fix = true),
        Cfg("B1_unknown_compass", "unknown"),
        Cfg("B2_unknown_compass_mag", "unknown", mag = true),
        Cfg("B3_unknown_compass_mag_cosmic", "unknown", mag = true, cosmic = true),
        Cfg("B4_unknown_compass_fix", "unknown", fix = true),
        // Kotlin-only extensions, not in the Python prototype
        Cfg("X1_track_cues_late", "known", lateCues = true),
        Cfg("X2_track_cues_fix_velocity", "known", fix = true, fixVelocity = true),
        Cfg("X3_unknown_compass_fix_velocity", "unknown", fix = true, fixVelocity = true),
    )

    /** Python prototype, 400 flights of W6 4747: median, p90, coverage, last-5-min median, last-5-min p90, mid-flight median. */
    val PYTHON_W64747 = mapOf(
        "baseline_clock_gc" to doubleArrayOf(64.61, 117.65, Double.NaN, 12.76, 14.44, 81.41),
        "A1_gc_clock" to doubleArrayOf(48.47, 86.86, 0.96, 23.55, 59.71, 66.40),
        "A2_gc_cues" to doubleArrayOf(41.65, 79.31, 0.97, 17.15, 30.94, 66.78),
        "A3_track_clock" to doubleArrayOf(21.74, 70.91, 0.94, 25.08, 66.26, 29.27),
        "A4_track_cues" to doubleArrayOf(19.33, 54.98, 0.95, 17.76, 32.91, 29.42),
        "A5_track_cues_mag" to doubleArrayOf(18.27, 50.51, 0.95, 16.11, 30.79, 27.50),
        "A6_track_cues_mag_cosmic" to doubleArrayOf(18.74, 51.20, 0.95, 20.07, 37.43, 27.05),
        "A7_track_cues_windfc" to doubleArrayOf(12.71, 28.00, 0.96, 15.70, 24.44, 15.20),
        "A8_track_cues_windfc_mag" to doubleArrayOf(12.45, 27.17, 0.96, 14.67, 23.72, 14.41),
        "A9_track_cues_fix" to doubleArrayOf(10.50, 29.92, 0.96, 16.37, 22.02, 2.25),
        "B1_unknown_compass" to doubleArrayOf(48.09, 132.41, 0.95, 102.29, 198.99, 57.52),
        "B2_unknown_compass_mag" to doubleArrayOf(43.87, 112.47, 0.95, 86.28, 153.63, 51.24),
        "B3_unknown_compass_mag_cosmic" to doubleArrayOf(43.95, 112.52, 0.95, 87.39, 155.73, 51.00),
        "B4_unknown_compass_fix" to doubleArrayOf(13.23, 44.96, 0.93, 18.28, 36.48, 1.71),
    )

    class RunOut(val err: DoubleArray, val r95: DoubleArray)
    class Snap(val inCircle: Int, val hitPos: Boolean, val hitBoth: Boolean)
    class FlightOut(
        val airMin: Double, val pathKm: Double, val crTotal: Int,
        val runs: Map<String, RunOut>, val snaps: Map<String, Snap>,
    )

    class Setup(val sc: Scenario, val fixMin: Double, val fixFrac: Double) {
        val gc = sc.greatCircle()
        val typ = sc.referenceTrack()
        val grid = MagGrid.around(listOf(gc, typ), sc.year)
    }

    private fun fixTime(setup: Setup, sim: SimFlight, dt: Double): Double =
        if (setup.fixMin > 0) (setup.fixMin * 60.0 / dt).roundToInt() * dt
        else (setup.fixFrac * sim.tLand / dt).roundToInt() * dt

    fun runFlight(
        setup: Setup, cfgs: List<Cfg>, epGc: Pair<Double, Double>, epTyp: Pair<Double, Double>,
        rng: Rng, particles: Int, airports: List<Airport>?,
    ): FlightOut {
        val sc = setup.sc
        val pf = sc.perf
        val sim = FlightSim.simulate(sc, setup.grid, rng)
        val dt = 30.0
        val steps = ceil(sim.tLand / dt).toInt() - 1
        val truthIdx = IntArray(steps) { sim.indexAt(dt * (it + 1)) }
        val runs = HashMap<String, RunOut>()
        val snaps = HashMap<String, Snap>()
        for (cfg in cfgs) {
            val err = DoubleArray(steps) { Double.NaN }
            val r95 = DoubleArray(steps) { Double.NaN }
            if (cfg.kind == "baseline") {
                for (k in 0 until steps) {
                    val p = setup.gc.pos(minOf(setup.gc.length, pf.tas * dt * (k + 1) / 3600.0))
                    err[k] = Geo.havKm(p.lat, p.lon, sim.lat[truthIdx[k]], sim.lon[truthIdx[k]])
                }
                runs[cfg.name] = RunOut(err, r95)
                continue
            }
            var windMean = 0.0
            var windSd = pf.windSd
            if (cfg.windFc) {
                windMean = sim.wind0 + rng.normal(0.0, pf.windFcSd)   // forecast = truth + forecast error
                windSd = pf.windFcSd
            }
            val mag: MagModel? = if (cfg.mag || cfg.cosmic) setup.grid else null
            val known = cfg.kind == "known"
            val filter: PositionFilter = if (known) {
                val useGc = cfg.ref == "gc"
                val ep = if (useGc) epGc else epTyp
                KnownRouteFilter(
                    sc, if (useGc) setup.gc else setup.typ, mag, rng,
                    cSd = if (useGc) pf.gcLateralFrac * setup.gc.length else pf.trackLateralSd,
                    ePriorMean = ep.first, ePriorSd = ep.second, cues = cfg.cues,
                    windMean = windMean, windSd = windSd, n = particles,
                    cueLagS = if (cfg.lateCues) 90.0 else 0.0,
                )
            } else {
                UnknownRouteFilter(sc, mag, rng, windMean, windSd, particles)
            }
            val fixT = if (cfg.fix) fixTime(setup, sim, dt) else -1.0
            val tocDelay = if (cfg.lateCues) 60.0 else 0.0
            val todDelay = if (cfg.lateCues) 90.0 else 0.0
            for (k in 0 until steps) {
                val t = dt * (k + 1)
                filter.advanceTo(t, if (known) null else sim.headingForStep(t, dt))
                if (cfg.cues) {
                    val toc = sim.tTocObs
                    if (!filter.tocDone && toc != null && t >= toc + tocDelay) filter.onTopOfClimb(toc)
                    if (!filter.todDone && t >= sim.tTodObs + todDelay) filter.onTopOfDescent(sim.tTodObs)
                }
                if (cfg.fix && abs(t - fixT) < 1e-6) {
                    val j = sim.indexAt(t)
                    if (cfg.fixVelocity) filter.onGpsFix(sim.lat[j], sim.lon[j], sim.speedKmh[j], sim.trackBrg[j])
                    else filter.onGpsFix(sim.lat[j], sim.lon[j])
                }
                if (cfg.mag && k % 2 == 1) {
                    val m = (k + 1) / 2 - 1
                    if (m < sim.tMin.size) filter.onMagnetic(sim.magF[m], sim.magDip[m])
                }
                if (cfg.cosmic && (k + 1) % 20 == 0) {
                    val c = (k + 1) / 20 - 1
                    if (c < sim.tCosmic.size) filter.onCosmic(sim.cosmic[c], 600.0)
                }
                if (k % 2 == 1 || (!known && k == steps - 1)) {
                    val e = filter.estimate()
                    err[k] = Geo.havKm(e.lat, e.lon, sim.lat[truthIdx[k]], sim.lon[truthIdx[k]])
                    r95[k] = e.r95Km
                    if (!known && k == steps - 1 && airports != null && sim.heading.isNotEmpty()) {
                        val course = Geo.mod360(sim.heading.last() - (filter as UnknownRouteFilter).headingBiasMean())
                        val g = LandingSnap.rank(airports, e.lat, e.lon, e.r95Km, course)
                        val truth = Airports.nearest(airports, sc.dest.lat, sc.dest.lon)
                        snaps[cfg.name] = Snap(g.inCircle, g.bestByPosition === truth, g.best === truth)
                    }
                }
            }
            runs[cfg.name] = RunOut(err, r95)
        }
        return FlightOut(sim.tLand / 60.0, sim.pathKm, sim.cosmic.sum(), runs, snaps)
    }

    // ---- statistics (numpy-style linear percentiles, NaN ignored) ----
    fun percentile(values: List<Double>, q: Double): Double {
        val v = values.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return Double.NaN
        val pos = q / 100.0 * (v.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(v.size - 1, lo + 1)
        return v[lo] + (v[hi] - v[lo]) * (pos - lo)
    }

    fun median(values: List<Double>) = percentile(values, 50.0)

    class Summary(
        val name: String, val median: Double, val p90: Double, val coverage: Double, val r95Median: Double,
        val last5Median: Double, val last5P90: Double, val midMedian: Double,
        val series: List<DoubleArray>,   // minute, n, median, p90, r95 median, coverage
    )

    fun summarize(name: String, flights: List<FlightOut>): Summary {
        val runs = flights.map { it.runs.getValue(name) }
        val maxMin = runs.maxOf { it.err.size / 2 }
        val all = ArrayList<Double>()
        val allR = ArrayList<Double>()
        var covered = 0
        var covN = 0
        val series = ArrayList<DoubleArray>()
        for (m in 1..maxMin) {
            val e = ArrayList<Double>()
            val r = ArrayList<Double>()
            var c = 0
            var cn = 0
            for (ro in runs) {
                val k = 2 * m - 1
                if (k < ro.err.size && !ro.err[k].isNaN()) {
                    e.add(ro.err[k])
                    all.add(ro.err[k])
                    if (!ro.r95[k].isNaN()) {
                        r.add(ro.r95[k]); allR.add(ro.r95[k])
                        cn++
                        if (ro.err[k] <= ro.r95[k]) c++
                    }
                }
            }
            covered += c
            covN += cn
            if (e.size >= 0.9 * runs.size) {
                series.add(doubleArrayOf(m.toDouble(), e.size.toDouble(), median(e), percentile(e, 90.0),
                    if (r.isEmpty()) Double.NaN else median(r), if (cn == 0) Double.NaN else c.toDouble() / cn))
            }
        }
        val last = runs.map { ro -> ro.err.takeLast(10).filter { !it.isNaN() }.average() }
        val mid = runs.map { ro ->
            val half = ro.err.size / 2
            val m = (0.5 * half).roundToInt().coerceIn(1, half)
            ro.err[2 * m - 1]
        }
        return Summary(
            name, median(all), percentile(all, 90.0), if (covN == 0) Double.NaN else covered.toDouble() / covN,
            if (allR.isEmpty()) Double.NaN else median(allR), median(last), percentile(last, 90.0), median(mid), series,
        )
    }

    private fun f(x: Double, d: Int = 1) = if (x.isNaN()) "-" else String.format(Locale.US, "%.${d}f", x)

    @JvmStatic
    fun main(args: Array<String>) {
        var runs = 200
        var seed = 4747L
        var particles = 1500
        var scenario = "w64747"
        var only: Set<String>? = null
        var jobs = Runtime.getRuntime().availableProcessors()
        var airportsPath: String? = null
        var outPath: String? = null
        var check = false
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--runs" -> runs = args[++i].toInt()
                "--seed" -> seed = args[++i].toLong()
                "--particles" -> particles = args[++i].toInt()
                "--scenario" -> scenario = args[++i]
                "--only" -> only = args[++i].split(",").toSet()
                "--jobs" -> jobs = args[++i].toInt()
                "--airports" -> airportsPath = args[++i]
                "--out" -> outPath = args[++i]
                "--check" -> check = true
                else -> error("unknown argument ${args[i]}")
            }
            i++
        }
        val setup = when (scenario) {
            "w64747" -> Setup(Scenarios.w64747(), 40.0, 0.45)
            "long_ns" -> Setup(Scenarios.longHaul(true), 0.0, 0.45)
            "long_ew" -> Setup(Scenarios.longHaul(false), 0.0, 0.45)
            else -> error("unknown scenario $scenario")
        }
        val cfgs = CONFIGS.filter { only == null || it.name in only }
        val airports = airportsPath?.let { Airports.parseCsv(File(it).readText()) }
        val t0 = System.currentTimeMillis()
        val prior = Rng(seed - 1)
        val epGc = FlightSim.extraKmPrior(setup.sc, setup.gc.length, prior)
        val epTyp = FlightSim.extraKmPrior(setup.sc, setup.typ.length, prior)
        println("scenario ${setup.sc.name}: great circle ${f(setup.gc.length)} km, reference track ${f(setup.typ.length)} km, " +
            "magnetic grid ${setup.grid.points} points")
        println("extra km vs great circle ${f(epGc.first)} +/- ${f(epGc.second)}, vs track ${f(epTyp.first)} +/- ${f(epTyp.second)}")
        if (airports != null) println("${airports.size} airports loaded")

        val results = Array<List<FlightOut>>(jobs) { emptyList() }
        val threads = (0 until jobs).map { j ->
            thread {
                val n = runs / jobs + (if (j < runs % jobs) 1 else 0)
                val rng = Rng(seed + 1000L * j)
                val list = ArrayList<FlightOut>()
                for (r in 0 until n) {
                    list.add(runFlight(setup, cfgs, epGc, epTyp, rng, particles, airports))
                    if (j == 0 && (r + 1) % maxOf(1, n / 5) == 0) {
                        println("  flight ${r + 1}/$n on worker 0, ${(System.currentTimeMillis() - t0) / 1000} s")
                    }
                }
                results[j] = list
            }
        }
        threads.forEach { it.join() }
        val flights = results.flatMap { it }
        val air = flights.map { it.airMin }
        println("airborne minutes: median ${f(median(air))}, p10 ${f(percentile(air, 10.0))}, p90 ${f(percentile(air, 90.0))}; " +
            "path km median ${f(median(flights.map { it.pathKm }))}; particle hits per flight median ${f(median(flights.map { it.crTotal.toDouble() }))}")

        val sums = cfgs.map { summarize(it.name, flights) }
        val ref = if (scenario == "w64747") PYTHON_W64747 else emptyMap()
        println()
        println(String.format(Locale.US, "%-32s %7s %7s %6s %7s %7s %7s %7s   %s", "config", "median", "p90", "cover", "r95", "last5", "l5p90", "mid", "Python median/p90/cover/last5/mid"))
        var failures = 0
        for (s in sums) {
            val py = ref[s.name]
            var verdict = ""
            if (py != null) {
                val okMed = abs(s.median - py[0]) <= maxOf(3.0, 0.20 * py[0])
                val okP90 = abs(s.p90 - py[1]) <= maxOf(6.0, 0.25 * py[1])
                val okCov = py[2].isNaN() || s.coverage >= 0.90
                val okLast = abs(s.last5Median - py[3]) <= maxOf(4.0, 0.25 * py[3])
                val ok = okMed && okP90 && okCov && okLast
                if (!ok) failures++
                verdict = "${f(py[0])}/${f(py[1])}/${f(py[2], 2)}/${f(py[3])}/${f(py[5])}  ${if (ok) "OK" else "DIFFERS"}"
            }
            println(String.format(Locale.US, "%-32s %7s %7s %6s %7s %7s %7s %7s   %s", s.name, f(s.median), f(s.p90), f(s.coverage, 3),
                f(s.r95Median), f(s.last5Median), f(s.last5P90), f(s.midMedian), verdict))
        }
        println()
        val minutes = listOf(5, 10, 20, 30, 39, 40, 41, 45, 50, 60, 70, 80)
        println("median error by minute: " + minutes.joinToString(" ") { String.format("%6d", it) })
        for (s in sums) {
            val row = minutes.joinToString(" ") { m ->
                val x = s.series.firstOrNull { it[0].toInt() == m }
                String.format(Locale.US, "%6s", if (x == null) "-" else f(x[2]))
            }
            println(String.format("%-23s", s.name.take(23)) + " " + row)
        }
        for (c in cfgs) {
            val sn = flights.mapNotNull { it.snaps[c.name] }
            if (sn.isNotEmpty()) {
                println("landing match ${c.name}: airports in circle median ${f(median(sn.map { it.inCircle.toDouble() }))}, " +
                    "right airport by position ${f(100.0 * sn.count { it.hitPos } / sn.size)}%, with runway heading ${f(100.0 * sn.count { it.hitBoth } / sn.size)}%")
            }
        }
        println("elapsed ${(System.currentTimeMillis() - t0) / 1000} s")

        if (outPath != null) {
            val sb = StringBuilder()
            sb.append("{\n \"scenario\": \"${setup.sc.name}\",\n \"runs\": ${flights.size},\n \"particles\": $particles,\n \"seed\": $seed,\n")
            sb.append(" \"airborne_min_median\": ${f(median(air), 2)},\n \"overall\": {\n")
            sb.append(sums.joinToString(",\n") { s ->
                "  \"${s.name}\": {\"median\": ${f(s.median, 2)}, \"p90\": ${f(s.p90, 2)}, \"coverage95\": ${if (s.coverage.isNaN()) "null" else f(s.coverage, 3)}, " +
                    "\"last5min_median\": ${f(s.last5Median, 2)}, \"last5min_p90\": ${f(s.last5P90, 2)}, \"midflight_median\": ${f(s.midMedian, 2)}}"
            })
            sb.append("\n },\n \"median_by_minute\": {\n")
            sb.append(sums.joinToString(",\n") { s ->
                "  \"${s.name}\": [" + s.series.joinToString(", ") { "[${it[0].toInt()}, ${f(it[2], 2)}]" } + "]"
            })
            sb.append("\n }\n}\n")
            File(outPath).writeText(sb.toString())
            println("wrote $outPath")
        }
        if (check) {
            if (failures > 0) {
                println("CHECK FAILED: $failures configuration(s) differ from the Python prototype by more than the tolerance")
                kotlin.system.exitProcess(1)
            }
            println("CHECK PASSED: all configurations agree with the Python prototype within the tolerance")
        }
    }
}
