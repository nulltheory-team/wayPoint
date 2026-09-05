package `in`.nulltheory.waypoint

import `in`.nulltheory.waypoint.sim.GeoUtils
import `in`.nulltheory.waypoint.sim.LatLng
import `in`.nulltheory.waypoint.sim.RouteSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    // One degree of latitude is ~111.2 km anywhere on the globe.
    @Test
    fun haversine_oneDegreeOfLatitude() {
        val d = GeoUtils.haversine(LatLng(0.0, 0.0), LatLng(1.0, 0.0))
        assertEquals(111195.0, d, 50.0)
    }

    @Test
    fun haversine_isZeroForSamePoint() {
        assertEquals(0.0, GeoUtils.haversine(LatLng(12.97, 77.59), LatLng(12.97, 77.59)), 1e-9)
    }

    @Test
    fun bearing_cardinalDirections() {
        val o = LatLng(12.9716, 77.5946)
        assertEquals(0.0, GeoUtils.bearing(o, LatLng(o.lat + 0.1, o.lon)), 0.5)
        assertEquals(90.0, GeoUtils.bearing(o, LatLng(o.lat, o.lon + 0.1)), 0.5)
        assertEquals(180.0, GeoUtils.bearing(o, LatLng(o.lat - 0.1, o.lon)), 0.5)
        assertEquals(270.0, GeoUtils.bearing(o, LatLng(o.lat, o.lon - 0.1)), 0.5)
    }

    @Test
    fun bearing_isNeverNegative() {
        val b = GeoUtils.bearing(LatLng(10.0, 10.0), LatLng(9.0, 9.0))
        assertTrue("bearing was $b", b in 0.0..360.0)
    }

    @Test
    fun interpolate_clampsOutOfRangeT() {
        val a = LatLng(0.0, 0.0)
        val b = LatLng(2.0, 4.0)
        assertEquals(1.0, GeoUtils.interpolate(a, b, 0.5).lat, 1e-9)
        assertEquals(2.0, GeoUtils.interpolate(a, b, 0.5).lon, 1e-9)
        assertEquals(0.0, GeoUtils.interpolate(a, b, -3.0).lat, 1e-9)
        assertEquals(2.0, GeoUtils.interpolate(a, b, 9.0).lat, 1e-9)
    }
}

class RouteSimulatorTest {

    /** A ~2.2 km straight north-south leg starting in Bengaluru. */
    private fun straightRoute() = listOf(
        LatLng(12.9716, 77.5946),
        LatLng(12.9916, 77.5946)
    )

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRoutesWithFewerThanTwoPoints() {
        RouteSimulator(listOf(LatLng(1.0, 1.0)))
    }

    // The milestone-1 acceptance check: 50 km/h is 13.889 m/s, so ten seconds is 138.9 m.
    @Test
    fun fiftyKmhForTenSecondsCoversOneHundredThirtyNineMetres() {
        val sim = RouteSimulator(straightRoute())
        val speed = 50.0 / 3.6
        repeat(10) { sim.advance(speed, 1.0) }

        assertEquals(138.9, sim.cursor, 0.1)
        val travelled = GeoUtils.haversine(straightRoute()[0], LatLng(sim.advance(0.0, 0.0)!!.lat, sim.advance(0.0, 0.0)!!.lon))
        assertEquals(138.9, travelled, 1.0)
    }

    @Test
    fun oneBigStepMatchesManySmallSteps() {
        val speed = 80.0 / 3.6
        val coarse = RouteSimulator(straightRoute()).apply { advance(speed, 10.0) }
        val fine = RouteSimulator(straightRoute()).apply { repeat(100) { advance(speed, 0.1) } }
        assertEquals(coarse.cursor, fine.cursor, 1e-6)
    }

    @Test
    fun zeroSpeedHoldsPositionButKeepsEmitting() {
        val sim = RouteSimulator(straightRoute())
        sim.advance(20.0, 5.0)
        val moving = sim.advance(0.0, 1.0)
        val stopped = sim.advance(0.0, 1.0)

        assertNotNull(stopped)
        assertEquals(moving!!.lat, stopped!!.lat, 1e-12)
        assertEquals(moving.lon, stopped.lon, 1e-12)
        assertEquals(0.0, stopped.speedMps, 1e-12)
    }

