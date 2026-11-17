Step 1 of 7. Create the app and add the first part of its estimation engine.

Create a new Android app named "Inflight Locator": Kotlin, Jetpack Compose, Material 3, one activity, light and dark theme following the system. If the project's system instructions are empty, apply these rules: the package `inflight.engine` is a verified reference engine that must be copied exactly and never edited; app code calls it from a background thread; minSdk 26; no Google Play services.

## 1. Engine files

Create the 8 files below in the same source root as MainActivity (app/src/main/java or app/src/main/kotlin), in the folder `inflight/engine/`. Copy each one exactly, character for character, with its `package inflight.engine` line. Do not edit, reformat, shorten or merge them. They use only the Kotlin standard library and java.time.

- `inflight/engine/Rng.kt` (73 lines)
- `inflight/engine/Geo.kt` (179 lines)
- `inflight/engine/Wmm2025Coefficients.kt` (98 lines)
- `inflight/engine/Wmm.kt` (133 lines)
- `inflight/engine/Model.kt` (177 lines)
- `inflight/engine/Airports.kt` (106 lines)
- `inflight/engine/Tools.kt` (282 lines)
- `inflight/engine/SelfTest.kt` (115 lines)

## 2. Navigation shell

A Scaffold with a bottom NavigationBar. For now it has one destination, "Diagnostics" (icon: a checklist). Later prompts add "Demo", "Flights" and "Live". On first launch show a one-time dialog: "Inflight Locator estimates your position in flight without satellite signals. Estimates can be tens of kilometres off. Not for navigation." with an OK button (remember the answer in SharedPreferences).

## 3. Diagnostics screen

A scrolling screen of cards. The first card is titled "Engine math".

- A button "Run checks" calls `SelfTest.math()` on a background dispatcher. While it runs, show a progress indicator in place of the button.
- The result is a list of `Check` objects (fields: `name`, `passed`, `detail`). Show each as a row: a green check icon or a red cross icon, the name in body text, and the detail underneath in a smaller, muted style.
- Above the list, a summary line: "34 of 34 checks passed" (use the real counts), in red when anything failed.

## Check

Open the app in the emulator, go to Diagnostics, tap "Run checks". All 34 checks pass. If any fail, compare the engine files with this prompt and recopy them exactly.

## Engine files to copy exactly

### File: inflight/engine/Rng.kt

```kotlin
package inflight.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Small deterministic random generator (SplitMix64), so a seeded run repeats on every device.
 * Not for cryptography.
 */
class Rng(seed: Long) {
    private var state = seed
    private var spare = 0.0
    private var hasSpare = false

    fun nextLong(): Long {
        state += 0x9E3779B97F4A7C15uL.toLong()
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun uniform(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun uniform(a: Double, b: Double): Double = a + (b - a) * uniform()

    /** Standard normal (polar Box-Muller). */
    fun normal(): Double {
        if (hasSpare) {
            hasSpare = false
            return spare
        }
        while (true) {
            val u = 2.0 * uniform() - 1.0
            val v = 2.0 * uniform() - 1.0
            val s = u * u + v * v
            if (s > 0.0 && s < 1.0) {
                val f = sqrt(-2.0 * ln(s) / s)
                spare = v * f
                hasSpare = true
                return u * f
            }
        }
    }

    fun normal(mean: Double, sd: Double): Double = mean + sd * normal()

    fun poisson(lambda: Double): Int {
        if (lambda <= 0.0) return 0
        if (lambda > 40.0) return maxOf(0, normal(lambda, sqrt(lambda)).roundToInt())
        val limit = exp(-lambda)
        var k = 0
        var p = uniform()
        while (p > limit) {
            k++
            p *= uniform()
        }
        return k
    }

    /** Index drawn with probability proportional to the weights. */
    fun choice(weights: DoubleArray): Int {
        var u = uniform() * weights.sum()
        for (i in weights.indices) {
            u -= weights[i]
            if (u < 0.0) return i
        }
        return weights.size - 1
    }
}
```

### File: inflight/engine/Geo.kt

```kotlin
package inflight.engine

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius, km. */
const val R_EARTH_KM = 6371.0088
internal const val D2R = PI / 180.0
internal const val R2D = 180.0 / PI

data class LatLon(val lat: Double, val lon: Double)

/** Great-circle geometry on a sphere. Angles in degrees, distances in km. */
object Geo {
    fun havKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * D2R
        val p2 = lat2 * D2R
        val sp = sin((p2 - p1) / 2)
        val sl = sin((lon2 - lon1) * D2R / 2)
        val a = sp * sp + cos(p1) * cos(p2) * sl * sl
        return 2 * R_EARTH_KM * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * D2R
        val p2 = lat2 * D2R
        val dl = (lon2 - lon1) * D2R
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return mod360(atan2(y, x) * R2D)
    }

    /** Point reached from (lat, lon) after distKm on bearing brgDeg. A negative distance goes backwards. */
    fun destPoint(lat: Double, lon: Double, brgDeg: Double, distKm: Double): LatLon {
        val p1 = lat * D2R
        val b = brgDeg * D2R
        val d = distKm / R_EARTH_KM
        val sp2 = sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b)
        val p2 = asin(sp2.coerceIn(-1.0, 1.0))
        val l2 = lon * D2R + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sp2)
        return LatLon(p2 * R2D, normLon(l2 * R2D))
    }

    /** Point at fraction f of the great circle from point 1 to point 2. */
    fun gcInterp(lat1: Double, lon1: Double, lat2: Double, lon2: Double, f: Double): LatLon {
        val p1 = lat1 * D2R
        val l1 = lon1 * D2R
        val p2 = lat2 * D2R
        val l2 = lon2 * D2R
        val ax = cos(p1) * cos(l1)
        val ay = cos(p1) * sin(l1)
        val az = sin(p1)
        val bx = cos(p2) * cos(l2)
        val by = cos(p2) * sin(l2)
        val bz = sin(p2)
        val om = acos((ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0))
        if (om < 1e-12) return LatLon(lat1, lon1)
        val ka = sin((1 - f) * om) / sin(om)
        val kb = sin(f * om) / sin(om)
        val x = ka * ax + kb * bx
        val y = ka * ay + kb * by
        val z = ka * az + kb * bz
        return LatLon(asin(z.coerceIn(-1.0, 1.0)) * R2D, atan2(y, x) * R2D)
    }

    /** Smallest signed difference a - b, in -180..180. */
    fun angDiff(a: Double, b: Double): Double = (a - b + 180.0).mod(360.0) - 180.0

    fun mod360(x: Double): Double = x.mod(360.0)

    /** Longitude folded into -180..180. */
    fun normLon(lon: Double): Double = (lon + 540.0).mod(360.0) - 180.0
}

/** Position on a track: coordinates plus the local track bearing. */
data class TrackPos(val lat: Double, val lon: Double, val bearing: Double)

/**
 * A polyline of great-circle legs, densified (every stepKm) so that linear interpolation is accurate.
 * s is the distance along the track in km; c is the cross-track offset in km, positive to the right.
 */
class Track(points: List<LatLon>, stepKm: Double = 4.0) {
    val lat: DoubleArray
    val lon: DoubleArray      // unwrapped, so it stays continuous across the antimeridian
    val cum: DoubleArray      // distance from the start to each vertex
    val brg: DoubleArray      // bearing of the leg that starts at each vertex
    val length: Double

    init {
        require(points.size >= 2) { "a track needs at least two points" }
        val la = ArrayList<Double>()
        val lo = ArrayList<Double>()
        la.add(points[0].lat)
        lo.add(points[0].lon)
        for (k in 0 until points.size - 1) {
            val a = points[k]
            val b = points[k + 1]
            val d = Geo.havKm(a.lat, a.lon, b.lat, b.lon)
            val n = maxOf(1, ceil(d / stepKm).toInt())
            for (i in 1..n) {
                val p = Geo.gcInterp(a.lat, a.lon, b.lat, b.lon, i.toDouble() / n)
                la.add(p.lat)
                lo.add(p.lon)
            }
        }
        val m = la.size
        lat = DoubleArray(m) { la[it] }
        lon = DoubleArray(m)
        lon[0] = lo[0]
        for (i in 1 until m) lon[i] = lon[i - 1] + Geo.angDiff(lo[i], lon[i - 1])
        cum = DoubleArray(m)
        brg = DoubleArray(m)
        for (i in 0 until m - 1) {
            cum[i + 1] = cum[i] + Geo.havKm(lat[i], lon[i], lat[i + 1], lon[i + 1])
            brg[i] = Geo.bearingDeg(lat[i], lon[i], lat[i + 1], lon[i + 1])
        }
        brg[m - 1] = brg[m - 2]
        length = cum[m - 1]
    }

    /** Index of the last vertex at or before s. */
    private fun vertexBefore(s: Double): Int {
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun bearingAt(s: Double): Double = brg[vertexBefore(s.coerceIn(0.0, length))]

    /** Position at distance s along the track, shifted c km to the right of it. */
    fun pos(s: Double, c: Double = 0.0): TrackPos {
        val sc = s.coerceIn(0.0, length)
        val i = vertexBefore(sc)
        val b = brg[i]
        var la = lat[i]
        var lo = lon[i]
        if (i < cum.size - 1) {
            val f = (sc - cum[i]) / (cum[i + 1] - cum[i])
            la += f * (lat[i + 1] - lat[i])
            lo += f * (lon[i + 1] - lon[i])
        }
        if (c == 0.0) return TrackPos(la, Geo.normLon(lo), b)
        val p = Geo.destPoint(la, lo, b + 90.0, c)
        return TrackPos(p.lat, p.lon, b)
    }

    /** Along-track distance and cross-track offset of the track point nearest to (lat, lon). */
    fun project(pLat: Double, pLon: Double): Pair<Double, Double> {
        var j = 0
        var best = Double.MAX_VALUE
        for (i in lat.indices) {
            val d = Geo.havKm(lat[i], lon[i], pLat, pLon)
            if (d < best) {
                best = d
                j = i
            }
        }
        val th = (Geo.bearingDeg(lat[j], lon[j], pLat, pLon) - brg[j]) * D2R
        return Pair((cum[j] + best * cos(th)).coerceIn(0.0, length), best * sin(th))
    }

    /** Distance from (lat, lon) to the nearest vertex of the track, km. */
    fun distanceKm(pLat: Double, pLon: Double): Double {
        var best = Double.MAX_VALUE
        for (i in lat.indices) best = minOf(best, Geo.havKm(lat[i], lon[i], pLat, pLon))
        return best
    }
}
```

