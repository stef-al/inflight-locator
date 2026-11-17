package inflight.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A position estimate. The aircraft should be inside the circle of radius r95Km around (lat, lon)
 * about 95% of the time.
 */
data class Estimate(
    val lat: Double, val lon: Double, val r95Km: Double,
    val speedKmh: Double, val altKm: Double,
    /** Phase most candidates are in: 0 climb, 1 cruise, 2 descent. */
    val phase: Int,
    /** Seconds since liftoff. */
    val tSec: Double,
)

/**
 * Particle filter shared by both operating modes. Each particle is one candidate flight: its own wind,
 * airspeed, climb time and descent distance. Time runs in 30-second steps from liftoff (t = 0).
 *
 * Call advanceTo() as time passes, the on...() methods when an observation arrives, and estimate() to read
 * the result. Not thread-safe: use it from one thread.
 */
abstract class PositionFilter(
    protected val sc: Scenario,
    protected val mag: MagModel?,
    protected val rng: Rng,
    val n: Int,
    windMean: Double,
    windSd: Double,
    startT: Double,
) {
    protected val pf: Perf = sc.perf
    val dt: Double = 30.0

    /** Seconds since liftoff reached so far. */
    var t: Double = startT
        protected set
    var tocDone: Boolean = false
        protected set
    var todDone: Boolean = false
        protected set

    protected val wind = DoubleArray(n) { rng.normal(windMean, windSd) }
    protected val wdev = DoubleArray(n)
    protected val tas = DoubleArray(n) { rng.normal(pf.tas, pf.tasSd) }
    protected val tc = DoubleArray(n) { maxOf(12.0, rng.normal(pf.climbMin, pf.climbMinSd)) * 60.0 }
    protected val dtod = DoubleArray(n) { maxOf(0.5 * pf.todKm, rng.normal(pf.todKm, pf.todKmSd)) }
    protected val rem0 = DoubleArray(n) { Double.NaN }
    protected val vtod = DoubleArray(n) { Double.NaN }
    protected val altTod = DoubleArray(n) { pf.cruiseAlt }
    protected val vLast = DoubleArray(n) { pf.vLo }
    protected val phase = IntArray(n)
    protected val logw = DoubleArray(n)

    // magnetometer biases: a small Kalman filter per particle (mean per particle, shared variance)
    private val mF = DoubleArray(n)
    private val mI = DoubleArray(n)
    private var pF = pf.bFSd * pf.bFSd
    private var pI = pf.bISd * pf.bISd
    private var tLastMag = startT

    // scratch
    protected val pLat = DoubleArray(n)
    protected val pLon = DoubleArray(n)
    protected val pAlt = DoubleArray(n)
    private val w = DoubleArray(n)
    private val idx = IntArray(n)
    private val tmpD = DoubleArray(n)
    private val tmpI = IntArray(n)
    private val keys = LongArray(n)
    private val q = DoubleArray(2)
    private val baseArrays = arrayOf(wind, wdev, tas, tc, dtod, rem0, vtod, altTod, vLast, mF, mI)

    /** Per-particle arrays owned by the subclass; they are reordered together on resampling. */
    protected abstract fun stateArrays(): Array<DoubleArray>
    protected abstract fun afterResample()
    protected abstract fun step()

    /** Fills pLat, pLon and pAlt with every particle's position at the current time. */
    protected abstract fun fillPositions()

    /**
     * Advances the filter to tSec seconds after liftoff. trueHeadingDeg is the latest compass heading
     * converted to true north; only the unknown-route filter uses it.
     */
    fun advanceTo(tSec: Double, trueHeadingDeg: Double? = null) {
        if (trueHeadingDeg != null && !trueHeadingDeg.isNaN()) onHeading(trueHeadingDeg)
        while (t + dt <= tSec + 1e-6) step()
    }

    protected open fun onHeading(trueHeadingDeg: Double) {}

    /** Latest true heading of the aircraft, degrees. Only the unknown-route filter uses it. */
    fun setHeading(trueHeadingDeg: Double) {
        if (!trueHeadingDeg.isNaN()) onHeading(trueHeadingDeg)
    }

    /** Top of climb observed (the cabin stopped climbing) at tObs seconds after liftoff. */
    open fun onTopOfClimb(tObs: Double) {
        if (tocDone) return
        for (i in 0 until n) {
            val z = (tc[i] - tObs) / 60.0
            logw[i] += -0.5 * z * z
        }
        tocDone = true
        resampleIfNeeded()
    }

    /** Top of descent observed at tObs seconds after liftoff. May be reported late; pass the event time. */
    abstract fun onTopOfDescent(tObs: Double)

    /**
     * A GPS fix. The estimate jumps to it and the error circle resets.
     * speedKmh and courseDeg (ground speed and course over ground from the same fix) are optional; with them
     * the filter also pins the ground speed (and, with no route, the direction of travel).
     */
    abstract fun onGpsFix(lat: Double, lon: Double, speedKmh: Double? = null, courseDeg: Double? = null)

    /** A magnetometer reading averaged over about a minute: field strength in microtesla and dip angle in degrees. */
    fun onMagnetic(fMicroTesla: Double, dipDeg: Double) {
        val m = mag ?: return
        val grow = maxOf(0.0, t - tLastMag) / 1800.0
        tLastMag = t
        pF += pf.bFRw * pf.bFRw * grow
        pI += pf.bIRw * pf.bIRw * grow
        fillPositions()
        val sF = pF + pf.fNoise * pf.fNoise
        val sI = pI + pf.iNoise * pf.iNoise
        val kF = pF / sF
        val kI = pI / sI
        for (i in 0 until n) {
            m.query(pLat[i], pLon[i], pAlt[i], q)
            val nuF = fMicroTesla - q[0] - mF[i]
            val nuI = dipDeg - q[1] - mI[i]
            logw[i] += -0.5 * nuF * nuF / sF - 0.5 * nuI * nuI / sI
            mF[i] += kF * nuF
            mI[i] += kI * nuI
        }
        pF *= (1.0 - kF)
        pI *= (1.0 - kI)
    }

    /** Particle hits counted on the camera sensor during the last windowS seconds. */
    fun onCosmic(count: Int, windowS: Double) {
        fillPositions()
        for (i in 0 until n) {
            val lam = maxOf(1e-6, Cosmic.ratePerHour(pLat[i], pLon[i], pAlt[i], pf) * windowS / 3600.0)
            logw[i] += count * ln(lam) - lam
        }
    }

    /** Weighted mean position and the radius that holds 95% of the weight. */
    fun estimate(): Estimate {
        fillPositions()
        val ww = normalizedWeights()
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var v = 0.0
        var a = 0.0
        val ph = DoubleArray(3)
        for (i in 0 until n) {
            val p = pLat[i] * D2R
            val l = pLon[i] * D2R
            x += ww[i] * cos(p) * cos(l)
            y += ww[i] * cos(p) * sin(l)
            z += ww[i] * sin(p)
            v += ww[i] * vLast[i]
            a += ww[i] * pAlt[i]
            ph[phase[i]] += ww[i]
        }
        val mLat = atan2(z, hypot(x, y)) * R2D
        val mLon = atan2(y, x) * R2D
        for (i in 0 until n) {
            val d = Geo.havKm(pLat[i], pLon[i], mLat, mLon)
            tmpD[i] = d
            keys[i] = (d.toFloat().toRawBits().toLong() shl 32) or i.toLong()
        }
        keys.sort()
        var r95 = tmpD[(keys[n - 1] and 0xFFFFFFFFL).toInt()]
        var cw = 0.0
        for (k in 0 until n) {
            val i = (keys[k] and 0xFFFFFFFFL).toInt()
            cw += ww[i]
            if (cw >= 0.95) {
                r95 = tmpD[i]
                break
            }
        }
        val major = if (ph[0] >= ph[1] && ph[0] >= ph[2]) 0 else if (ph[1] >= ph[2]) 1 else 2
        return Estimate(mLat, mLon, r95, v, a, major, t)
    }

    /** A thinned copy of the particle cloud for drawing: (lat, lon) pairs. */
    fun cloud(maxPoints: Int = 200): List<LatLon> {
        fillPositions()
        val stride = maxOf(1, n / maxPoints)
        return (0 until n step stride).map { LatLon(pLat[it], pLon[it]) }
    }

    // ---- shared machinery -------------------------------------------------------------------------

    protected fun normalizedWeights(): DoubleArray {
        var mx = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            if (logw[i].isNaN()) logw[i] = Double.NEGATIVE_INFINITY
            if (logw[i] > mx) mx = logw[i]
        }
        if (mx == Double.NEGATIVE_INFINITY) {
            logw.fill(0.0)
            mx = 0.0
        }
        var sum = 0.0
        for (i in 0 until n) {
            val e = exp(logw[i] - mx)
            w[i] = e
            sum += e
        }
        for (i in 0 until n) w[i] /= sum
        return w
    }

    protected fun effectiveSize(ww: DoubleArray): Double {
        var s2 = 0.0
        for (x in ww) s2 += x * x
        return 1.0 / s2
    }

    /** Systematic resampling when the weights have become uneven (or when forced). */
    protected fun resampleIfNeeded(force: Boolean = false) {
        val ww = normalizedWeights()
        if (!force && effectiveSize(ww) >= 0.5 * n) return
        val u0 = rng.uniform()
        var j = 0
        var cum = ww[0]
        for (i in 0 until n) {
            val u = (u0 + i) / n
            while (u > cum && j < n - 1) {
                j++
                cum += ww[j]
            }
            idx[i] = j
        }
        for (a in baseArrays) permute(a)
        for (a in stateArrays()) permute(a)
        phase.copyInto(tmpI)
        for (i in 0 until n) phase[i] = tmpI[idx[i]]
        logw.fill(0.0)
        for (i in 0 until n) {
            wind[i] += rng.normal(0.0, 2.0)
            tas[i] += rng.normal(0.0, 1.0)
            dtod[i] += rng.normal(0.0, 2.0)
        }
        afterResample()
    }

    private fun permute(a: DoubleArray) {
        a.copyInto(tmpD)
        for (i in 0 until n) a[i] = tmpD[idx[i]]
    }

    protected fun climbSpeed(i: Int, tt: Double): Double {
        val x = (tt / tc[i]).coerceIn(0.0, 1.0)
        val vcr = tas[i] + wind[i] + wdev[i]
        return pf.vLo + (vcr - pf.vLo) * (1.0 - (1.0 - x).pow(pf.climbExp))
    }

    /** Slow random change of the wind along the route. */
    protected fun stepWind(i: Int, v: Double) {
        val ell = pf.windWavelen / (2 * PI)
        val rho = exp(-abs(v) * dt / 3600.0 / ell)
        wdev[i] = wdev[i] * rho + rng.normal() * 0.7 * pf.windVar * sqrt(1.0 - rho * rho)
    }

    protected fun altitudeOf(i: Int, tt: Double, u: Double): Double {
        if (phase[i] == 2) {
            val uu = if (u.isNaN()) 0.0 else u.coerceIn(0.0, 1.0)
            return sc.dElevKm + (altTod[i] - sc.dElevKm) * (1.0 - uu)
        }
        if (phase[i] == 0) {
            val x = (tt / tc[i]).coerceIn(0.0, 1.0)
            return sc.oElevKm + (pf.cruiseAlt - sc.oElevKm) * x.pow(0.8)
        }
        return pf.cruiseAlt
    }

    /** Weights the particles by distance to a fix, widening the tolerance until enough of them survive. */
    protected fun weightByFix(fLat: Double, fLon: Double) {
        fillPositions()
        val d = DoubleArray(n) { Geo.havKm(pLat[it], pLon[it], fLat, fLon) }
        val base = logw.copyOf()
        var sig = 8.0
        for (iter in 0 until 12) {
            for (i in 0 until n) {
                val z = d[i] / sig
                logw[i] = base[i] - 0.5 * z * z
            }
            if (effectiveSize(normalizedWeights()) >= 0.05 * n) break
            sig *= 1.5
        }
        resampleIfNeeded(force = true)
        for (i in 0 until n) wind[i] += rng.normal(0.0, 4.0)
    }

    /** After a fix with a ground speed: candidates in cruise take that speed. */
    protected fun snapSpeed(speedKmh: Double) {
        for (i in 0 until n) {
            if (phase[i] == 1) wind[i] = speedKmh - tas[i] - wdev[i] + rng.normal(0.0, 8.0)
        }
    }
}

