Step 4 of 7. Add sensor processing to the engine and a sensor check to Diagnostics.

## 1. Engine files

Create the 3 files below next to the other engine files (folder `inflight/engine/`, `package inflight.engine`). Copy each one exactly; do not change the earlier engine files.

- `inflight/engine/Phase.kt` (678 lines)
- `inflight/engine/SensorSim.kt` (190 lines)
- `inflight/engine/SelfTestSensors.kt` (60 lines)

What they do, so you can wire them up in the next step:

- `PhaseDetector(hasBarometer, elevChangeM)` takes raw barometer samples (`onPressure(timeMillis, hPa)`) and raw accelerometer samples (`onAccel(timeMillis, x, y, z)`, gravity included) and returns `PhaseEvent`s: `TakeoffRoll`, `Takeoff`, `TopOfClimb`, `TopOfDescent`, `Landing`, `PhoneMoved`. Its `handling` flag is true while the phone is being moved, and `pressureTrend` is the cabin-pressure trend in hPa per minute.
- `HeadingTracker` turns the phone's rotation matrix into the aircraft's true heading after an alignment (from the takeoff roll, or manual).
- `MagMath.fieldAndDip()` and `MagCalibration` turn magnetometer readings into gate-calibrated field strength and dip angle.
- `SensorSim` produces synthetic sensor streams for the self-test.

## 2. Diagnostics

Add a third card, "Sensor processing", whose "Run checks" calls `SelfTestSensors.run()` on a background dispatcher (2 checks). Include it in "Run all".

Add a fourth card, "Sensors on this phone", that lists, with a check or a cross: barometer (`Sensor.TYPE_PRESSURE`), accelerometer, gyroscope, rotation vector, uncalibrated magnetometer (`Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED`), and GPS (`LocationManager.GPS_PROVIDER` present). While the card is visible, show live values: pressure in hPa with two decimals, the cabin-pressure trend from a `PhaseDetector(hasBarometer = true)` fed with the pressure and accelerometer samples (hPa/min, one decimal, "-" until it has a minute of data), the accelerometer magnitude in m/s², and "Phone moving" when the detector's `handling` is true. Register the listeners in onResume-equivalent lifecycle and unregister when the card leaves the screen. Convert `SensorEvent.timestamp` to wall-clock milliseconds with `System.currentTimeMillis() + (event.timestamp - SystemClock.elapsedRealtimeNanos()) / 1_000_000`.

## Check

In the emulator, Run all: 34 + 5 + 2 = 41 checks pass. "Sensors on this phone" shows crosses for what the emulator lacks without crashing. On a real phone with a barometer, the pressure updates every second and the trend settles near 0.0 hPa/min on a desk.

## Engine files to copy exactly

### File: inflight/engine/Phase.kt