### File: inflight/engine/Wmm2025Coefficients.kt

```kotlin
package inflight.engine

/**
 * World Magnetic Model 2025 coefficients (NOAA NCEI / BGS, public domain).
 * Epoch 2025.0, valid to the end of 2029. Columns: n m g h gdot hdot (nT, nT/year).
 */
internal const val WMM2025_COF = """
  1  0  -29351.8       0.0       12.0        0.0
  1  1   -1410.8    4545.4        9.7      -21.5
  2  0   -2556.6       0.0      -11.6        0.0
  2  1    2951.1   -3133.6       -5.2      -27.7
  2  2    1649.3    -815.1       -8.0      -12.1
  3  0    1361.0       0.0       -1.3        0.0
  3  1   -2404.1     -56.6       -4.2        4.0
  3  2    1243.8     237.5        0.4       -0.3
  3  3     453.6    -549.5      -15.6       -4.1
  4  0     895.0       0.0       -1.6        0.0
  4  1     799.5     278.6       -2.4       -1.1
  4  2      55.7    -133.9       -6.0        4.1
  4  3    -281.1     212.0        5.6        1.6
  4  4      12.1    -375.6       -7.0       -4.4
  5  0    -233.2       0.0        0.6        0.0
  5  1     368.9      45.4        1.4       -0.5
  5  2     187.2     220.2        0.0        2.2
  5  3    -138.7    -122.9        0.6        0.4
  5  4    -142.0      43.0        2.2        1.7
  5  5      20.9     106.1        0.9        1.9
  6  0      64.4       0.0       -0.2        0.0
  6  1      63.8     -18.4       -0.4        0.3
  6  2      76.9      16.8        0.9       -1.6
  6  3    -115.7      48.8        1.2       -0.4
  6  4     -40.9     -59.8       -0.9        0.9
  6  5      14.9      10.9        0.3        0.7
  6  6     -60.7      72.7        0.9        0.9
  7  0      79.5       0.0       -0.0        0.0
  7  1     -77.0     -48.9       -0.1        0.6
  7  2      -8.8     -14.4       -0.1        0.5
  7  3      59.3      -1.0        0.5       -0.8
  7  4      15.8      23.4       -0.1        0.0
  7  5       2.5      -7.4       -0.8       -1.0
  7  6     -11.1     -25.1       -0.8        0.6
  7  7      14.2      -2.3        0.8       -0.2
  8  0      23.2       0.0       -0.1        0.0
  8  1      10.8       7.1        0.2       -0.2
  8  2     -17.5     -12.6        0.0        0.5
  8  3       2.0      11.4        0.5       -0.4
  8  4     -21.7      -9.7       -0.1        0.4
  8  5      16.9      12.7        0.3       -0.5
  8  6      15.0       0.7        0.2       -0.6
  8  7     -16.8      -5.2       -0.0        0.3
  8  8       0.9       3.9        0.2        0.2
  9  0       4.6       0.0       -0.0        0.0
  9  1       7.8     -24.8       -0.1       -0.3
  9  2       3.0      12.2        0.1        0.3
  9  3      -0.2       8.3        0.3       -0.3
  9  4      -2.5      -3.3       -0.3        0.3
  9  5     -13.1      -5.2        0.0        0.2
  9  6       2.4       7.2        0.3       -0.1
  9  7       8.6      -0.6       -0.1       -0.2
  9  8      -8.7       0.8        0.1        0.4
  9  9     -12.9      10.0       -0.1        0.1
 10  0      -1.3       0.0        0.1        0.0
 10  1      -6.4       3.3        0.0        0.0
 10  2       0.2       0.0        0.1       -0.0
 10  3       2.0       2.4        0.1       -0.2
 10  4      -1.0       5.3       -0.0        0.1
 10  5      -0.6      -9.1       -0.3       -0.1
 10  6      -0.9       0.4        0.0        0.1
 10  7       1.5      -4.2       -0.1        0.0
 10  8       0.9      -3.8       -0.1       -0.1
 10  9      -2.7       0.9       -0.0        0.2
 10 10      -3.9      -9.1       -0.0       -0.0
 11  0       2.9       0.0        0.0        0.0
 11  1      -1.5       0.0       -0.0       -0.0
 11  2      -2.5       2.9        0.0        0.1
 11  3       2.4      -0.6        0.0       -0.0
 11  4      -0.6       0.2        0.0        0.1
 11  5      -0.1       0.5       -0.1       -0.0
 11  6      -0.6      -0.3        0.0       -0.0
 11  7      -0.1      -1.2       -0.0        0.1
 11  8       1.1      -1.7       -0.1       -0.0
 11  9      -1.0      -2.9       -0.1        0.0
 11 10      -0.2      -1.8       -0.1        0.0
 11 11       2.6      -2.3       -0.1        0.0
 12  0      -2.0       0.0        0.0        0.0
 12  1      -0.2      -1.3        0.0       -0.0
 12  2       0.3       0.7       -0.0        0.0
 12  3       1.2       1.0       -0.0       -0.1
 12  4      -1.3      -1.4       -0.0        0.1
 12  5       0.6      -0.0       -0.0       -0.0
 12  6       0.6       0.6        0.1       -0.0
 12  7       0.5      -0.1       -0.0       -0.0
 12  8      -0.1       0.8        0.0        0.0
 12  9      -0.4       0.1        0.0       -0.0
 12 10      -0.2      -1.0       -0.1       -0.0
 12 11      -1.3       0.1       -0.0        0.0
 12 12      -0.7       0.2       -0.1       -0.1
"""
```

