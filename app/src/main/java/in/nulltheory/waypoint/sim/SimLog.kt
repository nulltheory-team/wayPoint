package `in`.nulltheory.waypoint.sim

import android.util.Log
import java.util.Locale

/**
 * The logcat readout for a running simulation. This is the surface you actually watch next to
 * the SDK under test, so every line reports what was *injected*, after jitter — not what the
 * simulator computed. If the two ever disagree, the log is the truth.
 *
 *     adb logcat -s WaypointFix
 */
object SimLog {

    const val TAG = "WaypointFix"

    fun started(
        totalMeters: Double,
        pointCount: Int,
        emittingOn: String,
        fusedActive: Boolean,
        providers: List<String>,
        updateRateHz: Int,
        speedKmh: Int,
        jitter: Boolean,
        loop: Boolean
    ) {
        box(
            "SIMULATION STARTED",
            listOf(
                "route       ${km(totalMeters)} km · $pointCount points",
                "emitting on $emittingOn",
                "shadowing   ${providers.joinToString(", ")} (real fixes suppressed)",
                "fused       ${
                    if (fusedActive) "also feeding Play Services fused provider"
                    else "no Play Services on this device"
                }",
                "update rate $updateRateHz Hz  (every ${1000 / updateRateHz.coerceAtLeast(1)} ms)",
                "speed       $speedKmh km/h",
                "jitter      ${onOff(jitter)}",
                "loop        ${onOff(loop)}"
            )
        )
        Log.d(TAG, HEADER)
    }

    /**
     * One line per injected fix. Fixed-width columns on purpose: the digits change every tick
     * and shifting glyph widths make a live readout unreadable.
     */
    @Suppress("LongParameterList")
    fun fix(
        seq: Long,
        lat: Double,
        lon: Double,
        speedKmh: Int,
        speedMps: Double,
        bearing: Double,
        accuracyM: Float,
        altitudeM: Double,
        progress: Double,
        travelledMeters: Double,
        totalMeters: Double,
        status: String,
        dtSeconds: Double,
        jitter: Boolean
    ) {
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "#%05d │ %11.6f, %11.6f │ %3d km/h %6.2f m/s │ %03d° │ ±%4.1fm │ %5.0fm │ %3.0f%% │ %7s/%-7s km │ %-7s │ dt %5.3fs%s",
                seq,
                lat,
                lon,
                speedKmh,
                speedMps,
                normalisedBearing(bearing),
                accuracyM,
                altitudeM,
                progress * 100,
                km(travelledMeters),
                km(totalMeters),
                status,
                dtSeconds,
                if (jitter) " │ jittered" else ""
            )
        )
    }

    fun event(name: String, detail: String? = null) {
        Log.d(TAG, "──── $name${detail?.let { " · $it" } ?: ""}")
        // A status change shifts the meaning of the columns below it, so reprint the header.
        if (name == "RESUMED" || name == "RUN AGAIN") Log.d(TAG, HEADER)
    }

    fun stopped(reason: String, providers: List<String>) {
        box(
            "SIMULATION STOPPED",
            listOf(
                "reason      $reason",
                "providers   ${
                    if (providers.isEmpty()) "none were registered"
                    else "${providers.joinToString(", ")} deregistered"
                }",
                "real GPS    adb shell dumpsys location | grep -A5 \"gps provider\""
            )
        )
    }

    fun failed(reason: String) {
        box("SIMULATION FAILED", listOf("reason      $reason"))
    }

    // ---------------------------------------------------------------- formatting

    private const val HEADER =
        "seq   │ latitude     longitude   │ speed            │ hdg  │ acc    │ alt    │ pct  │ travelled/total │ state   │ tick"

    private fun box(title: String, lines: List<String>) {
        val width = (lines + title).maxOf { it.length } + 2
        Log.d(TAG, "┌" + "─".repeat(width) + "┐")
        Log.d(TAG, "│ " + title.padEnd(width - 1) + "│")
        Log.d(TAG, "├" + "─".repeat(width) + "┤")
        lines.forEach { Log.d(TAG, "│ " + it.padEnd(width - 1) + "│") }
        Log.d(TAG, "└" + "─".repeat(width) + "┘")
    }

    private fun km(meters: Double): String = String.format(Locale.US, "%.2f", meters / 1000.0)

    private fun normalisedBearing(bearing: Double): Int =
        ((bearing.toInt() % 360) + 360) % 360

    private fun onOff(value: Boolean): String = if (value) "on" else "off"
}