/**
 * Known route: the particles move along a reference track (a preloaded track, or the great circle when
 * nothing better is known). State per particle: distance along the track, cross-track offset and the
 * extra distance flown around the airports.
 *
 * cSd            lateral sd around the reference: perf.trackLateralSd for a preloaded track,
 *                perf.gcLateralFrac x distance for a great circle
 * ePriorMean/Sd  prior of the extra track distance, from FlightSim.extraKmPrior()
 * cues           true when top-of-climb and top-of-descent cues will be supplied
 * windMean/Sd    along-track wind prior at cruise: (0, perf.windSd) without a forecast,
 *                (forecast, perf.windFcSd) with one
 * cueLagS        typical delay between the top of descent and its detection; keeps the filter from
 *                punishing candidates that are already descending while the cue is still on its way
 */
class KnownRouteFilter(
    sc: Scenario,
    val ref: Track,
    mag: MagModel?,
    rng: Rng,
    private val cSd: Double,
    ePriorMean: Double,
    ePriorSd: Double,
    cues: Boolean = true,
    windMean: Double = 0.0,
    windSd: Double = sc.perf.windSd,
    n: Int = 1500,
    private val cueLagS: Double = 0.0,
) : PositionFilter(sc, mag, rng, n, windMean, windSd, 0.0) {
    private val len = ref.length
    private val zone = sc.zoneKm
    private val ellC = maxOf(150.0, 0.15 * len)
    private val s = DoubleArray(n)
    private val c = DoubleArray(n) { rng.normal(0.0, 2.0) }
    private val cArr = DoubleArray(n) { rng.normal(0.0, pf.arrLatSd) }
    private val cDep = DoubleArray(n) { rng.normal(0.0, pf.depLatSd) }
    private val extra = DoubleArray(n) { maxOf(0.0, rng.normal(ePriorMean, ePriorSd)) }
    private val negfac = DoubleArray(n)

    /** False once the cues are switched off (never supplied, or judged missed). */
    var cuesActive: Boolean = cues
        private set

    /** If no top-of-descent cue has arrived by this time, the filter stops waiting for one. */
    val todDeadlineS: Double

    init {
        val vcr = pf.tas + windMean
        val tcS = pf.climbMin * 60.0
        val dClimb = tcS / 3600.0 * (pf.vLo + (vcr - pf.vLo) * (1.0 - 1.0 / (pf.climbExp + 1.0)))
        val dCruise = maxOf(0.0, len + ePriorMean - pf.todKm - dClimb)
        val tExpected = tcS + dCruise / vcr * 3600.0
        todDeadlineS = tExpected * (1.0 + 3.0 * windSd / vcr) + 180.0
    }

    override fun stateArrays(): Array<DoubleArray> = arrayOf(s, c, cArr, cDep, extra, negfac)

    override fun afterResample() {
        for (i in 0 until n) extra[i] = maxOf(0.0, extra[i] + rng.normal(0.0, 1.0))
    }

    /** Track distance still to fly, including the extra distance spread over the arrival zone. */
    private fun remaining(i: Int): Double =
        (len - s[i]) + extra[i] * ((len - s[i]) / zone).coerceIn(0.0, 1.0)

    /** Cross-track offset: en-route wander plus departure and arrival manoeuvring terms. */
    private fun lateral(i: Int): Double {
        val g = ((s[i] - (len - zone)) / (0.5 * zone)).coerceIn(0.0, 1.0) * ((len - s[i]) / 30.0).coerceIn(0.0, 1.0)
        return c[i] + cArr[i] * g + cDep[i] * exp(-s[i] / 40.0)
    }

    private fun cueSigmaKm(i: Int): Double = maxOf(200.0, tas[i] + wind[i]) * 75.0 / 3600.0

    override fun step() {
        resampleIfNeeded()
        if (cuesActive && !todDone && t > todDeadlineS) disableCues()
        val tm = t + dt / 2
        for (i in 0 until n) {
            if (phase[i] == 0 && tm >= tc[i]) phase[i] = 1
            val rem = remaining(i)
            val vcr = tas[i] + wind[i] + wdev[i]
            var v = if (phase[i] == 0) climbSpeed(i, tm) else vcr
            if (!cuesActive && phase[i] < 2 && rem <= dtod[i]) {
                rem0[i] = rem
                vtod[i] = v
                altTod[i] = altitudeOf(i, tm, 0.0)
                phase[i] = 2
            }
            if (phase[i] == 2) {
                val u = (1.0 - rem / rem0[i]).coerceIn(0.0, 1.0)
                v = pf.vApp + (vtod[i] - pf.vApp) * (1.0 - u.pow(pf.descExp))
            }
            if (v.isNaN()) v = vcr
            val kfac = if (s[i] >= len - zone) zone / (zone + extra[i]) else 1.0
            s[i] = minOf(len, s[i] + v * dt / 3600.0 * kfac)
            stepWind(i, v)
            val rho = exp(-abs(v) * dt / 3600.0 / ellC)
            c[i] = c[i] * rho + rng.normal() * cSd * sqrt(1.0 - rho * rho)
            vLast[i] = v
        }
        t += dt
        // silence counts: with no top-of-descent cue yet, candidates that should already be descending lose weight
        if (cuesActive && !todDone) {
            for (i in 0 until n) {
                val rem = remaining(i) + vLast[i] * cueLagS / 3600.0
                val z = maxOf(0.0, dtod[i] - rem) / cueSigmaKm(i)
                val nf = -0.5 * z * z
                logw[i] += nf - negfac[i]
                negfac[i] = nf
            }
        }
    }

    override fun onTopOfDescent(tObs: Double) {
        if (todDone) return
        todDone = true
        if (!cuesActive) return
        val late = maxOf(0.0, t - tObs)
        for (i in 0 until n) {
            val rem = remaining(i) + vLast[i] * late / 3600.0
            val z = (rem - dtod[i]) / cueSigmaKm(i)
            logw[i] += -0.5 * z * z - negfac[i]
            negfac[i] = 0.0
        }
        resampleIfNeeded(force = true)
        for (i in 0 until n) {
            val vcr = tas[i] + wind[i] + wdev[i]
            rem0[i] = remaining(i) + vcr * late / 3600.0
            vtod[i] = vcr
            altTod[i] = pf.cruiseAlt
            phase[i] = 2
        }
    }

    /** Stops waiting for cues: every candidate starts its descent at its own planned distance. */
    fun disableCues() {
        if (!cuesActive) return
        for (i in 0 until n) {
            logw[i] -= negfac[i]
            negfac[i] = 0.0
        }
        cuesActive = false
    }

    override fun onGpsFix(lat: Double, lon: Double, speedKmh: Double?, courseDeg: Double?) {
        weightByFix(lat, lon)
        val (sFix, cFix) = ref.project(lat, lon)
        for (i in 0 until n) {
            s[i] = sFix
            c[i] = 0.0
            c[i] = cFix - lateral(i)
        }
        if (speedKmh != null) snapSpeed(speedKmh)
    }

    override fun fillPositions() {
        for (i in 0 until n) {
            val p = ref.pos(s[i], lateral(i))
            pLat[i] = p.lat
            pLon[i] = p.lon
            pAlt[i] = altitudeOf(i, t, if (phase[i] == 2) 1.0 - remaining(i) / rem0[i] else 0.0)
        }
    }

    /** Weighted mean of the track distance still to fly, km. */
    fun remainingKm(): Double {
        val ww = normalizedWeights()
        var r = 0.0
        for (i in 0 until n) r += ww[i] * remaining(i)
        return r
    }
}

