package inflight.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic phone sensor streams for a simulated flight: cabin pressure (1 Hz) and raw accelerometer readings
 * with the phone lying at a random orientation. Used to test PhaseDetector and the whole pipeline.
 *
 * Model: the cabin climbs in proportion to the aircraft to a cabin altitude of about 1.9 km, holds it in cruise and
 * descends in proportion from the top of descent; the cabin is slightly pressurised during the takeoff roll; the
 * taxi has starts, stops and turns; the climb has a nose-up attitude; cruise has light turbulence and one rougher
 * patch; the landing roll decelerates at about 0.24 g. Optionally the passenger picks the phone up mid-flight and
 * puts it down differently.
 */
object SensorSim {
    class Truth(
        val rollStartMillis: Long, val liftoffMillis: Long, val tocMillis: Long?, val todMillis: Long,
        val landingMillis: Long, val noseDevice: DoubleArray, val movedAtMillis: Long?,
    )

    private const val G = 9.80665

    /** ISA pressure in hPa at an altitude in metres. */
    fun isaPressure(altM: Double): Double = 1013.25 * (1.0 - 2.25577e-5 * altM).pow(5.25588)

    fun run(
        sim: SimFlight, sc: Scenario, rng: Rng, liftoffMillis: Long,
        accelHz: Double = 25.0, taxiOutS: Double = 600.0, taxiInS: Double = 240.0,
        handleAtS: Double? = null,
        onPressure: (Long, Double) -> Unit,
        onAccel: (Long, Double, Double, Double) -> Unit,
    ): Truth {
        val pf = sc.perf
        val rollDur = rng.uniform(30.0, 42.0)
        val qO = rng.normal(0.0, 6.0)
        val qD = rng.normal(0.0, 6.0)
        val cabinMax = rng.normal(1.9, 0.15).coerceIn(1.5, 2.4)
        val fluctPhase = rng.uniform(0.0, 2 * PI)
        val tToc = sim.tToc
        val tTod = sim.tTod
        val iTod = sim.indexAt(tTod)
        val altTod = sim.altKm[iTod]
        val cabinTod = cabinMax
        val tEnd = sim.tLand + taxiInS

        fun cabinPressure(t: Double): Double {
            if (t < 0.0) {
                val pre = if (t > -rollDur) 1.2 * ((t + rollDur) / 10.0).coerceIn(0.0, 1.0) else 0.0
                return isaPressure(sc.oElevKm * 1000) + qO + pre
            }
            if (t >= sim.tLand) return isaPressure(sc.dElevKm * 1000) + qD
            val alt = sim.altKm[sim.indexAt(t)]
            val pre = 1.2 * (1.0 - t / 20.0).coerceIn(0.0, 1.0)
            return if (t < tTod) {
                val fr = ((alt - sc.oElevKm) / (pf.cruiseAlt - sc.oElevKm)).coerceIn(0.0, 1.0)
                val cabin = sc.oElevKm + (cabinMax - sc.oElevKm) * fr
                val fluct = if (fr >= 1.0) 0.15 * sin(2 * PI * t / 180.0 + fluctPhase) else 0.0
                isaPressure(cabin * 1000) + qO * (1.0 - fr) + pre + fluct
            } else {
                val fr = ((alt - sc.dElevKm) / (altTod - sc.dElevKm)).coerceIn(0.0, 1.0)
                val cabin = sc.dElevKm + (cabinTod - sc.dElevKm) * fr
                isaPressure(cabin * 1000) + qD * (1.0 - fr)
            }
        }

        // phone orientation: device -> body rotation (body x forward, y left, z up)
        var rp = randomRotation(rng)
        val nose = doubleArrayOf(rp[0], rp[1], rp[2])   // body x axis in device coordinates (first row)
        var movedAt: Long? = null

        // taxi manoeuvres: (start, end, ax, ay)
        val taxi = ArrayList<DoubleArray>()
        fun planTaxi(from: Double, to: Double) {
            var t = from
            while (t < to - 20.0) {
                val kind = rng.uniform()
                val dur = if (kind < 0.4) 5.0 else if (kind < 0.7) 4.0 else 10.0
                val ax = if (kind < 0.4) 1.0 else if (kind < 0.7) -1.2 else 0.0
                val ay = if (kind >= 0.7) (if (rng.uniform() < 0.5) 1.0 else -1.0) else 0.0
                taxi.add(doubleArrayOf(t, t + dur, ax, ay))
                t += dur + rng.uniform(20.0, 50.0)
            }
        }
        planTaxi(-taxiOutS + 240.0, -rollDur - 60.0)   // the first four minutes at the gate are still
        planTaxi(sim.tLand + 40.0, tEnd)
        val tCruiseStart = tToc ?: 1200.0
        val turbStart = rng.uniform(tCruiseStart + 300.0, maxOf(tCruiseStart + 301.0, tTod - 600.0))

        fun bodyForce(t: Double, out: DoubleArray): Double {   // returns vibration sd
            var ax = 0.0
            var ay = 0.0
            var pitch = 0.0
            var vib: Double
            when {
                t < -rollDur - 60.0 -> {
                    vib = if (t > -taxiOutS + 240.0) 0.25 else 0.02
                    for (m in taxi) if (t >= m[0] && t < m[1]) {
                        ax = m[2]
                        ay = m[3]
                    }
                }
                t < -rollDur -> vib = 0.03
                t < 0.0 -> {
                    ax = 2.5 + 0.3 * (t + rollDur) / rollDur
                    vib = 0.35
                }
                t < sim.tLand -> {
                    val climbEnd = tToc ?: tTod
                    pitch = when {
                        t < 6.0 -> 13.0 * t / 6.0
                        t < 90.0 -> 13.0
                        t < climbEnd -> 13.0 + (4.0 - 13.0) * (t - 90.0) / (climbEnd - 90.0)
                        t < tTod -> 2.5
                        t < sim.tLand - 240.0 -> -1.0
                        else -> 3.0 * (1.0 - ((t - (sim.tLand - 5.0)) / 5.0).coerceIn(0.0, 1.0))
                    }
                    ax = if (t < 60.0) 0.8 else if (t < climbEnd) 0.3 else 0.0
                    vib = if (t > turbStart && t < turbStart + 180.0) 0.6 else 0.15
                }
                t < sim.tLand + 22.0 -> {
                    ax = -2.3
                    vib = 0.45
                }
                else -> {
                    vib = 0.25
                    for (m in taxi) if (t >= m[0] && t < m[1]) {
                        ax = m[2]
                        ay = m[3]
                    }
                }
            }
            val th = pitch * D2R
            out[0] = ax + G * sin(th)
            out[1] = ay
            out[2] = G * cos(th)
            return vib
        }

        val dtA = 1.0 / accelHz
        val fb = DoubleArray(3)
        var t = -taxiOutS
        var nextP = -taxiOutS
        val handleEnd = handleAtS?.plus(60.0)
        while (t < tEnd) {
            val ms = liftoffMillis + (t * 1000).toLong()
            if (t >= nextP) {
                onPressure(ms, cabinPressure(t) + rng.normal(0.0, 0.03))
                nextP += 1.0
            }
            val vib = bodyForce(t, fb)
            for (k in 0..2) fb[k] += rng.normal(0.0, if (k == 2) vib else vib * 0.6)
            val handlingNow = handleAtS != null && t >= handleAtS && t < handleEnd!!
            if (handlingNow) for (k in 0..2) fb[k] += rng.normal(0.0, 2.5)
            if (handleEnd != null && movedAt == null && t >= handleEnd) {
                rp = randomRotation(rng)
                movedAt = ms
            }
            // device = rp^T * body
            val dx = rp[0] * fb[0] + rp[3] * fb[1] + rp[6] * fb[2]
            val dy = rp[1] * fb[0] + rp[4] * fb[1] + rp[7] * fb[2]
            val dz = rp[2] * fb[0] + rp[5] * fb[1] + rp[8] * fb[2]
            onAccel(ms, dx + rng.normal(0.0, 0.03), dy + rng.normal(0.0, 0.03), dz + rng.normal(0.0, 0.03))
            t += dtA
        }
        return Truth(
            liftoffMillis - (rollDur * 1000).toLong(), liftoffMillis,
            tToc?.let { liftoffMillis + (it * 1000).toLong() }, liftoffMillis + (tTod * 1000).toLong(),
            liftoffMillis + (sim.tLand * 1000).toLong(), nose, movedAt,
        )
    }

    /** Uniformly random rotation matrix, row-major. */
    fun randomRotation(rng: Rng): DoubleArray {
        var w = rng.normal()
        var x = rng.normal()
        var y = rng.normal()
        var z = rng.normal()
        val n = sqrt(w * w + x * x + y * y + z * z)
        w /= n; x /= n; y /= n; z /= n
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }
}
