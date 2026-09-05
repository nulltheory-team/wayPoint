package `in`.nulltheory.waypoint.ui

import java.util.Locale

/** Readouts are compared against logcat, so they use a fixed locale and fixed precision. */
object Format {

    fun distance(meters: Double): String = when {
        meters < 1000 -> String.format(Locale.US, "%.0f m", meters)
        else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
    }

    /** Kilometres without a unit suffix, for the "10.4/27.4 km" telemetry pair. */
    fun km(meters: Double): String = String.format(Locale.US, "%.1f", meters / 1000.0)

    /**
     * Duration at a constant speed. Deliberately not OSRM's estimate: this is how long the
     * simulation will take, not a traffic-aware ETA.
     */
    fun duration(meters: Double, speedKmh: Int): String {
        if (speedKmh <= 0) return "—"
        val totalMinutes = (meters / 1000.0) / speedKmh * 60.0
        return when {
            totalMinutes < 1 -> "under a minute"
            totalMinutes < 60 -> "${totalMinutes.toInt()} min"
            else -> {
                val h = (totalMinutes / 60).toInt()
                val m = (totalMinutes % 60).toInt()
                if (m == 0) "$h h" else "$h h $m min"
            }
        }
    }

    fun coordinate(value: Double): String = String.format(Locale.US, "%.4f", value)

    fun bearing(degrees: Double): String =
        String.format(Locale.US, "%03d", ((degrees.toInt() % 360) + 360) % 360)
}
