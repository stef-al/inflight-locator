package inflight.engine

import kotlin.math.abs

/** Simulation checks for SelfTest: a few seeded flights through the same code the app runs in flight. */
object SelfTestSim {
    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.isEmpty()) Double.NaN else if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
    }

    fun run(): List<Check> {
        val out = ArrayList<Check>()

        // 1. known route with a wind forecast: accuracy and honesty of the error circle
        val errs = ArrayList<Double>()
        var inside = 0
        for (s in 1L..5L) {
            val d = DemoRunner(DemoMode.KNOWN_ROUTE_WIND, seed = s, particles = 400)
            var t = 300.0
            while (t < d.flight.tLand) {
                d.advanceTo(t)
                val e = d.estimate()
                val tr = d.truth()
                val err = Geo.havKm(e.lat, e.lon, tr.lat, tr.lon)
                errs.add(err)
                if (err <= e.r95Km) inside++
                t += 300.0
            }
        }
        val med = median(errs)
        val cov = inside.toDouble() / errs.size
        out.add(Check("Simulated flights, known route + wind forecast", med < 30.0,
            "median error %.1f km over %d samples (about 13 km expected; limit 30 km)".format(med, errs.size)))
        out.add(Check("Simulated flights, error circle", cov >= 0.8,
            "%.0f%% of samples inside the 95%% circle (limit 80%%)".format(100 * cov)))

        // 2. a GPS fix resets the estimate
        var worst = 0.0
        for (s in 11L..14L) {
            val d = DemoRunner(DemoMode.UNKNOWN_ROUTE, seed = s, gpsFixMinute = 40.0, particles = 400)
            d.advanceTo(41 * 60.0)
            worst = maxOf(worst, d.errorKm())
        }
        out.add(Check("GPS fix resets the estimate", worst < 5.0,
            "error one minute after the fix: at most %.2f km in 4 flights (limit 5 km)".format(worst)))

        // 3. the flight log replays to the same state
        val d = DemoRunner(DemoMode.KNOWN_ROUTE, seed = 3, gpsFixMinute = 30.0, useMagnetometer = true, particles = 300)
        d.advanceTo(50 * 60.0)
        val a = d.estimate()
        val replay = LiveSession.fromLog(d.plan, d.session.encodeLog())
        val b = replay.estimate(d.takeoffMillis + 50 * 60_000L)
        val same = abs(a.lat - b.lat) < 1e-9 && abs(a.lon - b.lon) < 1e-9 && abs(a.r95Km - b.r95Km) < 1e-9
        out.add(Check("Flight log replays to the same estimate", same,
            "live %.5f, %.5f (r95 %.2f km) vs replay %.5f, %.5f (r95 %.2f km)".format(a.lat, a.lon, a.r95Km, b.lat, b.lon, b.r95Km)))

        // 4. post-flight accuracy check: replay a whole demo flight with and without its GPS fix
        val full = DemoRunner(DemoMode.UNKNOWN_ROUTE, seed = 4, gpsFixMinute = 40.0, particles = 300)
        full.advanceTo(full.durationSeconds)
        val logText = full.session.encodeLog()
        val truth = (0..(full.flight.tLand / 60.0).toInt()).map {
            val p = full.truthAt(it * 60.0)
            TimedPoint(full.takeoffMillis + it * 60_000L, p.lat, p.lon)
        }
        val withFix = Evaluation.compare(LiveSession.replayEstimates(full.plan, logText), truth)
        val noFix = Evaluation.compare(LiveSession.replayEstimates(full.plan, logText, dropFixes = true), truth)
        out.add(Check("Accuracy check after a flight (replay with and without the GPS fix)",
            withFix.n > 60 && noFix.n > 60 && withFix.medianKm < noFix.medianKm,
            "%d minutes compared; median error %.1f km with the fix, %.1f km without".format(withFix.n, withFix.medianKm, noFix.medianKm)))
        return out
    }
}
