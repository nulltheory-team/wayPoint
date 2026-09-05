package `in`.nulltheory.waypoint.sim

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plain geodesy. No Android imports here on purpose: everything in this file and in
 * [RouteSimulator] runs on a bare JVM so it can be unit tested without a device.
 */
data class LatLng(val lat: Double, val lon: Double)

object GeoUtils {

    /** IUGG mean Earth radius, metres. */
    const val EARTH_RADIUS_M = 6_371_008.8

    private const val DEG = Math.PI / 180.0
    private const val RAD = 180.0 / Math.PI

    /** Great-circle distance in metres. */
    fun haversine(a: LatLng, b: LatLng): Double {
        val lat1 = a.lat * DEG
        val lat2 = b.lat * DEG
        val dLat = (b.lat - a.lat) * DEG
        val dLon = (b.lon - a.lon) * DEG
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from [a] to [b], degrees clockwise from true north, in [0, 360). */
    fun bearing(a: LatLng, b: LatLng): Double {
        val lat1 = a.lat * DEG
        val lat2 = b.lat * DEG
        val dLon = (b.lon - a.lon) * DEG
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (atan2(y, x) * RAD + 360.0) % 360.0
    }

    /**
     * Linear interpolation between two points. Route segments are tens of metres, where the
     * difference against a great-circle interpolation is well under a centimetre.
     */
    fun interpolate(a: LatLng, b: LatLng, t: Double): LatLng {
        val c = t.coerceIn(0.0, 1.0)
        return LatLng(a.lat + (b.lat - a.lat) * c, a.lon + (b.lon - a.lon) * c)
    }

    /** Total length of a polyline in metres. */
    fun pathLength(points: List<LatLng>): Double {
        var sum = 0.0
        for (i in 0 until points.size - 1) sum += haversine(points[i], points[i + 1])
        return sum
    }
}
