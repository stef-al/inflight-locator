package inflight.engine

import kotlin.math.ceil
import kotlin.math.floor

/**
 * World Magnetic Model sampled on a latitude/longitude grid at two altitudes, with bilinear lookup.
 * Fast lookups for the Monte Carlo tests (hundreds of millions of queries); the app calls Wmm2025 directly.
 */
class MagGrid(
    private val lat0: Double, lat1: Double, private val lon0: Double, lon1: Double,
    private val step: Double, year: Double,
    private val alt0: Double = 0.0, private val alt1: Double = 13.0,
) : MagModel {
    private val ni = floor((lat1 - lat0) / step + 1e-9).toInt() + 1
    private val nj = floor((lon1 - lon0) / step + 1e-9).toInt() + 1
    private val f = Array(2) { DoubleArray(ni * nj) }
    private val dip = Array(2) { DoubleArray(ni * nj) }

    init {
        val wmm = Wmm2025(year)
        for (k in 0..1) {
            val alt = if (k == 0) alt0 else alt1
            for (i in 0 until ni) {
                for (j in 0 until nj) {
                    val m = wmm.field(lat0 + i * step, Geo.normLon(lon0 + j * step), alt)
                    f[k][i * nj + j] = m.f / 1000.0
                    dip[k][i * nj + j] = m.dipDeg
                }
            }
        }
    }

    val points: Int get() = ni * nj

    private fun bilin(a: DoubleArray, lat: Double, lon: Double): Double {
        val fi = (lat - lat0) / step
        val fj = (lon0 + (lon - lon0).mod(360.0) - lon0) / step
        val i0 = floor(fi).toInt().coerceIn(0, ni - 2)
        val j0 = floor(fj).toInt().coerceIn(0, nj - 2)
        val di = (fi - i0).coerceIn(0.0, 1.0)
        val dj = (fj - j0).coerceIn(0.0, 1.0)
        return a[i0 * nj + j0] * (1 - di) * (1 - dj) + a[(i0 + 1) * nj + j0] * di * (1 - dj) +
            a[i0 * nj + j0 + 1] * (1 - di) * dj + a[(i0 + 1) * nj + j0 + 1] * di * dj
    }

    override fun query(lat: Double, lon: Double, altKm: Double, out: DoubleArray) {
        val w = ((altKm - alt0) / (alt1 - alt0)).coerceIn(0.0, 1.0)
        out[0] = bilin(f[0], lat, lon) * (1 - w) + bilin(f[1], lat, lon) * w
        out[1] = bilin(dip[0], lat, lon) * (1 - w) + bilin(dip[1], lat, lon) * w
    }

    companion object {
        /** Grid that covers the given tracks with a generous margin (same rule as the Python prototype). */
        fun around(tracks: List<Track>, year: Double): MagGrid {
            var latMin = 90.0
            var latMax = -90.0
            var lonMin = 1e9
            var lonMax = -1e9
            for (t in tracks) {
                for (i in t.lat.indices) {
                    latMin = minOf(latMin, t.lat[i]); latMax = maxOf(latMax, t.lat[i])
                    lonMin = minOf(lonMin, t.lon[i]); lonMax = maxOf(lonMax, t.lon[i])
                }
            }
            val pad = 8.0
            val la0 = maxOf(-89.0, floor(latMin - pad))
            val la1 = minOf(89.0, ceil(latMax + pad))
            val lo0 = floor(lonMin - pad - 4)
            val lo1 = ceil(lonMax + pad + 4)
            val step = if ((la1 - la0) * (lo1 - lo0) < 2500) 0.5 else 1.0
            return MagGrid(la0, la1, lo0, lo1, step, year)
        }
    }
}