### File: inflight/engine/Wmm.kt

```kotlin
package inflight.engine

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Geomagnetic field at one point. x north, y east, z down, h horizontal, f total: all in nanotesla. */
data class MagField(
    val x: Double, val y: Double, val z: Double, val h: Double, val f: Double,
    val dipDeg: Double,   // inclination, positive when the field points down (northern hemisphere)
    val declDeg: Double,  // declination, positive east: true heading = magnetic heading + declination
)

/** Anything that can return field strength (microtesla) and dip angle (degrees) at a position. */
interface MagModel {
    /** Writes F in microtesla to out[0] and the dip angle in degrees to out[1]. */
    fun query(lat: Double, lon: Double, altKm: Double, out: DoubleArray)
}

/**
 * World Magnetic Model 2025, degree and order 12, evaluated for one decimal year (valid 2025.0 to 2030.0).
 * Inputs: geodetic latitude and longitude in degrees, height above the WGS84 ellipsoid in km.
 * Android's own GeomagneticField class is documented with older coefficients, so the app uses this instead.
 * One instance is not thread-safe (it reuses scratch arrays); create one per thread.
 */
class Wmm2025(val year: Double) : MagModel {
    private val g = Array(N + 1) { DoubleArray(N + 1) }
    private val h = Array(N + 1) { DoubleArray(N + 1) }
    private val p = Array(N + 1) { DoubleArray(N + 1) }
    private val dp = Array(N + 1) { DoubleArray(N + 1) }
    private val cosM = DoubleArray(N + 1)
    private val sinM = DoubleArray(N + 1)

    init {
        val dtYears = year - EPOCH
        for (line in WMM2025_COF.lineSequence()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 6) continue
            val n = f[0].toInt()
            val m = f[1].toInt()
            g[n][m] = f[2].toDouble() + dtYears * f[4].toDouble()
            h[n][m] = f[3].toDouble() + dtYears * f[5].toDouble()
        }
    }

    fun field(latDeg: Double, lonDeg: Double, altKm: Double): MagField {
        val phi = latDeg.coerceIn(-89.9999, 89.9999) * D2R
        val lam = lonDeg * D2R
        // geodetic -> geocentric
        val e2 = WGS84_F * (2.0 - WGS84_F)
        val sphi = sin(phi)
        val rc = WGS84_A / sqrt(1.0 - e2 * sphi * sphi)
        val pxy = (rc + altKm) * cos(phi)
        val pz = (rc * (1.0 - e2) + altKm) * sphi
        val r = hypot(pxy, pz)
        val ct = pz / r                      // cos(colatitude) = sin(geocentric latitude)
        val st = maxOf(pxy / r, 1e-10)       // sin(colatitude)
        // Schmidt semi-normalised associated Legendre functions and their colatitude derivatives
        p[0][0] = 1.0
        dp[0][0] = 0.0
        for (n in 1..N) {
            for (m in 0..n) {
                if (m == n) {
                    val k = if (n == 1) 1.0 else sqrt(1.0 - 1.0 / (2.0 * n))
                    p[n][n] = k * st * p[n - 1][n - 1]
                    dp[n][n] = k * (st * dp[n - 1][n - 1] + ct * p[n - 1][n - 1])
                } else {
                    val d = sqrt((n * n - m * m).toDouble())
                    val k1 = (2 * n - 1) / d
                    var pv = k1 * ct * p[n - 1][m]
                    var dv = k1 * (ct * dp[n - 1][m] - st * p[n - 1][m])
                    if (m <= n - 2) {
                        val k2 = sqrt(((n - 1) * (n - 1) - m * m).toDouble()) / d
                        pv -= k2 * p[n - 2][m]
                        dv -= k2 * dp[n - 2][m]
                    }
                    p[n][m] = pv
                    dp[n][m] = dv
                }
            }
        }
        for (m in 0..N) {
            cosM[m] = cos(m * lam)
            sinM[m] = sin(m * lam)
        }
        // field in the geocentric frame: bx north, by east, bz down
        var bx = 0.0
        var by = 0.0
        var bz = 0.0
        val ratio = A_REF / r
        var rn = ratio * ratio
        for (n in 1..N) {
            rn *= ratio
            for (m in 0..n) {
                val gc = g[n][m] * cosM[m] + h[n][m] * sinM[m]
                bx += rn * gc * dp[n][m]
                by += rn * m * (g[n][m] * sinM[m] - h[n][m] * cosM[m]) * p[n][m]
                bz -= rn * (n + 1) * gc * p[n][m]
            }
        }
        by /= st
        // rotate into the geodetic (local ellipsoid) frame
        val psi = asin(ct.coerceIn(-1.0, 1.0)) - phi
        val x = bx * cos(psi) - bz * sin(psi)
        val z = bx * sin(psi) + bz * cos(psi)
        val hh = hypot(x, by)
        return MagField(x, by, z, hh, hypot(hh, z), atan2(z, hh) * R2D, atan2(by, x) * R2D)
    }

    /** Declination in degrees, positive east. */
    fun declination(lat: Double, lon: Double, altKm: Double = 0.0): Double = field(lat, lon, altKm).declDeg

    override fun query(lat: Double, lon: Double, altKm: Double, out: DoubleArray) {
        val f = field(lat, lon, altKm)
        out[0] = f.f / 1000.0
        out[1] = f.dipDeg
    }

    companion object {
        const val EPOCH = 2025.0
        const val VALID_UNTIL = 2030.0
        private const val N = 12
        private const val A_REF = 6371.2            // geomagnetic reference radius, km
        private const val WGS84_A = 6378.137        // km
        private const val WGS84_F = 1.0 / 298.257223563

        /** Decimal year from a Unix time in milliseconds (accurate to about a day, which is ample here). */
        fun decimalYear(epochMillis: Long): Double = 1970.0 + epochMillis / (365.2425 * 86_400_000.0)
    }
}
```

