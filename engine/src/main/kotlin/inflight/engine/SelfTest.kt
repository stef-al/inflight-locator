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
