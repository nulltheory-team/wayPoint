package `in`.nulltheory.waypoint.sim

/**
 * One synthetic GPS sample. [progress] is 0..1 along the route, [distanceMeters] is metres
 * travelled from the start.
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val bearing: Double,
    val progress: Double,
    val distanceMeters: Double
)

/**
 * Walks a polyline at a caller-supplied speed. Pure math, no Android, no threads, no clock:
 * the caller owns timing and decides what to do when the route runs out.
 */
class RouteSimulator(val points: List<LatLng>) {

    init {
        require(points.size >= 2) { "A route needs at least two points, got ${points.size}" }
    }

    /** Prefix sums of segment lengths; `cumulative[i]` is the distance from start to `points[i]`. */
    private val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) {
            c[i] = c[i - 1] + GeoUtils.haversine(points[i - 1], points[i])
        }
    }

    val totalMeters: Double = cumulative.last()

    /** Metres travelled. */
    var cursor: Double = 0.0
        private set

    /** Cached segment index. Advances monotonically so a tick never rescans from zero. */
    private var segment = 0

    /**
     * Moves the vehicle forward by `speedMps * dtSeconds` and returns the resulting fix, or
     * null once the route is consumed. A speed of zero is legal and yields a stationary fix,
     * which is how dwell and idle detection get exercised.
     */
    fun advance(speedMps: Double, dtSeconds: Double): Fix? {
        cursor += speedMps * dtSeconds
        if (totalMeters <= 0.0 || cursor >= totalMeters) return null

        while (segment < points.size - 2 && cumulative[segment + 1] < cursor) segment++

        val segStart = cumulative[segment]
        val segLen = cumulative[segment + 1] - segStart
        val t = if (segLen > 0) (cursor - segStart) / segLen else 0.0

        val pos = GeoUtils.interpolate(points[segment], points[segment + 1], t)
        val bearing = GeoUtils.bearing(points[segment], points[segment + 1])
        return Fix(pos.lat, pos.lon, speedMps, bearing, cursor / totalMeters, cursor)
    }

    /**
     * The destination, at a standstill.
     *
     * Once the route is consumed the caller must keep emitting this rather than falling
     * silent. A consumer that stops receiving fixes sees signal loss, not a vehicle that has
     * arrived — and speed history that simply goes stale reads as "unknown", not as zero.
     */
    fun destinationFix(): Fix {
        val last = points.last()
        val previous = points[points.size - 2]
        return Fix(
            lat = last.lat,
            lon = last.lon,
            speedMps = 0.0,
            bearing = GeoUtils.bearing(previous, last),
            progress = 1.0,
            distanceMeters = totalMeters
        )
    }

    fun reset() {
        cursor = 0.0
        segment = 0
    }

    /** Jumps to an absolute distance along the route. Used when resuming a run. */
    fun seek(meters: Double) {
        cursor = meters.coerceIn(0.0, totalMeters)
        segment = 0
        while (segment < points.size - 2 && cumulative[segment + 1] < cursor) segment++
    }
}