### File: inflight/engine/Model.kt

```kotlin
package inflight.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Design values: aircraft performance, atmosphere and phone-sensor noise.
 * Defaults describe an Airbus A321neo on a short-haul leg. They are assumptions until real flights are logged.
 * Speeds in km/h, distances in km, times as named.
 */
data class Perf(
    // aircraft and atmosphere
    val tas: Double = 840.0,            // cruise true airspeed (about Mach 0.78 to 0.79)
    val tasSd: Double = 12.0,
    val windSd: Double = 60.0,          // day-to-day sd of the along-track wind at cruise
    val windVar: Double = 20.0,         // sd of the wind variation along the route
    val windWavelen: Double = 800.0,
    val climbMin: Double = 18.5,        // liftoff to top of climb, minutes
    val climbMinSd: Double = 2.0,
    val climbExp: Double = 3.0,
    val todKm: Double = 225.0,          // track distance from top of descent to touchdown
    val todKmSd: Double = 20.0,
    val descExp: Double = 3.0,
    val vLo: Double = 300.0,            // ground speed at liftoff
    val vApp: Double = 250.0,           // ground speed at touchdown
    val cruiseAlt: Double = 11.0,       // km
    val latDevSd: List<Double> = listOf(8.0, 5.0, 3.0),  // smooth lateral deviation harmonics (truth simulation)
    val devTaperKm: Double = 60.0,
    val gcLateralFrac: Double = 0.042,  // lateral sd, as a fraction of the distance, when only the great circle is known
    val trackLateralSd: Double = 12.0,  // lateral sd around a preloaded track
    val arrLatSd: Double = 18.0,        // extra lateral sd while manoeuvring for the arrival runway
    val depLatSd: Double = 7.0,         // extra lateral sd right after takeoff (runway direction unknown)
    val windFcSd: Double = 15.0,        // error sd of a preloaded winds-aloft forecast
    // phone sensors
    val cueSdS: Double = 45.0,          // timing error of the top-of-climb and top-of-descent cues, seconds
    val hbSd: Double = 5.0,             // compass bias after calibration, degrees
    val hdNoise: Double = 2.0,          // per-minute heading noise, degrees
    val bFSd: Double = 0.7,             // field-strength bias after gate calibration, microtesla
    val bISd: Double = 1.5,             // dip-angle bias, degrees
    val fNoise: Double = 0.3,           // noise of a one-minute average
    val iNoise: Double = 0.5,
    val bFRw: Double = 0.2,             // bias random walk per sqrt(30 minutes)
    val bIRw: Double = 0.4,
    val crCruisePerH: Double = 19.33,   // particle hits per hour on a phone camera at cruise (one published measurement)
    val crGroundPerH: Double = 3.68,
    val crRefRc: Double = 14.0,         // assumed mean cutoff rigidity of that measurement, GV
)

/** A runway direction (true heading in degrees) with a relative weight for how often it is used. */
data class Runway(val headingDeg: Double, val weight: Double = 1.0)

/** A flight to estimate or to simulate. Elevations in km. */
data class Scenario(
    val name: String,
    val origin: LatLon,
    val dest: LatLon,
    val oElevKm: Double,
    val dElevKm: Double,
    val routePts: List<LatLon> = emptyList(),   // typical en-route waypoints; empty means great circle only
    val depRunways: List<Runway> = DEFAULT_RUNWAYS,
    val arrRunways: List<Runway> = DEFAULT_RUNWAYS,
    val perf: Perf = Perf(),
    val pDirect: Double = 0.35,                 // chance that an interior waypoint is skipped (direct-to)
    val keepFirst: Int = 2,
    val keepLast: Int = 1,
    val depLegKm: Double = 6.0,
    val fafKm: Double = 16.0,
    val ipKm: Double = 8.0,
    val downwindOffsetKm: Double = 9.0,
    val year: Double = 2026.88,
    val zoneKm: Double = 150.0,                 // arrival zone over which extra track distance is spread
) {
    /** Great circle from origin to destination. */
    fun greatCircle(): Track = Track(listOf(origin, dest))

    /** Reference track through the typical waypoints (the great circle when there are none). */
    fun referenceTrack(): Track = Track(listOf(origin) + routePts + listOf(dest))

    companion object {
        /** Used when the runway directions of an airport are not known. */
        val DEFAULT_RUNWAYS = listOf(Runway(0.0), Runway(90.0), Runway(180.0), Runway(270.0))
    }
}

object Scenarios {
    /** Wizz Air W6 4747, Skopje (SKP/LWSK) to Prague (PRG/LKPR), with a published airway route. */
    fun w64747(): Scenario = Scenario(
        name = "W6 4747 Skopje-Prague",
        origin = LatLon(41.96162, 21.62137),
        dest = LatLon(50.10114, 14.26296),
        oElevKm = 0.24,
        dElevKm = 0.38,
        routePts = listOf(
            LatLon(42.15944, 21.39722),  // LONTA
            LatLon(43.71750, 20.81033),  // VTN
            LatLon(43.85467, 20.75311),  // GONAR
            LatLon(44.21206, 20.60267),  // NEVAK
            LatLon(44.49189, 20.48333),  // ORVAN
            LatLon(44.80703, 20.33494),  // BEO
            LatLon(46.04731, 19.35593),  // TONDO
            LatLon(47.62377, 17.40891),  // TEKNO
            LatLon(47.83071, 17.11443),  // BEGLA
            LatLon(47.92196, 16.95406),  // GESGI
            LatLon(48.10511, 16.62926),  // FMD
            LatLon(48.41714, 16.01859),  // STO
            LatLon(48.88811, 15.61579),  // LANUX
        ),
        depRunways = listOf(Runway(345.0, 0.7), Runway(165.0, 0.3)),                                     // LWSK 34 / 16
        arrRunways = listOf(Runway(245.0, 0.60), Runway(65.0, 0.25), Runway(307.0, 0.12), Runway(127.0, 0.03)),  // LKPR 24 / 06 / 30 / 12
    )

    /** Indicative long-haul legs: the typical route is the great circle bent sideways by up to 120 km. */
    fun longHaul(northSouth: Boolean): Scenario {
        val o = LatLon(50.0333, 8.5706)  // Frankfurt
        val d = if (northSouth) LatLon(-26.1392, 28.2460) else LatLon(40.6413, -73.7781)
        val n = 24
        val route = ArrayList<LatLon>()
        var b0 = Double.NaN
        for (i in 1 until n) {
            val f = i.toDouble() / n
            val a = Geo.gcInterp(o.lat, o.lon, d.lat, d.lon, f)
            val a2 = Geo.gcInterp(o.lat, o.lon, d.lat, d.lon, minOf(1.0, f + 0.01))
            val b = Geo.bearingDeg(a.lat, a.lon, a2.lat, a2.lon)
            val off = 120.0 * sin(PI * f) * (if (northSouth) 1 else -1)
            route.add(Geo.destPoint(a.lat, a.lon, b + 90.0, off))
            if (b0.isNaN()) b0 = b
        }
        val bEnd = Geo.bearingDeg(route.last().lat, route.last().lon, d.lat, d.lon)
        val perf = Perf(
            tas = 900.0, tasSd = 15.0, windSd = 50.0, windVar = 45.0, windWavelen = 2500.0,
            climbMin = 25.0, climbMinSd = 3.0, todKm = 250.0, todKmSd = 25.0, cruiseAlt = 11.5,
            latDevSd = listOf(60.0, 35.0, 20.0), devTaperKm = 150.0, trackLateralSd = 60.0,
        )
        return Scenario(
            name = if (northSouth) "Long-haul north-south (Frankfurt-Johannesburg)" else "Long-haul east-west (Frankfurt-New York)",
            origin = o, dest = d, oElevKm = 0.1, dElevKm = if (northSouth) 1.7 else 0.0,
            routePts = route,
            depRunways = listOf(Runway(b0, 0.6), Runway(Geo.mod360(b0 + 180.0), 0.4)),
            arrRunways = listOf(
                Runway(bEnd, 0.4), Runway(Geo.mod360(bEnd + 90.0), 0.2),
                Runway(Geo.mod360(bEnd + 180.0), 0.2), Runway(Geo.mod360(bEnd + 270.0), 0.2),
            ),
            perf = perf, pDirect = 0.15, zoneKm = 200.0,
        )
    }
}

/** Cosmic-ray hit rate on a phone camera sensor. The dependence on cutoff rigidity is an assumed shape. */
object Cosmic {
    private const val POLE_LAT = 80.85     // centred-dipole north pole, about 2025
    private const val POLE_LON = -72.76
    private val RC = doubleArrayOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 15.0, 17.0)
    private val REL = doubleArrayOf(1.00, 1.00, 0.88, 0.74, 0.60, 0.48, 0.40, 0.33, 0.30)

    /** Vertical cutoff rigidity in GV, centred-dipole approximation. */
    fun cutoffRigidity(lat: Double, lon: Double): Double {
        val sinm = sin(lat * D2R) * sin(POLE_LAT * D2R) +
            cos(lat * D2R) * cos(POLE_LAT * D2R) * cos((lon - POLE_LON) * D2R)
        return 14.9 * (1.0 - sinm * sinm).pow(2)
    }

    private fun rel(rc: Double): Double {
        if (rc <= RC[0]) return REL[0]
        for (i in 1 until RC.size) {
            if (rc <= RC[i]) return REL[i - 1] + (REL[i] - REL[i - 1]) * (rc - RC[i - 1]) / (RC[i] - RC[i - 1])
        }
        return REL[REL.size - 1]
    }

    /** Expected hits per hour at a position and altitude. */
    fun ratePerHour(lat: Double, lon: Double, altKm: Double, perf: Perf): Double {
        val cruise = perf.crCruisePerH * rel(cutoffRigidity(lat, lon)) / rel(perf.crRefRc)
        return perf.crGroundPerH + (cruise - perf.crGroundPerH) * (altKm / 11.0).coerceIn(0.0, 1.2).pow(3)
    }
}
```

