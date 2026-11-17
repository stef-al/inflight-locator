package inflight.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Sensor-processing checks for SelfTest: phase detection on synthetic sensor data, and heading alignment. */
object SelfTestSensors {
    fun run(): List<Check> {
        val out = ArrayList<Check>()

        // 1. flight phases from synthetic barometer and accelerometer data
        val sc = Scenarios.w64747()
        val rng = Rng(99)
        val sim = FlightSim.simulate(sc, Wmm2025(sc.year), rng)
        val det = PhaseDetector(hasBarometer = true, elevChangeM = (sc.dElevKm - sc.oElevKm) * 1000.0)
        val got = ArrayList<PhaseEvent>()
        val t0 = 1_794_827_040_000L
        val truth = SensorSim.run(sim, sc, rng, t0, accelHz = 20.0,
            onPressure = { t, p -> got.addAll(det.onPressure(t, p)) },
            onAccel = { t, x, y, z -> got.addAll(det.onAccel(t, x, y, z)) })
        fun dt(ev: Long?, tr: Long?): Double = if (ev == null || tr == null) Double.NaN else (ev - tr) / 1000.0
        val tko = got.filterIsInstance<PhaseEvent.Takeoff>().firstOrNull()
        val toc = got.filterIsInstance<PhaseEvent.TopOfClimb>().firstOrNull()
        val tod = got.filterIsInstance<PhaseEvent.TopOfDescent>().firstOrNull()
        val lnd = got.filterIsInstance<PhaseEvent.Landing>().firstOrNull()
        val roll = got.filterIsInstance<PhaseEvent.TakeoffRoll>().firstOrNull()
        val e1 = dt(tko?.eventMillis, truth.liftoffMillis)
        val e2 = dt(toc?.eventMillis, truth.tocMillis)
        val e3 = dt(tod?.eventMillis, truth.todMillis)
        val e4 = dt(lnd?.eventMillis, truth.landingMillis)
        val axisErr = if (roll == null) Double.NaN else Vec.angleDeg(roll.axis, truth.noseDevice)
        out.add(Check("Phase detection on a simulated flight",
            abs(e1) <= 60 && abs(e2) <= 150 && abs(e3) <= 150 && abs(e4) <= 120 && axisErr <= 10,
            "time errors: takeoff %.0f s, top of climb %.0f s, top of descent %.0f s, landing %.0f s; nose axis %.1f deg (limits 60/150/150/120 s, 10 deg)"
                .format(e1, e2, e3, e4, axisErr)))

        // 2. heading from the phone's orientation, aligned on the runway
        val rp = SensorSim.randomRotation(Rng(5))
        val nose = doubleArrayOf(rp[0], rp[1], rp[2])
        fun worldR(trueHdg: Double, decl: Double, compassBias: Double): DoubleArray {
            val m = (trueHdg - decl + compassBias) * D2R
            val rwb = doubleArrayOf(sin(m), -cos(m), 0.0, cos(m), sin(m), 0.0, 0.0, 0.0, 1.0)
            val r = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) {
                var s = 0.0
                for (k in 0..2) s += rwb[i * 3 + k] * rp[k * 3 + j]
                r[i * 3 + j] = s
            }
            return r
        }
        val ht = HeadingTracker()
        val rw = ht.alignFromRoll(nose, worldR(165.0, 5.4, -4.0), 5.4, listOf(165.0, 345.0))
        val h2 = ht.trueHeading(worldR(300.0, 5.1, -4.0), 5.1) ?: Double.NaN
        out.add(Check("Heading from the phone, aligned on the runway", rw == 165.0 && abs(Geo.angDiff(h2, 300.0)) < 0.5,
            "runway %s, heading after a turn %.2f deg (expected 300.00)".format(rw?.toString() ?: "none", h2)))

        return out
    }
}
