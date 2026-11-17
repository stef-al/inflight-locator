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