### File: inflight/engine/Airports.kt

```kotlin
package inflight.engine

import java.util.Locale
import kotlin.math.abs

/** An airport with the true headings of its runway directions (may be empty). */
data class Airport(
    val ident: String,   // ICAO code where one exists, for example LKPR
    val iata: String,    // for example PRG; may be empty
    val name: String,
    val lat: Double,
    val lon: Double,
    val elevM: Double,
    val runwayHeadings: List<Double>,
) {
    val label: String get() = if (iata.isNotEmpty()) iata else ident

    /** Runway directions as equally likely choices (four compass directions when none are known). */
    fun runways(): List<Runway> =
        if (runwayHeadings.isEmpty()) Scenario.DEFAULT_RUNWAYS else runwayHeadings.distinct().map { Runway(it) }
}

object Airports {
    /**
     * Parses data/airports.csv from the repository. Columns:
     * ident,iata,lat,lon,elev_m,runway_headings_true,name  (headings separated by semicolons).
     */
    fun parseCsv(text: String): List<Airport> {
        val out = ArrayList<Airport>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("ident,")) continue
            val f = line.split(",", limit = 7)
            if (f.size < 7) continue
            val lat = f[2].toDoubleOrNull() ?: continue
            val lon = f[3].toDoubleOrNull() ?: continue
            val headings = f[5].split(";").mapNotNull { it.toDoubleOrNull() }
            out.add(Airport(f[0], f[1], f[6].trim(), lat, lon, f[4].toDoubleOrNull() ?: 0.0, headings))
        }
        return out
    }

    /** Finds an airport by IATA or ICAO code, ignoring case. */
    fun byCode(airports: List<Airport>, code: String): Airport? {
        val c = code.trim().uppercase()
        return airports.firstOrNull { it.iata == c } ?: airports.firstOrNull { it.ident == c }
    }

    fun nearest(airports: List<Airport>, lat: Double, lon: Double): Airport? =
        airports.minByOrNull { Geo.havKm(it.lat, it.lon, lat, lon) }

    /** A position in words, for example "45 km NW of BUD (Budapest Ferenc Liszt International Airport)". */
    fun describe(airports: List<Airport>, lat: Double, lon: Double): String {
        val a = nearest(airports, lat, lon) ?: return String.format(Locale.US, "%.2f, %.2f", lat, lon)
        val d = Geo.havKm(a.lat, a.lon, lat, lon)
        if (d < 5.0) return "at ${a.label} (${a.name})"
        val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val c = points[((Geo.bearingDeg(a.lat, a.lon, lat, lon) + 22.5) / 45.0).toInt() % 8]
        return String.format(Locale.US, "%.0f km %s of %s (%s)", d, c, a.label, a.name)
    }
}

/** Result of matching a landing to an airport. */
data class LandingGuess(
    /** Best match using position and, when a landing course is given, runway direction. */
    val best: Airport?,
    /** Best match by position alone. */
    val bestByPosition: Airport?,
    /** Airports inside the error circle. */
    val inCircle: Int,
    /** Up to five candidates with their scores (lower is better). */
    val ranked: List<Pair<Airport, Double>>,
)

object LandingSnap {
    /**
     * At touchdown: ranks airports by distance from the estimate and by how well a runway lines up with
     * the landing course (true degrees). The winner is where the flight most likely landed.
     */
    fun rank(airports: List<Airport>, estLat: Double, estLon: Double, r95Km: Double, courseDeg: Double?): LandingGuess {
        val rr = maxOf(r95Km, 30.0)
        val sig = rr / 2.45
        var inCircle = 0
        var bestPos: Airport? = null
        var scorePos = Double.MAX_VALUE
        val scored = ArrayList<Pair<Airport, Double>>()
        for (a in airports) {
            if (abs(a.lat - estLat) > 3.0 * rr / 111.0 + 0.1) continue   // quick reject
            val d = Geo.havKm(a.lat, a.lon, estLat, estLon)
            if (d <= rr) inCircle++
            if (d > 3.0 * rr) continue
            val chi = (d / sig) * (d / sig)
            if (chi < scorePos) {
                scorePos = chi
                bestPos = a
            }
            var total = chi
            if (courseDeg != null) {
                val dh = a.runwayHeadings.minOfOrNull { abs(Geo.angDiff(it, courseDeg)) } ?: 30.0
                total += (dh / 8.0) * (dh / 8.0)
            }
            scored.add(Pair(a, total))
        }
        scored.sortBy { it.second }
        return LandingGuess(scored.firstOrNull()?.first, bestPos, inCircle, scored.take(5))
    }
}
```

### File: inflight/engine/Tools.kt