/**
 * Unknown route: dead reckoning from the compass heading and the clock. State per particle: latitude,
 * longitude and a heading bias (compass error plus the unknown wind drift angle).
 *
 * The start defaults to the origin airport at liftoff. Pass startLat/startLon/startT to start from a known
 * position later in the flight (for example at a GPS fix that showed the planned route was wrong).
 */
class UnknownRouteFilter(
    sc: Scenario,
    mag: MagModel?,
    rng: Rng,
    windMean: Double = 0.0,
    windSd: Double = sc.perf.windSd,
    n: Int = 1500,
    startLat: Double = sc.origin.lat,
    startLon: Double = sc.origin.lon,
    startT: Double = 0.0,
    startSpreadKm: Double = 4.0,
) : PositionFilter(sc, mag, rng, n, windMean, windSd, startT) {
    private val lat = DoubleArray(n)
    private val lon = DoubleArray(n)
    private val hb = DoubleArray(n)
    private val dsince = DoubleArray(n)
    private var hMeas = Double.NaN

    init {
        val crabSd = asin((pf.windSd / pf.tas).coerceIn(0.0, 0.5)) * R2D
        val hbSd = hypot(pf.hbSd, crabSd)
        for (i in 0 until n) {
            val p = Geo.destPoint(startLat, startLon, rng.uniform(0.0, 360.0), abs(rng.normal(0.0, startSpreadKm)))
            lat[i] = p.lat
            lon[i] = p.lon
            hb[i] = rng.normal(0.0, hbSd)
        }
    }

    override fun stateArrays(): Array<DoubleArray> = arrayOf(lat, lon, hb, dsince)

    override fun afterResample() {
        for (i in 0 until n) hb[i] += rng.normal(0.0, 0.2)
    }

    override fun onHeading(trueHeadingDeg: Double) {
        hMeas = trueHeadingDeg
    }

    override fun step() {
        resampleIfNeeded()
        val tm = t + dt / 2
        val haveHeading = !hMeas.isNaN()
        for (i in 0 until n) {
            if (phase[i] == 0 && tm >= tc[i]) phase[i] = 1
            val vcr = tas[i] + wind[i] + wdev[i]
            var v = if (phase[i] == 0) climbSpeed(i, tm) else vcr
            if (phase[i] == 2) {
                val u = (dsince[i] / rem0[i]).coerceIn(0.0, 1.0)
                v = pf.vApp + (vtod[i] - pf.vApp) * (1.0 - u.pow(pf.descExp))
            }
            if (v.isNaN()) v = vcr
            val d = v * dt / 3600.0
            // with no heading at all the direction is unknown: spread the candidates evenly
            val h = if (haveHeading) hMeas - hb[i] + rng.normal(0.0, pf.hdNoise) else rng.uniform(0.0, 360.0)
            val p = Geo.destPoint(lat[i], lon[i], h, d)
            lat[i] = p.lat
            lon[i] = p.lon
            if (phase[i] == 2) dsince[i] += d
            stepWind(i, v)
            vLast[i] = v
        }
        t += dt
    }

    override fun onTopOfDescent(tObs: Double) {
        if (todDone) return
        todDone = true
        val late = maxOf(0.0, t - tObs)
        for (i in 0 until n) {
            val vcr = tas[i] + wind[i] + wdev[i]
            phase[i] = 2
            rem0[i] = dtod[i]
            vtod[i] = vcr
            altTod[i] = pf.cruiseAlt
            dsince[i] = vcr * late / 3600.0
        }
    }

    override fun onGpsFix(lat: Double, lon: Double, speedKmh: Double?, courseDeg: Double?) {
        weightByFix(lat, lon)
        for (i in 0 until n) {
            hb[i] += rng.normal(0.0, 0.5)
            this.lat[i] = lat
            this.lon[i] = lon
        }
        if (speedKmh != null) snapSpeed(speedKmh)
        if (courseDeg != null && !hMeas.isNaN()) {
            val bias = Geo.angDiff(hMeas, courseDeg)
            for (i in 0 until n) hb[i] = bias + rng.normal(0.0, pf.hdNoise)
        }
    }

    override fun fillPositions() {
        for (i in 0 until n) {
            pLat[i] = lat[i]
            pLon[i] = lon[i]
            pAlt[i] = altitudeOf(i, t, if (phase[i] == 2) dsince[i] / rem0[i] else 0.0)
        }
    }

    /**
     * The heading source lost its alignment (the phone was moved). Candidates keep their last direction, each
     * with a wider spread, until a new alignment arrives.
     */
    fun headingLost(spreadDeg: Double = 20.0) {
        for (i in 0 until n) hb[i] += rng.normal(0.0, spreadDeg)
    }

    /** A new heading alignment: its error is unrelated to the old one, so the heading biases start afresh. */
    fun resetHeadingBias() {
        val crabSd = asin((pf.windSd / pf.tas).coerceIn(0.0, 0.5)) * R2D
        val sd = hypot(pf.hbSd, crabSd)
        for (i in 0 until n) hb[i] = rng.normal(0.0, sd)
    }

    /** Weighted mean heading bias in degrees: true course = measured heading - bias. */
    fun headingBiasMean(): Double {
        val ww = normalizedWeights()
        var b = 0.0
        for (i in 0 until n) b += ww[i] * hb[i]
        return b
    }
}
