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