```kotlin
package inflight.engine

import java.util.Locale
import kotlin.math.cos

/** Reads a reference track from a file the user downloaded before the flight. */
object TrackImport {
    private val COORDS = Regex("<coordinates>([\\s\\S]*?)</coordinates>", RegexOption.IGNORE_CASE)
    private val GPX_PT = Regex("<(?:trkpt|rtept|wpt)\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val LAT_ATTR = Regex("\\blat\\s*=\\s*\"([-0-9.eE+]+)\"")
    private val LON_ATTR = Regex("\\blon\\s*=\\s*\"([-0-9.eE+]+)\"")
    private val DECIMAL = Regex("-?\\d+\\.\\d+")

    /**
     * Extracts points from KML (<coordinates>lon,lat[,alt] ...</coordinates>), GPX (trkpt, rtept or wpt with lat and
     * lon attributes), or plain text / CSV with a latitude and a longitude on each line (the first two decimal
     * numbers in range, so "50.1, 14.2" and flight-tracker CSV exports both work).
     */
    fun parse(text: String): List<LatLon> {
        val kml = COORDS.findAll(text).toList()
        if (kml.isNotEmpty()) {
            val out = ArrayList<LatLon>()
            for (m in kml) {
                for (tuple in m.groupValues[1].trim().split(Regex("\\s+"))) {
                    val p = tuple.split(",")
                    if (p.size >= 2) {
                        val lon = p[0].toDoubleOrNull()
                        val lat = p[1].toDoubleOrNull()
                        if (lat != null && lon != null && valid(lat, lon)) out.add(LatLon(lat, lon))
                    }
                }
            }
            return dedupe(out)
        }
        val gpx = GPX_PT.findAll(text).toList()
        if (gpx.isNotEmpty()) {
            val out = ArrayList<LatLon>()
            for (m in gpx) {
                val lat = LAT_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = LON_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                if (lat != null && lon != null && valid(lat, lon)) out.add(LatLon(lat, lon))
            }
            return dedupe(out)
        }
        val out = ArrayList<LatLon>()
        for (line in text.lineSequence()) {
            val nums = DECIMAL.findAll(line).mapNotNull { it.value.toDoubleOrNull() }.toList()
            for (i in 0 until nums.size - 1) {
                if (valid(nums[i], nums[i + 1])) {
                    out.add(LatLon(nums[i], nums[i + 1]))
                    break
                }
            }
        }
        return dedupe(out)
    }

    private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    private fun dedupe(p: List<LatLon>): List<LatLon> {
        val out = ArrayList<LatLon>()
        for (x in p) if (out.isEmpty() || Geo.havKm(out.last().lat, out.last().lon, x.lat, x.lon) > 0.05) out.add(x)
        return out
    }

    /**
     * Turns a recorded or published track into en-route points for a FlightPlan: drops the parts within trimKm of
     * either airport (departure and arrival paths depend on the runway in use) and keeps a point every spacingKm.
     */
    fun toRoute(points: List<LatLon>, origin: LatLon, dest: LatLon, trimKm: Double = 30.0, spacingKm: Double = 20.0): List<LatLon> {
        val mid = points.filter {
            Geo.havKm(it.lat, it.lon, origin.lat, origin.lon) > trimKm && Geo.havKm(it.lat, it.lon, dest.lat, dest.lon) > trimKm
        }
        if (mid.isEmpty()) return emptyList()
        val out = ArrayList<LatLon>()
        out.add(mid.first())
        var acc = 0.0
        for (i in 1 until mid.size) {
            acc += Geo.havKm(mid[i - 1].lat, mid[i - 1].lon, mid[i].lat, mid[i].lon)
            if (acc >= spacingKm) {
                out.add(mid[i])
                acc = 0.0
            }
        }
        if (out.last() != mid.last()) out.add(mid.last())
        return out
    }
}

/** A position with a time (Unix milliseconds). */
data class TimedPoint(val tMillis: Long, val lat: Double, val lon: Double)

/** One logged estimate. */
data class LoggedEstimate(val tMillis: Long, val lat: Double, val lon: Double, val r95Km: Double)

/** Reads a timestamped track of the real flight (for checking accuracy after landing). */
object TimedTrackImport {
    private val WHEN = Regex("<when>([^<]+)</when>", RegexOption.IGNORE_CASE)
    private val GX_COORD = Regex("<gx:coord>([^<]+)</gx:coord>", RegexOption.IGNORE_CASE)
    private val PLACEMARK = Regex("<Placemark\\b[\\s\\S]*?</Placemark>", RegexOption.IGNORE_CASE)
    private val COORD1 = Regex("<coordinates>\\s*([-0-9.eE+]+)\\s*,\\s*([-0-9.eE+]+)", RegexOption.IGNORE_CASE)
    private val TRKPT = Regex("<trkpt\\b([^>]*)>([\\s\\S]*?)</trkpt>", RegexOption.IGNORE_CASE)
    private val TIME = Regex("<time>([^<]+)</time>", RegexOption.IGNORE_CASE)
    private val LAT_ATTR = Regex("\\blat\\s*=\\s*\"([-0-9.eE+]+)\"")
    private val LON_ATTR = Regex("\\blon\\s*=\\s*\"([-0-9.eE+]+)\"")
    private val ISO = Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?")
    private val UNIX = Regex("(?<![\\d.])(1[5-9]\\d{8})(\\d{3})?(?![\\d.])")   // seconds, or milliseconds
    private val DECIMAL = Regex("-?\\d+\\.\\d+")

    /** Parses ISO 8601 times such as 2026-11-16T11:04:12Z (no zone means UTC); null if unreadable. */
    fun parseIso(s: String): Long? {
        var t = s.trim().replace(' ', 'T')
        if (t.length < 16) return null
        if (t.length == 16) t += ":00"
        if (!t.endsWith("Z") && !Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(t.substring(10))) t += "Z"
        return try {
            java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * KML gx:Track (when + gx:coord), KML placemarks with a TimeStamp and a Point, GPX track points with a time,
     * or CSV / text lines holding a Unix time (seconds or milliseconds) or an ISO time plus a latitude and a
     * longitude (flight-tracker CSV exports and the app's own "tMillis,lat,lon" files work). Sorted by time.
     */
    fun parse(text: String): List<TimedPoint> {
        val out = ArrayList<TimedPoint>()
        val whens = WHEN.findAll(text).map { it.groupValues[1] }.toList()
        val coords = GX_COORD.findAll(text).map { it.groupValues[1] }.toList()
        if (coords.isNotEmpty() && whens.size >= coords.size) {
            val w = whens.takeLast(coords.size)
            for (i in coords.indices) {
                val p = coords[i].trim().split(Regex("\\s+"))
                val t = parseIso(w[i])
                val lon = p.getOrNull(0)?.toDoubleOrNull()
                val lat = p.getOrNull(1)?.toDoubleOrNull()
                if (t != null && lat != null && lon != null) out.add(TimedPoint(t, lat, lon))
            }
        }
        if (out.isEmpty()) {
            for (m in PLACEMARK.findAll(text)) {
                val t = WHEN.find(m.value)?.groupValues?.get(1)?.let { parseIso(it) } ?: continue
                val c = COORD1.find(m.value) ?: continue
                val lon = c.groupValues[1].toDoubleOrNull()
                val lat = c.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null) out.add(TimedPoint(t, lat, lon))
            }
        }
        if (out.isEmpty()) {
            for (m in TRKPT.findAll(text)) {
                val lat = LAT_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                val lon = LON_ATTR.find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()
                val t = TIME.find(m.groupValues[2])?.groupValues?.get(1)?.let { parseIso(it) }
                if (lat != null && lon != null && t != null) out.add(TimedPoint(t, lat, lon))
            }
        }
        if (out.isEmpty()) {
            for (line in text.lineSequence()) {
                val u = UNIX.find(line)
                val t = u?.let { m -> m.value.toLongOrNull()?.let { if (m.groupValues[2].isEmpty()) it * 1000 else it } }
                    ?: ISO.find(line)?.value?.let { parseIso(it) } ?: continue
                val rest = line.replace(ISO, " ").replace(UNIX, " ")
                val nums = DECIMAL.findAll(rest).mapNotNull { it.value.toDoubleOrNull() }.toList()
                for (i in 0 until nums.size - 1) {
                    if (nums[i] in -90.0..90.0 && nums[i + 1] in -180.0..180.0) {
                        out.add(TimedPoint(t, nums[i], nums[i + 1]))
                        break
                    }
                }
            }
        }
        return out.sortedBy { it.tMillis }
    }
}

/** Compares logged estimates with the real track: the acceptance test for real flights. */
object Evaluation {
    data class Result(
        val n: Int, val medianKm: Double, val p90Km: Double, val maxKm: Double,
        /** Share of estimates whose 95% circle held the true position. */
        val coverage: Double,
        /** (time, error km) for every estimate inside the track's time span. */
        val errors: List<Pair<Long, Double>>,
    )

    /** True position at time t, interpolated between track points; null outside the track's time span. */
    fun truthAt(truth: List<TimedPoint>, t: Long): LatLon? {
        if (truth.isEmpty() || t < truth.first().tMillis || t > truth.last().tMillis) return null
        var lo = 0
        var hi = truth.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (truth[mid].tMillis <= t) lo = mid else hi = mid
        }
        val a = truth[lo]
        val b = truth[hi]
        if (b.tMillis == a.tMillis) return LatLon(a.lat, a.lon)
        val f = (t - a.tMillis).toDouble() / (b.tMillis - a.tMillis)
        return Geo.gcInterp(a.lat, a.lon, b.lat, b.lon, f)
    }

    fun compare(estimates: List<LoggedEstimate>, truth: List<TimedPoint>): Result {
        val errs = ArrayList<Pair<Long, Double>>()
        var inside = 0
        for (e in estimates) {
            val p = truthAt(truth, e.tMillis) ?: continue
            val d = Geo.havKm(e.lat, e.lon, p.lat, p.lon)
            errs.add(Pair(e.tMillis, d))
            if (d <= e.r95Km) inside++
        }
        if (errs.isEmpty()) return Result(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, errs)
        val s = errs.map { it.second }.sorted()
        fun q(p: Double): Double {
            val pos = p * (s.size - 1)
            val i = pos.toInt()
            return if (i + 1 < s.size) s[i] + (s[i + 1] - s[i]) * (pos - i) else s[i]
        }
        return Result(errs.size, q(0.5), q(0.9), s.last(), inside.toDouble() / errs.size, errs)
    }

    /** Reads lines "tMillis,lat,lon,r95Km" (the app's estimates log); other lines are skipped. */
    fun parseEstimates(text: String): List<LoggedEstimate> = text.lineSequence().mapNotNull { line ->
        val p = line.trim().split(",")
        if (p.size < 4) return@mapNotNull null
        val t = p[0].toLongOrNull() ?: return@mapNotNull null
        val lat = p[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
        val r = p[3].toDoubleOrNull() ?: return@mapNotNull null
        LoggedEstimate(t, lat, lon, r)
    }.toList()
}

/** Winds aloft from a forecast service, reduced to the one number the filter uses: the mean tailwind. */
object WindForecast {
    /** Points along the cruise part of a track (15% to 85% of its length) where to ask for the wind. */
    fun samplePoints(track: Track, count: Int = 8): List<LatLon> = (0 until count).map {
        val f = 0.15 + 0.7 * (it + 0.5) / count
        val p = track.pos(f * track.length)
        LatLon(p.lat, p.lon)
    }

    /** Standard pressure level nearest to a cruise altitude: 300 hPa (about 9.2 km), 250 (10.4 km) or 200 (11.8 km). */
    fun pressureLevelFor(cruiseAltKm: Double): Int = when {
        cruiseAltKm < 9.8 -> 300
        cruiseAltKm < 11.1 -> 250
        else -> 200
    }

    /**
     * Open-Meteo forecast request for wind speed (km/h) and direction (degrees the wind blows from) at one pressure
     * level, for all points at one hour. hourUtc: "yyyy-MM-ddTHH:00". With several points the response is a JSON
     * array with one object per point, each holding hourly.wind_speed_<level>hPa and hourly.wind_direction_<level>hPa.
     */
    fun openMeteoUrl(points: List<LatLon>, hourUtc: String, levelHpa: Int = 250): String {
        val lat = points.joinToString(",") { String.format(Locale.US, "%.3f", it.lat) }
        val lon = points.joinToString(",") { String.format(Locale.US, "%.3f", it.lon) }
        return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=wind_speed_${levelHpa}hPa,wind_direction_${levelHpa}hPa" +
            "&wind_speed_unit=kmh&timezone=GMT&start_hour=$hourUtc&end_hour=$hourUtc"
    }

    /**
     * Mean along-track wind in km/h (positive = tailwind) from wind speeds and the directions the wind blows FROM,
     * at points along the track.
     */
    fun alongTrackKmh(track: Track, points: List<LatLon>, speedKmh: List<Double>, fromDeg: List<Double>): Double {
        var sum = 0.0
        var n = 0
        for (i in points.indices) {
            val v = speedKmh.getOrNull(i) ?: continue
            val d = fromDeg.getOrNull(i) ?: continue
            if (v.isNaN() || d.isNaN()) continue
            val s = track.project(points[i].lat, points[i].lon).first
            val brg = track.bearingAt(s)
            sum += -v * cos((d - brg) * D2R)
            n++
        }
        return if (n == 0) 0.0 else sum / n
    }
}
```