```kotlin
package inflight.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

enum class FlightPhase { GROUND, CLIMB, CRUISE, DESCENT, LANDED }

/** Something the phase detector noticed. Times are Unix milliseconds. */
sealed class PhaseEvent {
    /** When the detector became sure. */
    abstract val detectedMillis: Long

    /** Liftoff, estimated to have happened at eventMillis. */
    data class Takeoff(val eventMillis: Long, override val detectedMillis: Long, val source: String) : PhaseEvent()

    /** The cabin stopped climbing (top of climb) at eventMillis. */
    data class TopOfClimb(val eventMillis: Long, override val detectedMillis: Long) : PhaseEvent()

    /** The cabin started descending (top of descent) at eventMillis. */
    data class TopOfDescent(val eventMillis: Long, override val detectedMillis: Long) : PhaseEvent()

    /** Touchdown, estimated to have happened at eventMillis. */
    data class Landing(val eventMillis: Long, override val detectedMillis: Long, val source: String) : PhaseEvent()

    /**
     * The takeoff roll, found by the accelerometer. axis is a unit vector in device coordinates that points to the
     * aircraft's nose; pass it to HeadingTracker.alignFromRoll().
     */
    class TakeoffRoll(val startMillis: Long, override val detectedMillis: Long, val axis: DoubleArray) : PhaseEvent()

    /** The phone was moved relative to the aircraft, so any heading alignment is no longer valid. */
    data class PhoneMoved(override val detectedMillis: Long) : PhaseEvent()
}

/** Tuning values for PhaseDetector. Pressures in hPa, pressure rates in hPa per minute, accelerations in m/s². */
data class PhaseParams(
    val slopeWindowS: Int = 60,        // pressure trend is a straight-line fit over this window
    val climbRate: Double = -3.0,      // cabin climbing: pressure falling faster than this
    val climbHoldS: Int = 40,
    val minClimbDropHpa: Double = 4.0,
    val levelRate: Double = 0.8,       // cabin level: |trend| below this
    val tocHoldS: Int = 90,
    val descentRate: Double = 2.5,     // cabin descending: pressure rising faster than this
    val descentHoldS: Int = 45,
    val minDescentRiseHpa: Double = 4.0,
    val landingHoldS: Int = 90,
    val landingRecovery: Double = 0.85, // share of the climb's pressure drop recovered before a landing counts
    val minClimbS: Int = 300,          // no top of climb in the first five minutes
    val rollMinAccel: Double = 1.2,    // takeoff and landing rolls: steady horizontal push between these
    val rollMaxAccel: Double = 5.0,
    val rollMinS: Double = 20.0,
    val rollToLiftoffS: Double = 35.0, // typical takeoff roll of an A320-family aircraft
    val rollMaxTurnDeg: Double = 30.0,
    val landingRollMinS: Double = 8.0,
    val handlingJitter: Double = 1.0,  // phone in a hand or a pocket while walking
    val quietJitter: Double = 0.2,
    val movedAngleDeg: Double = 4.0,   // gravity direction change, in device coordinates, that means the phone moved
)

/**
 * Finds takeoff, top of climb, top of descent and landing from the phone's barometer (cabin pressure) and
 * accelerometer. Feed it raw samples; it returns events once it is sure. Event times are estimates of when the
 * event happened; the barometer confirms each event about a minute later.
 *
 * Cabin pressure falls while the cabin climbs after liftoff, stays level in cruise, rises from the top of descent
 * and levels off again on the ground. The accelerometer finds the takeoff roll (a steady push of about 0.25 g for
 * half a minute), the landing roll, and phone handling. Without a barometer only takeoff and landing are found.
 *
 * Sample rates: barometer about 1 Hz, accelerometer 25 Hz or more (raw TYPE_ACCELEROMETER, gravity included).
 * Not thread-safe: call it from one thread.
 *
 * elevChangeM is the destination elevation minus the origin elevation (0 if unknown); it helps confirm landings.
 */
class PhaseDetector(
    val hasBarometer: Boolean,
    private val elevChangeM: Double = 0.0,
    private val prm: PhaseParams = PhaseParams(),
) {
    var phase: FlightPhase = FlightPhase.GROUND
        private set
    var takeoffMillis: Long? = null
        private set
    var landingMillis: Long? = null
        private set

    /** Unit vector in device coordinates toward the aircraft's nose; null when unknown or after the phone moved. */
    var noseAxis: DoubleArray? = null
        private set

    /** True while the phone is being handled. */
    var handling: Boolean = false
        private set

    /** Latest cabin pressure trend, hPa per minute (NaN until there is enough data). */
    var pressureTrend: Double = Double.NaN
        private set

    // ---- barometer state ----
    private val pt = ArrayList<Long>()      // one-second bins: time in seconds
    private val pv = ArrayList<Double>()    // mean pressure in the bin
    private var pBin = Long.MIN_VALUE
    private var pSum = 0.0
    private var pN = 0
    private var runLevel = 0
    private var runDown = 0
    private var runUp = 0
    private var groundP = Double.NaN
    private var minP = Double.NaN
    private var lastP = Double.NaN

    /**
     * After an app restart mid-flight: continue in the given phase instead of waiting for a takeoff.
     * groundHpa is the pressure at the origin (SensorSim.isaPressure(origin elevation in m) is close enough).
     */
    fun resumeAirborne(takeoffMillis: Long, phase: FlightPhase, groundHpa: Double) {
        this.takeoffMillis = takeoffMillis
        this.phase = phase
        groundP = groundHpa
        minP = Double.NaN
        pendingLiftoff = null
        resetRuns()
    }

    fun onPressure(tMillis: Long, hPa: Double): List<PhaseEvent> {
        val out = ArrayList<PhaseEvent>()
        if (!hasBarometer || hPa.isNaN() || hPa <= 0.0) return out
        val s = Math.floorDiv(tMillis, 1000L)
        if (pN > 0 && s != pBin) {
            addPressure(pBin, pSum / pN, out)
            pN = 0
            pSum = 0.0
        }
        if (pN == 0) pBin = s
        pSum += hPa
        pN++
        return out
    }

    private fun addPressure(sec: Long, p: Double, out: MutableList<PhaseEvent>) {
        pt.add(sec)
        pv.add(p)
        lastP = p
        while (pt.size > 1 && pt[0] < sec - 900) {
            pt.removeAt(0)
            pv.removeAt(0)
        }
        val sl = slopePerMin(sec, prm.slopeWindowS) ?: return
        pressureTrend = sl
        if (phase != FlightPhase.GROUND) minP = if (minP.isNaN()) p else minOf(minP, p)
        when (phase) {
            FlightPhase.GROUND -> {
                runDown = if (sl < prm.climbRate) runDown + 1 else 0
                if (runDown >= prm.climbHoldS && change(sec, 120) <= -prm.minClimbDropHpa) {
                    val tk = hinge(sec - 300, sec)
                    groundP = meanBetween(tk - 150, tk - 10) ?: pv[0]
                    minP = p
                    val rollLift = pendingLiftoff
                    val useRoll = rollLift != null && abs(rollLift - tk * 1000) <= 180_000L
                    phase = FlightPhase.CLIMB
                    resetRuns()
                    takeoff(if (useRoll) rollLift!! else tk * 1000, sec * 1000, if (useRoll) "accelerometer+barometer" else "barometer", out)
                }
            }
            FlightPhase.CLIMB -> {
                runLevel = if (abs(sl) < prm.levelRate) runLevel + 1 else 0
                runUp = if (sl > prm.descentRate) runUp + 1 else 0
                val since = sec - (takeoffMillis ?: sec * 1000) / 1000
                if (runLevel >= prm.tocHoldS && since >= prm.minClimbS) {
                    val tk = hinge(sec - 300, sec)
                    phase = FlightPhase.CRUISE
                    resetRuns()
                    out.add(PhaseEvent.TopOfClimb(tk * 1000, sec * 1000))
                } else if (runUp >= prm.descentHoldS && since >= prm.minClimbS && change(sec, 120) >= prm.minDescentRiseHpa) {
                    // a short flight: the cabin went from climb straight into descent
                    val tk = hinge(sec - 300, sec)
                    phase = FlightPhase.DESCENT
                    resetRuns()
                    out.add(PhaseEvent.TopOfClimb(tk * 1000, sec * 1000))
                    out.add(PhaseEvent.TopOfDescent(tk * 1000, sec * 1000))
                }
            }
            FlightPhase.CRUISE -> {
                runUp = if (sl > prm.descentRate) runUp + 1 else 0
                if (runUp >= prm.descentHoldS && change(sec, 120) >= prm.minDescentRiseHpa) {
                    val tk = hinge(sec - 300, sec)
                    phase = FlightPhase.DESCENT
                    resetRuns()
                    out.add(PhaseEvent.TopOfDescent(tk * 1000, sec * 1000))
                }
            }
            FlightPhase.DESCENT -> {
                runLevel = if (abs(sl) < prm.levelRate) runLevel + 1 else 0
                if (runLevel >= prm.landingHoldS && recovered() >= prm.landingRecovery) {
                    land(hinge(sec - 300, sec) * 1000, sec * 1000, "barometer", out)
                }
            }
            FlightPhase.LANDED -> {}
        }
    }

    /** Share of the climb's pressure drop that has come back (1 = back at the expected landing pressure). */
    private fun recovered(): Double {
        if (groundP.isNaN() || minP.isNaN() || lastP.isNaN()) return 0.0
        val expectLand = groundP - 0.12 * elevChangeM
        val span = expectLand - minP
        return if (span > 5.0) (lastP - minP) / span else 1.0
    }

    private fun resetRuns() {
        runLevel = 0
        runDown = 0
        runUp = 0
    }

    private fun slopePerMin(now: Long, window: Int): Double? {
        var n = 0
        var st = 0.0
        var sp = 0.0
        var stt = 0.0
        var stp = 0.0
        val p0 = pv[pv.size - 1]
        for (i in pt.indices.reversed()) {
            val dt = (pt[i] - now).toDouble()
            if (dt < -window) break
            val dp = pv[i] - p0
            n++
            st += dt
            sp += dp
            stt += dt * dt
            stp += dt * dp
        }
        if (n < window / 2) return null
        val den = n * stt - st * st
        if (den <= 0.0) return null
        return (n * stp - st * sp) / den * 60.0
    }

    private fun meanBetween(a: Long, b: Long): Double? {
        var s = 0.0
        var n = 0
        for (i in pt.indices) {
            if (pt[i] in a..b) {
                s += pv[i]
                n++
            }
        }
        return if (n == 0) null else s / n
    }

    /** Pressure now minus pressure `back` seconds ago (10-second means), hPa. */
    private fun change(now: Long, back: Long): Double {
        val a = meanBetween(now - 10, now) ?: return 0.0
        val b = meanBetween(now - back - 5, now - back + 5) ?: return 0.0
        return a - b
    }

    /**
     * Time of the corner in the pressure curve between from and to (seconds): the best fit of two straight
     * lines that meet at that time.
     */
    private fun hinge(from: Long, to: Long): Long {
        val idx = pt.indices.filter { pt[it] in from..to }
        if (idx.size < 30) return to - prm.slopeWindowS / 2
        val t0 = pt[idx[0]]
        val ts = DoubleArray(idx.size) { (pt[idx[it]] - t0).toDouble() }
        val pm = idx.map { pv[it] }.average()
        val ps = DoubleArray(idx.size) { pv[idx[it]] - pm }
        var bestT = to - prm.slopeWindowS / 2
        var bestSse = Double.MAX_VALUE
        var cand = ts.first() + 15.0
        while (cand <= ts.last() - 15.0) {
            var n = 0.0
            var s1 = 0.0
            var s2 = 0.0
            var s11 = 0.0
            var s22 = 0.0
            var sp = 0.0
            var s1p = 0.0
            var s2p = 0.0
            for (k in ts.indices) {
                val u = ts[k] - cand
                val u1 = if (u < 0) u else 0.0
                val u2 = if (u > 0) u else 0.0
                n += 1.0
                s1 += u1
                s2 += u2
                s11 += u1 * u1
                s22 += u2 * u2
                sp += ps[k]
                s1p += u1 * ps[k]
                s2p += u2 * ps[k]
            }
            val sol = solve3(
                doubleArrayOf(n, s1, s2, s1, s11, 0.0, s2, 0.0, s22),
                doubleArrayOf(sp, s1p, s2p),
            )
            if (sol != null) {
                var sse = 0.0
                for (k in ts.indices) {
                    val u = ts[k] - cand
                    val fit = sol[0] + sol[1] * (if (u < 0) u else 0.0) + sol[2] * (if (u > 0) u else 0.0)
                    val r = ps[k] - fit
                    sse += r * r
                }
                if (sse < bestSse) {
                    bestSse = sse
                    bestT = t0 + Math.round(cand)
                }
            }
            cand += 2.0
        }
        return bestT
    }

    // ---- accelerometer state ----
    private var aBin = Long.MIN_VALUE
    private val aSum = DoubleArray(3)
    private var aN = 0
    private var aMagSum = 0.0
    private var aMag2 = 0.0
    private val mPrev = DoubleArray(3)
    private var havePrev = false
    private val gRef = DoubleArray(3)
    private var gRefValid = false
    private var loudBins = 0
    private var quietBins = 0
    private var tiltBins = 0
    private val gBefore = DoubleArray(3)
    private var runStart = 0L
    private var runCount = 0
    private var runMiss = 0
    private val runSum = DoubleArray(3)
    private var pendingLiftoff: Long? = null
    private var landArmed = false
    private var landRunStart = 0L

    fun onAccel(tMillis: Long, x: Double, y: Double, z: Double): List<PhaseEvent> {
        val out = ArrayList<PhaseEvent>()
        val b = Math.floorDiv(tMillis, 200L)
        if (aN > 0 && b != aBin) {
            closeAccel(aBin * 200 + 100, out)
            aN = 0
            aSum.fill(0.0)
            aMagSum = 0.0
            aMag2 = 0.0
        }
        if (aN == 0) aBin = b
        aSum[0] += x
        aSum[1] += y
        aSum[2] += z
        val m = sqrt(x * x + y * y + z * z)
        aMagSum += m
        aMag2 += m * m
        aN++
        return out
    }

    private fun closeAccel(tMs: Long, out: MutableList<PhaseEvent>) {
        val m = doubleArrayOf(aSum[0] / aN, aSum[1] / aN, aSum[2] / aN)
        val mean = aMagSum / aN
        val within = maxOf(0.0, aMag2 / aN - mean * mean)
        val dPrev = if (havePrev) Vec.dist(m, mPrev) else 0.0
        m.copyInto(mPrev)
        havePrev = true
        val jitter = sqrt(within + dPrev * dPrev / 2.0)
        if (!gRefValid) {
            if (jitter < prm.quietJitter) {
                m.copyInto(gRef)
                gRefValid = true
            }
            return
        }
        // ---- phone handling ----
        if (!handling) {
            loudBins = if (jitter > prm.handlingJitter) loudBins + 1 else 0
            // in barometric cruise the attitude barely changes, so a large tilt means the phone was moved
            tiltBins = if (hasBarometer && phase == FlightPhase.CRUISE && Vec.angleDeg(m, gRef) > 10.0) tiltBins + 1 else 0
            if (loudBins >= 5 || tiltBins >= 15) {
                handling = true
                quietBins = 0
                gRef.copyInto(gBefore)
                runCount = 0
                landArmed = false
            }
        } else {
            quietBins = if (jitter < prm.quietJitter) quietBins + 1 else 0
            if (quietBins >= 25) {
                handling = false
                loudBins = 0
                tiltBins = 0
                if (Vec.angleDeg(m, gBefore) > prm.movedAngleDeg) {
                    noseAxis = null
                    out.add(PhaseEvent.PhoneMoved(tMs))
                }
                m.copyInto(gRef)
            }
            return
        }
        // ---- slow update of the gravity reference, then the horizontal push ----
        val dev = doubleArrayOf(m[0] - gRef[0], m[1] - gRef[1], m[2] - gRef[2])
        if (takeoffMillis == null) {
            // on the ground: follow only small, quiet changes, so a takeoff roll is never absorbed
            if (jitter < prm.quietJitter && Vec.norm(dev) < 0.5) for (k in 0..2) gRef[k] += 0.02 * dev[k]
        } else if (jitter < 0.8 && runCount == 0 && !landArmed) {
            // in flight: follow attitude changes (climb, cruise, descent) with a one-minute time constant
            for (k in 0..2) gRef[k] += dev[k] / 300.0
        }
        val gn = Vec.norm(gRef)
        val along = Vec.dot(dev, gRef) / gn
        val h = doubleArrayOf(dev[0] - along * gRef[0] / gn, dev[1] - along * gRef[1] / gn, dev[2] - along * gRef[2] / gn)
        val mag = Vec.norm(h)
        val tk = takeoffMillis
        if (tk == null && phase == FlightPhase.GROUND) {
            rollCheck(tMs, h, mag, jitter, out)
        } else if (tk != null && phase != FlightPhase.LANDED && tMs > tk + 600_000L &&
            (!hasBarometer || phase == FlightPhase.DESCENT || (phase == FlightPhase.CRUISE && recovered() >= 0.5))
        ) {
            landingCheck(tMs, h, mag, jitter, out)
        }
        // takeoff from the accelerometer alone, once the roll has had time to end
        val pl = pendingLiftoff
        if (pl != null && takeoffMillis == null) {
            if (!hasBarometer && tMs >= pl) {
                phase = FlightPhase.CRUISE
                takeoff(pl, tMs, "accelerometer", out)
            } else if (hasBarometer && tMs >= pl + 300_000L && phase == FlightPhase.GROUND) {
                // the barometer never saw a climb: trust the roll
                groundP = meanBetween(pl / 1000 - 150, pl / 1000 - 10) ?: lastP
                minP = lastP
                phase = FlightPhase.CLIMB
                resetRuns()
                takeoff(pl, tMs, "accelerometer", out)
            }
        }
    }

    private fun rollCheck(tMs: Long, h: DoubleArray, mag: Double, jitter: Double, out: MutableList<PhaseEvent>) {
        if (pendingLiftoff != null) return
        if (mag >= prm.rollMinAccel && mag <= prm.rollMaxAccel && jitter < 1.5) {
            if (runCount > 0 && Vec.angleDeg(h, runSum) > prm.rollMaxTurnDeg) runCount = 0
            if (runCount == 0) {
                runStart = tMs
                runSum.fill(0.0)
            }
            runCount++
            runMiss = 0
            for (k in 0..2) runSum[k] += h[k]
            if (runCount * 0.2 >= prm.rollMinS) {
                val axis = Vec.unit(runSum)
                noseAxis = axis
                pendingLiftoff = runStart + (prm.rollToLiftoffS * 1000).toLong()
                out.add(PhaseEvent.TakeoffRoll(runStart, tMs, axis))
                runCount = 0
            }
        } else if (runCount > 0 && ++runMiss > 3) {
            runCount = 0
            runMiss = 0
        }
    }

    private fun landingCheck(tMs: Long, h: DoubleArray, mag: Double, jitter: Double, out: MutableList<PhaseEvent>) {
        if (landArmed) {
            // a landing roll ends within a minute or so; a lasting push is an attitude change instead
            if (mag < 0.6) {
                land(landRunStart - 2000, tMs, "accelerometer", out)
                landArmed = false
            } else if (tMs - landRunStart > 90_000L) {
                landArmed = false
            }
            return
        }
        if (mag >= prm.rollMinAccel && mag <= prm.rollMaxAccel && jitter < 2.0) {
            val axis = noseAxis
            if (axis != null && Vec.dot(h, axis) / mag > -0.5) {  // must push toward the tail
                runCount = 0
                return
            }
            if (runCount > 0 && Vec.angleDeg(h, runSum) > prm.rollMaxTurnDeg) runCount = 0
            if (runCount == 0) {
                runStart = tMs
                runSum.fill(0.0)
            }
            runCount++
            runMiss = 0
            for (k in 0..2) runSum[k] += h[k]
            if (runCount * 0.2 >= prm.landingRollMinS) {
                landArmed = true
                landRunStart = runStart
                runCount = 0
            }
        } else if (runCount > 0 && ++runMiss > 3) {
            runCount = 0
            runMiss = 0
        }
    }

    private fun takeoff(liftMs: Long, detMs: Long, src: String, out: MutableList<PhaseEvent>) {
        if (takeoffMillis != null) return
        takeoffMillis = liftMs
        pendingLiftoff = null
        out.add(PhaseEvent.Takeoff(liftMs, detMs, src))
    }

    private fun land(eventMs: Long, detMs: Long, src: String, out: MutableList<PhaseEvent>) {
        if (phase == FlightPhase.LANDED) return
        phase = FlightPhase.LANDED
        landingMillis = eventMs
        out.add(PhaseEvent.Landing(eventMs, detMs, src))
    }
}

/**
 * Turns the phone's orientation into the aircraft's true heading. Alignment comes from the takeoff roll
 * (automatic) or from the user placing the phone flat with its top edge toward the cockpit (manual). After that
 * the phone must stay where it is; call invalidate() when PhaseDetector reports PhoneMoved.
 *
 * r is the device-to-world rotation matrix (x east, y north, z up), row-major, as filled by Android's
 * SensorManager.getRotationMatrixFromVector() from TYPE_ROTATION_VECTOR. Its north is magnetic north, so pass the
 * declination (Wmm2025.declination at the current estimate) to get true headings.
 */
class HeadingTracker {
    private var axis: DoubleArray? = null

    /** Correction learned from the runway: true runway heading minus the compass reading of the roll. */
    var offsetDeg: Double = 0.0
        private set

    /** Increases with every new alignment; logged with each heading so the filter knows when the error changed. */
    var alignmentId: Int = 0
        private set

    var source: String = "none"
        private set

    val aligned: Boolean get() = axis != null

    /**
     * Aligns with the takeoff roll. The runway direction nearest to the measured one is taken as the truth;
     * returns that runway heading, or null when no runway was within 35 degrees (then the compass is trusted).
     */
    fun alignFromRoll(axisDevice: DoubleArray, r: DoubleArray, declinationDeg: Double, runwayHeadingsTrue: List<Double>): Double? {
        val h = axisHeading(r, axisDevice)
        if (h.isNaN()) return null
        val measured = Geo.mod360(h + declinationDeg)
        val best = runwayHeadingsTrue.minByOrNull { abs(Geo.angDiff(it, measured)) }
        val ok = best != null && abs(Geo.angDiff(best, measured)) <= 35.0
        offsetDeg = if (ok) Geo.angDiff(best!!, measured) else 0.0
        axis = Vec.unit(axisDevice)
        alignmentId++
        source = "takeoff roll"
        return if (ok) best else null
    }

    /** Manual alignment: axisDevice points to the nose. For a phone lying flat with its top toward the cockpit: (0, 1, 0). */
    fun alignManual(axisDevice: DoubleArray = doubleArrayOf(0.0, 1.0, 0.0)) {
        axis = Vec.unit(axisDevice)
        offsetDeg = 0.0
        alignmentId++
        source = "manual"
    }

    fun invalidate() {
        axis = null
        source = "none"
    }

    /** True heading of the aircraft in degrees, or null when not aligned or the nose axis is close to vertical. */
    fun trueHeading(r: DoubleArray, declinationDeg: Double): Double? {
        val a = axis ?: return null
        val h = axisHeading(r, a)
        if (h.isNaN()) return null
        return Geo.mod360(h + declinationDeg + offsetDeg)
    }

    companion object {
        /** Compass heading (degrees from the world frame's north) of a device axis; NaN when it points almost straight up or down. */
        fun axisHeading(r: DoubleArray, a: DoubleArray): Double {
            val e = r[0] * a[0] + r[1] * a[1] + r[2] * a[2]
            val n = r[3] * a[0] + r[4] * a[1] + r[5] * a[2]
            if (hypot(e, n) < 0.3 * Vec.norm(a)) return Double.NaN
            return Geo.mod360(atan2(e, n) * R2D)
        }

        fun toDouble(r: FloatArray): DoubleArray = DoubleArray(9) { r[it].toDouble() }
    }
}

/** Magnetic field strength and dip angle from raw phone readings. */
object MagMath {
    /**
     * Field strength in microtesla and dip angle in degrees (positive when the field points down) from a magnetometer
     * reading m and an accelerometer reading g (it points up) taken at the same time with the phone still.
     * For m use TYPE_MAGNETIC_FIELD_UNCALIBRATED values[0..2] minus the bias values[3..5] recorded once at the gate,
     * so the system's own recalibration cannot shift the bias mid-flight. Average over about a minute before use.
     */
    fun fieldAndDip(mx: Double, my: Double, mz: Double, gx: Double, gy: Double, gz: Double): Pair<Double, Double> {
        val f = sqrt(mx * mx + my * my + mz * mz)
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (f == 0.0 || g == 0.0) return Pair(f, Double.NaN)
        val s = -(mx * gx + my * gy + mz * gz) / (f * g)
        return Pair(f, asin(s.coerceIn(-1.0, 1.0)) * R2D)
    }
}

/**
 * Gate calibration of the magnetometer: compares a reading taken in the aircraft before departure with the World
 * Magnetic Model at the origin airport. The differences (the aircraft's own field plus sensor offsets) are then
 * removed from every reading in flight.
 */
data class MagCalibration(val biasF: Double, val biasDip: Double) {
    fun correctF(fMicroTesla: Double) = fMicroTesla - biasF
    fun correctDip(dipDeg: Double) = dipDeg - biasDip

    companion object {
        val NONE = MagCalibration(0.0, 0.0)

        fun atGate(measuredF: Double, measuredDip: Double, airport: Airport, year: Double): MagCalibration {
            val f = Wmm2025(year).field(airport.lat, airport.lon, airport.elevM / 1000.0)
            return MagCalibration(measuredF - f.f / 1000.0, measuredDip - f.dipDeg)
        }
    }
}

/** Small vector helpers on DoubleArray(3). */
internal object Vec {
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun norm(a: DoubleArray) = sqrt(dot(a, a))
    fun dist(a: DoubleArray, b: DoubleArray): Double {
        val x = a[0] - b[0]
        val y = a[1] - b[1]
        val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }

    fun unit(a: DoubleArray): DoubleArray {
        val n = norm(a)
        return if (n == 0.0) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val na = norm(a)
        val nb = norm(b)
        if (na == 0.0 || nb == 0.0) return 0.0
        return acos((dot(a, b) / (na * nb)).coerceIn(-1.0, 1.0)) * R2D
    }
}

/** Solves a 3x3 linear system (row-major matrix); null when singular. */
internal fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
    val m = a.copyOf()
    val v = b.copyOf()
    for (c in 0 until 3) {
        var piv = c
        for (r in c + 1 until 3) if (abs(m[r * 3 + c]) > abs(m[piv * 3 + c])) piv = r
        if (abs(m[piv * 3 + c]) < 1e-12) return null
        if (piv != c) {
            for (k in 0 until 3) {
                val t = m[c * 3 + k]
                m[c * 3 + k] = m[piv * 3 + k]
                m[piv * 3 + k] = t
            }
            val t = v[c]
            v[c] = v[piv]
            v[piv] = t
        }
        for (r in 0 until 3) {
            if (r == c) continue
            val f = m[r * 3 + c] / m[c * 3 + c]
            for (k in c until 3) m[r * 3 + k] -= f * m[c * 3 + k]
            v[r] -= f * v[c]
        }
    }
    return doubleArrayOf(v[0] / m[0], v[1] / m[4], v[2] / m[8])
}
```

### File: inflight/engine/SensorSim.kt

```kotlin
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
```

### File: inflight/engine/SelfTestSensors.kt

```kotlin
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
```