    @Test
    fun returnsNullOnceRouteIsConsumed() {
        val sim = RouteSimulator(straightRoute())
        assertNull(sim.advance(1000.0, 60.0))
    }

    @Test
    fun progressRunsFromZeroToOne() {
        val sim = RouteSimulator(straightRoute())
        val first = sim.advance(10.0, 1.0)!!
        assertTrue(first.progress > 0.0 && first.progress < 0.01)

        val half = RouteSimulator(straightRoute())
        half.advance(half.totalMeters / 2, 1.0)
        assertEquals(0.5, half.advance(0.0, 0.0)!!.progress, 1e-6)
    }

    @Test
    fun bearingFollowsTheCurrentSegment() {
        // North leg, then a right turn onto an east leg.
        val sim = RouteSimulator(
            listOf(
                LatLng(12.9716, 77.5946),
                LatLng(12.9816, 77.5946),
                LatLng(12.9816, 77.6046)
            )
        )
        assertEquals(0.0, sim.advance(100.0, 1.0)!!.bearing, 0.5)
        sim.advance(1200.0, 1.0)
        assertEquals(90.0, sim.advance(0.0, 0.0)!!.bearing, 0.5)
    }

    @Test
    fun segmentCursorSurvivesLargeJumpsAcrossManySegments() {
        // 200 points, 100 m apart in longitude at the equator (~111 m per 0.001 deg).
        val pts = (0 until 200).map { LatLng(0.0, it * 0.001) }
        val sim = RouteSimulator(pts)
        val target = sim.totalMeters * 0.75
        sim.seek(target)
        val fix = sim.advance(0.0, 0.0)!!
        assertEquals(0.75, fix.progress, 1e-6)
        assertEquals(pts.last().lon * 0.75, fix.lon, 1e-4)
    }

    @Test
    fun resetReturnsToTheStart() {
        val sim = RouteSimulator(straightRoute())
        sim.advance(30.0, 10.0)
        sim.reset()
        assertEquals(0.0, sim.cursor, 1e-12)
        val fix = sim.advance(0.0, 0.0)!!
        assertEquals(straightRoute()[0].lat, fix.lat, 1e-9)
        assertEquals(straightRoute()[0].lon, fix.lon, 1e-9)
    }

    // Falling silent at the end would read as signal loss to a consuming SDK, and speed
    // history that merely goes stale reads as unknown rather than zero.
    @Test
    fun destinationFixHoldsTheEndPointAtAStandstill() {
        val route = straightRoute()
        val sim = RouteSimulator(route)
        assertNull(sim.advance(1000.0, 60.0))

        val arrived = sim.destinationFix()
        assertEquals(route.last().lat, arrived.lat, 1e-9)
        assertEquals(route.last().lon, arrived.lon, 1e-9)
        assertEquals(0.0, arrived.speedMps, 1e-12)
        assertEquals(1.0, arrived.progress, 1e-12)
        assertEquals(sim.totalMeters, arrived.distanceMeters, 1e-9)
    }

    @Test
    fun destinationFixKeepsTheApproachBearing() {
        val sim = RouteSimulator(
            listOf(
                LatLng(12.9716, 77.5946),
                LatLng(12.9816, 77.5946),
                LatLng(12.9816, 77.6046)
            )
        )
        // Last leg runs due east, so that is the heading the vehicle arrives on.
        assertEquals(90.0, sim.destinationFix().bearing, 0.5)
    }

    @Test
    fun destinationFixIsStableAcrossRepeatedCalls() {
        val sim = RouteSimulator(straightRoute())
        val first = sim.destinationFix()
        val second = sim.destinationFix()
        assertEquals(first, second)
    }

    @Test
    fun handlesDegenerateZeroLengthSegments() {
        val p = LatLng(12.9716, 77.5946)
        val sim = RouteSimulator(listOf(p, p, LatLng(12.9816, 77.5946)))
        val fix = sim.advance(10.0, 1.0)
        assertNotNull(fix)
        assertTrue(fix!!.lat >= p.lat)
    }
}