### File: inflight/engine/SelfTest.kt

```kotlin
package inflight.engine

import kotlin.math.abs

/** One self-test check, for the Diagnostics screen. */
data class Check(val name: String, val passed: Boolean, val detail: String)

/**
 * Checks that the engine in this build still computes what the reference implementation computes.
 * The expected values come from the Python prototype (pygeomag for the magnetic model).
 * Run it once after every change to the engine files. SelfTestSim and SelfTestSensors add the slower checks.
 */
object SelfTest {
    private class MagCase(
        val name: String, val lat: Double, val lon: Double, val altKm: Double,
        val fNt: Double, val dip: Double, val decl: Double,
    )

    private const val YEAR = 2026.88
    private val MAG_CASES = listOf(
        MagCase("Skopje", 41.96162, 21.62137, 0.24, 47753.19, 59.2660, 5.4156),
        MagCase("Prague", 50.10114, 14.26296, 0.38, 49545.92, 66.2732, 5.1491),
        MagCase("Belgrade at 11 km", 44.80703, 20.33494, 11.0, 48293.46, 62.0162, 5.6030),
        MagCase("0N 0E", 0.0, 0.0, 0.0, 31789.66, -30.1983, -3.7842),
        MagCase("Cape Town", -33.9, 18.4, 0.0, 24964.40, -64.5962, -26.7935),
        MagCase("Reykjavik at 10 km", 64.1, -21.9, 10.0, 52349.38, 75.3869, -10.9582),
        MagCase("Tokyo", 35.7, 139.7, 0.0, 46899.38, 49.5715, -7.9454),
        MagCase("Ushuaia at 5 km", -54.8, -68.3, 5.0, 31062.65, -51.2679, 11.6091),
        MagCase("North Atlantic at 12 km", 55.0, -40.0, 12.0, 51376.22, 70.3442, -15.3093),
        MagCase("Nairobi", -1.3, 36.8, 1.8, 33460.18, -22.4490, 0.2586),
    )

    /** Magnetic model, geometry, reference track and file parsing: instant. */
    fun math(): List<Check> {
        val out = ArrayList<Check>()
        fun near(name: String, got: Double, want: Double, tol: Double) {
            out.add(Check(name, abs(got - want) <= tol, "got %.4f, expected %.4f (tolerance %.4f)".format(got, want, tol)))
        }

        // --- World Magnetic Model 2025 ---
        val wmm = Wmm2025(YEAR)
        for (c in MAG_CASES) {
            val m = wmm.field(c.lat, c.lon, c.altKm)
            val ok = abs(m.f - c.fNt) <= 1.0 && abs(m.dipDeg - c.dip) <= 0.002 && abs(m.declDeg - c.decl) <= 0.002
            out.add(
                Check(
                    "Magnetic model: ${c.name}", ok,
                    "F %.1f nT (expected %.1f), dip %.4f (expected %.4f), declination %.4f (expected %.4f)"
                        .format(m.f, c.fNt, m.dipDeg, c.dip, m.declDeg, c.decl),
                ),
            )
        }

        // --- geometry ---
        val skp = LatLon(41.96162, 21.62137)
        val prg = LatLon(50.10114, 14.26296)
        near("Distance Skopje-Prague, km", Geo.havKm(skp.lat, skp.lon, prg.lat, prg.lon), 1067.3928, 0.001)
        near("Bearing Skopje to Prague", Geo.bearingDeg(skp.lat, skp.lon, prg.lat, prg.lon), 330.4852, 0.001)
        near("Bearing Prague to Skopje", Geo.bearingDeg(prg.lat, prg.lon, skp.lat, skp.lon), 145.1723, 0.001)
        val d1 = Geo.destPoint(skp.lat, skp.lon, 330.0, 500.0)
        near("Destination point latitude", d1.lat, 45.812431, 1e-5)
        near("Destination point longitude", d1.lon, 18.397340, 1e-5)
        val d2 = Geo.destPoint(prg.lat, prg.lon, 245.0, -26.0)
        near("Backward destination point latitude", d2.lat, 50.199488, 1e-5)
        near("Backward destination point longitude", d2.lon, 14.594019, 1e-5)
        val mid = Geo.gcInterp(skp.lat, skp.lon, prg.lat, prg.lon, 0.5)
        near("Great-circle midpoint latitude", mid.lat, 46.090122, 1e-5)
        near("Great-circle midpoint longitude", mid.lon, 18.213912, 1e-5)
        near("Angle difference 350 - 10", Geo.angDiff(350.0, 10.0), -20.0, 1e-9)
        near("Angle difference 10 - 350", Geo.angDiff(10.0, 350.0), 20.0, 1e-9)

        // --- reference track of the example flight ---
        val sc = Scenarios.w64747()
        val track = sc.referenceTrack()
        near("Great-circle track length, km", sc.greatCircle().length, 1067.3928, 0.01)
        near("Published track length, km", track.length, 1085.3408, 0.01)
        val p1 = track.pos(500.0, 20.0)
        near("Track point (500 km, 20 km right) latitude", p1.lat, 46.209777, 1e-5)
        near("Track point (500 km, 20 km right) longitude", p1.lon, 19.499150, 1e-5)
        near("Track bearing at 500 km", p1.bearing, 320.4887, 0.001)
        val p2 = track.pos(1000.0, -10.0)
        near("Track point (1000 km, 10 km left) latitude", p2.lat, 49.428764, 1e-5)
        near("Track point (1000 km, 10 km left) longitude", p2.lon, 14.852513, 1e-5)
        val pr = track.project(p1.lat, p1.lon)
        near("Projection back onto the track: distance along", pr.first, 500.0, 1.0)
        near("Projection back onto the track: offset", pr.second, 20.0, 0.3)
        near("Cutoff rigidity at Skopje, GV", Cosmic.cutoffRigidity(skp.lat, skp.lon), 4.9448, 0.001)

        // --- file parsing ---
        val ap = Airports.parseCsv(
            "ident,iata,lat,lon,elev_m,runway_headings_true,name\n" +
                "LKPR,PRG,50.10087,14.25991,380,65;245;127;307,Václav Havel Airport Prague\n",
        )
        out.add(Check("Airport list parsing", ap.size == 1 && ap[0].runwayHeadings.size == 4 && ap[0].label == "PRG",
            "parsed ${ap.size} airport(s): ${ap.firstOrNull()?.let { "${it.label}, ${it.runwayHeadings.size} runway directions" } ?: "none"}"))
        val kml = TrackImport.parse("<kml><LineString><coordinates>21.6,41.9,0 20.3,44.8,10000 14.3,50.1,0</coordinates></LineString></kml>")
        val gpx = TrackImport.parse("<gpx><trk><trkseg><trkpt lat=\"41.9\" lon=\"21.6\"/><trkpt lon=\"20.3\" lat=\"44.8\"/></trkseg></trk></gpx>")
        val csv = TrackImport.parse("lat,lon\n41.9,21.6\n44.8, 20.3\n")
        out.add(Check("Track import (KML, GPX, CSV)",
            kml.size == 3 && kml[1].lat == 44.8 && gpx.size == 2 && gpx[1].lon == 20.3 && csv.size == 2,
            "KML ${kml.size} points, GPX ${gpx.size}, CSV ${csv.size} (expected 3, 2, 2)"))
        val timed = TimedTrackImport.parse(
            "Timestamp,UTC,Callsign,Position,Altitude,Speed,Direction\n" +
                "1794827040,2026-11-22T11:04:00Z,WZZ4747,\"41.961,21.621\",0,150,345\n" +
                "1794827100,2026-11-22T11:05:00Z,WZZ4747,\"42.050,21.590\",3000,250,340\n",
        )
        val half = Evaluation.truthAt(timed, 1_794_827_070_000L)
        val ms = TimedTrackImport.parse("1794827040000,41.961,21.621\n1794827100000,42.05,21.59\n")
        out.add(Check("Timed track import and interpolation",
            timed.size == 2 && timed[0].tMillis == 1_794_827_040_000L && half != null && abs(half.lat - 42.0055) < 0.001 &&
                ms.size == 2 && ms[1].tMillis == 1_794_827_100_000L,
            "${timed.size} timed points (and ${ms.size} with millisecond times); midpoint latitude ${half?.lat?.let { "%.4f".format(it) } ?: "none"} (expected 42.0055)"))
        return out
    }
}
```
