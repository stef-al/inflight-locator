Step 2 of 7. Add the estimation filters, the flight session and the flight simulator to the engine.

## 1. Engine files

Create the 5 files below next to the engine files from step 1 (folder `inflight/engine/`, `package inflight.engine`). Copy each one exactly; do not change the step 1 files.

- `inflight/engine/FlightSim.kt` (221 lines)
- `inflight/engine/Filters.kt` (627 lines)
- `inflight/engine/Session.kt` (464 lines)
- `inflight/engine/Demo.kt` (110 lines)
- `inflight/engine/SelfTestSim.kt` (73 lines)

## 2. Diagnostics

Add a second card to the Diagnostics screen, below "Engine math", titled "Simulated flights", with its own "Run checks" button and result list built the same way. It calls `SelfTestSim.run()` on a background dispatcher; this takes a few seconds on a phone, so show "Flying simulated flights..." with a progress indicator while it runs.

Add a "Run all" button at the top of the screen that runs every card in turn.

## Check

In the emulator, Diagnostics > Run all: "Engine math" 34 of 34 and "Simulated flights" 5 of 5 pass. The simulated-flights details read like "median error 9.0 km over 87 samples".

## Engine files to copy exactly

### File: inflight/engine/FlightSim.kt

```kotlin
package inflight.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One simulated flight: the true trajectory (5-second steps from liftoff) and what a phone would observe.
 * Used by the demo mode and by the tests. Times are seconds since liftoff.
 */
class SimFlight(
    val t: DoubleArray, val lat: DoubleArray, val lon: DoubleArray, val altKm: DoubleArray,
    val trackBrg: DoubleArray, val speedKmh: DoubleArray,
    val tLand: Double, val pathKm: Double, val depHeading: Double, val arrCourse: Double,
    val tToc: Double?, val tTod: Double,
    /** Observed cue times: the true event time plus timing noise. */
    val tTocObs: Double?, val tTodObs: Double,
    /** Once a minute (60, 120, ...): field strength in microtesla, dip in degrees, true heading in degrees. */
    val tMin: DoubleArray, val magF: DoubleArray, val magDip: DoubleArray, val heading: DoubleArray,
    /** Every ten minutes: particle hits counted in the last ten minutes. */
    val tCosmic: DoubleArray, val cosmic: IntArray,
    val wind0: Double, val tas: Double,
) {
    /** Index of the first sample at or after tSec. */
    fun indexAt(tSec: Double): Int {
        var lo = 0
        var hi = t.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] >= tSec) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Heading sample to use for the 30-second step that ends at tSec. */
    fun headingForStep(tSec: Double, dt: Double = 30.0): Double {
        val tm = tSec - dt / 2
        if (heading.isEmpty() || tm < 45.0) return depHeading
        return heading[(Math.round(tm / 60.0).toInt() - 1).coerceIn(0, heading.size - 1)]
    }
}

object FlightSim {
    /** Flown path for one flight: departure leg, airway route with random shortcuts, base leg and final approach. */
    fun buildPath(sc: Scenario, rng: Rng): Triple<Track, Double, Double> {
        val o = sc.origin
        val d = sc.dest
        val depH = sc.depRunways[rng.choice(DoubleArray(sc.depRunways.size) { sc.depRunways[it].weight })].headingDeg
        val pts = ArrayList<LatLon>()
        pts.add(o)
        pts.add(Geo.destPoint(o.lat, o.lon, depH, sc.depLegKm))
        for ((i, p) in sc.routePts.withIndex()) {
            val interior = i >= sc.keepFirst && i < sc.routePts.size - sc.keepLast
            if (interior && rng.uniform() < sc.pDirect) continue
            pts.add(p)
        }
        val arrC = sc.arrRunways[rng.choice(DoubleArray(sc.arrRunways.size) { sc.arrRunways[it].weight })].headingDeg
        val back = Geo.mod360(arrC + 180.0)
        val ip = Geo.destPoint(d.lat, d.lon, back, sc.fafKm + sc.ipKm)
        val last = pts.last()
        val inbound = Geo.bearingDeg(last.lat, last.lon, d.lat, d.lon)
        if (abs(Geo.angDiff(arrC, inbound)) > 110.0) {
            // must fly past the field: downwind leg abeam the airport, then base to the intercept point
            val a1 = Geo.destPoint(d.lat, d.lon, arrC + 90.0, sc.downwindOffsetKm)
            val a2 = Geo.destPoint(d.lat, d.lon, arrC - 90.0, sc.downwindOffsetKm)
            val ab = if (Geo.havKm(last.lat, last.lon, a1.lat, a1.lon) < Geo.havKm(last.lat, last.lon, a2.lat, a2.lon)) a1 else a2
            pts.add(ab)
            pts.add(Geo.destPoint(ab.lat, ab.lon, back, sc.fafKm + sc.ipKm))
        }
        pts.add(ip)
        pts.add(d)
        return Triple(Track(pts), depH, arrC)
    }

    /**
     * Mean and sd of the extra track distance (departure and arrival manoeuvres, shortcuts)
     * relative to a reference track of length refLenKm.
     */
    fun extraKmPrior(sc: Scenario, refLenKm: Double, rng: Rng, n: Int = 1500): Pair<Double, Double> {
        var sum = 0.0
        var sum2 = 0.0
        for (i in 0 until n) {
            val ex = buildPath(sc, rng).first.length - refLenKm
            sum += ex
            sum2 += ex * ex
        }
        val mean = sum / n
        return Pair(mean, sqrt(maxOf(0.0, sum2 / n - mean * mean)))
    }

    /** Simulates one flight and the phone observations. mag supplies the true field along the way. */
    fun simulate(sc: Scenario, mag: MagModel, rng: Rng): SimFlight {
        val pf = sc.perf
        val (track, depH, arrC) = buildPath(sc, rng)
        val len = track.length
        val wind0 = rng.normal(0.0, pf.windSd)
        val windA = abs(rng.normal(0.0, pf.windVar))
        val windPhi = rng.uniform(0.0, 2 * PI)
        val xwind = rng.normal(0.0, pf.windSd)
        val tas = rng.normal(pf.tas, pf.tasSd)
        val tc = maxOf(12.0, rng.normal(pf.climbMin, pf.climbMinSd)) * 60.0
        val dTod = maxOf(0.5 * pf.todKm, rng.normal(pf.todKm, pf.todKmSd))
        val amps = DoubleArray(pf.latDevSd.size) { rng.normal(0.0, pf.latDevSd[it]) }
        val phis = DoubleArray(amps.size) { rng.uniform(0.0, 2 * PI) }

        val dt = 5.0
        var t = 0.0
        var p = 0.0
        var phase = 0
        var tToc: Double? = null
        var tTod = Double.NaN
        var rem0 = Double.NaN
        var vTod = Double.NaN
        var altTod = Double.NaN
        var alt = sc.oElevKm
        var v = pf.vLo
        val ts = ArrayList<Double>()
        val ps = ArrayList<Double>()
        val alts = ArrayList<Double>()
        val vs = ArrayList<Double>()
        while (p < len) {
            val rem = len - p
            val vcr = tas + wind0 + windA * sin(2 * PI * p / pf.windWavelen + windPhi)
            if (phase == 0 && t >= tc) {
                phase = 1
                tToc = t
            }
            if (phase == 0) {
                val x = t / tc
                v = pf.vLo + (vcr - pf.vLo) * (1.0 - (1.0 - x).pow(pf.climbExp))
                alt = sc.oElevKm + (pf.cruiseAlt - sc.oElevKm) * x.pow(0.8)
            } else if (phase == 1) {
                v = vcr
                alt = pf.cruiseAlt
            }
            if (phase < 2 && rem <= dTod) {
                phase = 2
                tTod = t
                rem0 = rem
                vTod = v
                altTod = alt
            }
            if (phase == 2) {
                val u = 1.0 - rem / rem0
                v = pf.vApp + (vTod - pf.vApp) * (1.0 - u.pow(pf.descExp))
                alt = sc.dElevKm + (altTod - sc.dElevKm) * (1.0 - u)
            }
            ts.add(t)
            ps.add(p)
            alts.add(alt)
            vs.add(v)
            p += v * dt / 3600.0
            t += dt
        }
        val n = ts.size
        val lat = DoubleArray(n)
        val lon = DoubleArray(n)
        val brg = DoubleArray(n)
        for (i in 0 until n) {
            val pp = ps[i]
            val taper = (minOf(pp, len - pp) / pf.devTaperKm).coerceIn(0.0, 1.0)
            var dev = 0.0
            for (k in amps.indices) dev += amps[k] * sin((k + 1) * PI * pp / len + phis[k])
            val q = track.pos(pp, taper * dev)
            lat[i] = q.lat
            lon[i] = q.lon
            brg[i] = q.bearing
        }
        val tArr = DoubleArray(n) { ts[it] }
        val altArr = DoubleArray(n) { alts[it] }
        val tLand = tArr[n - 1] + dt

        // phone observations
        val tTocObs = tToc?.let { it + rng.normal(0.0, pf.cueSdS) }
        val tTodObs = tTod + rng.normal(0.0, pf.cueSdS)
        val nMin = maxOf(0, kotlin.math.ceil(tLand / 60.0).toInt() - 1)
        val tMin = DoubleArray(nMin) { 60.0 * (it + 1) }
        val magF = DoubleArray(nMin)
        val magI = DoubleArray(nMin)
        val hdg = DoubleArray(nMin)
        val q = sqrt(60.0 / 1800.0)
        var bF = rng.normal(0.0, pf.bFSd)
        var bI = rng.normal(0.0, pf.bISd)
        val crab = asin((xwind / tas).coerceIn(-0.5, 0.5)) * R2D
        val hb = rng.normal(0.0, pf.hbSd)
        val out = DoubleArray(2)
        fun idx(tt: Double): Int {
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (tArr[mid] >= tt) hi = mid else lo = mid + 1
            }
            return lo
        }
        for (k in 0 until nMin) {
            val i = idx(tMin[k])
            mag.query(lat[i], lon[i], altArr[i], out)
            bF += rng.normal(0.0, pf.bFRw * q)
            bI += rng.normal(0.0, pf.bIRw * q)
            magF[k] = out[0] + bF + rng.normal(0.0, pf.fNoise)
            magI[k] = out[1] + bI + rng.normal(0.0, pf.iNoise)
            hdg[k] = Geo.mod360(brg[i] + crab + hb + rng.normal(0.0, pf.hdNoise))
        }
        val nCr = maxOf(0, kotlin.math.ceil(tLand / 600.0).toInt() - 1)
        val tCr = DoubleArray(nCr) { 600.0 * (it + 1) }
        val cr = IntArray(nCr)
        for (k in 0 until nCr) {
            val i = idx(tCr[k] - 300.0)
            cr[k] = rng.poisson(Cosmic.ratePerHour(lat[i], lon[i], altArr[i], pf) / 6.0)
        }
        return SimFlight(
            tArr, lat, lon, altArr, brg, DoubleArray(n) { vs[it] },
            tLand, len, depH, arrC, tToc, tTod, tTocObs, tTodObs,
            tMin, magF, magI, hdg, tCr, cr, wind0, tas,
        )
    }
}
```

### File: inflight/engine/Filters.kt

```kotlin
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
```

### File: inflight/engine/Session.kt

```kotlin
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
```

### File: inflight/engine/Demo.kt

```kotlin
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
```

### File: inflight/engine/SelfTestSim.kt

```kotlin
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
```
