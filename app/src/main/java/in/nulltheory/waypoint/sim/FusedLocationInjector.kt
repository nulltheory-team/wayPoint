package `in`.nulltheory.waypoint.sim

import android.content.Context
import android.location.Location
import android.util.Log

/**
 * Feeds the Google Play Services fused location provider, when one exists.
 *
 * A consumer built on `FusedLocationProviderClient` does not see
 * `LocationManager.setTestProviderLocation` at all, so on a device that has Play Services it
 * would receive nothing from us. The LightMetrics `LocationReceiverCreator` picks its
 * receiver exactly this way: fused when GMS is present, raw LocationManager when it is not.
 *
 * **This is done entirely by reflection, on purpose.** The brief forbids any dependency on
 * `com.google.android.gms` — the target dashcams are AOSP builds with no Play Services, the
 * app must ship with only OSI-licensed dependencies, and it has to install and run on a unit
 * with no GMS at all. Linking the proprietary `play-services-location` artifact would break
 * all three. Reflection keeps the APK free of it: where GMS exists we drive it, and where it
 * does not every call here is a no-op that costs one failed `Class.forName` at startup.
 *
 * Requires the same `android:mock_location` appop as the LocationManager path.
 */
class FusedLocationInjector(private val context: Context) {

    private var client: Any? = null
    private var setMockModeMethod: java.lang.reflect.Method? = null
    private var setMockLocationMethod: java.lang.reflect.Method? = null
    private var enabled = false

    /** True once [enable] has successfully put a real fused client into mock mode. */
    val isActive: Boolean get() = enabled

    /**
     * Puts the fused provider into mock mode. Returns false when Play Services is absent
     * (the normal case on the target hardware) or when the appop has not been granted.
     */
    fun enable(): Boolean {
        if (enabled) return true
        return runCatching {
            val servicesClass = Class.forName("com.google.android.gms.location.LocationServices")
            val clientClass =
                Class.forName("com.google.android.gms.location.FusedLocationProviderClient")

            val instance = servicesClass
                .getMethod("getFusedLocationProviderClient", Context::class.java)
                .invoke(null, context)
                ?: return false

            val setMockMode =
                clientClass.getMethod("setMockMode", Boolean::class.javaPrimitiveType)
            val setMockLocation =
                clientClass.getMethod("setMockLocation", Location::class.java)

            setMockMode.invoke(instance, true)

            client = instance
            setMockModeMethod = setMockMode
            setMockLocationMethod = setMockLocation
            enabled = true
            Log.i(TAG, "Play Services present; also feeding the fused provider")
            true
        }.getOrElse { error ->
            when (error) {
                // The overwhelmingly common case on an AOSP dashcam. Not a failure.
                is ClassNotFoundException ->
                    Log.i(TAG, "No Play Services on this device; LocationManager only")
                else ->
                    Log.w(TAG, "Could not enter fused mock mode", error)
            }
            false
        }
    }

    fun push(location: Location) {
        if (!enabled) return
        runCatching { setMockLocationMethod?.invoke(client, location) }
            .onFailure { Log.w(TAG, "setMockLocation failed", it) }
    }

    /** Hands the fused provider back to real GPS. Safe to call when never enabled. */
    fun disable() {
        if (!enabled) return
        runCatching { setMockModeMethod?.invoke(client, false) }
            .onFailure { Log.w(TAG, "Could not leave fused mock mode", it) }
        client = null
        setMockModeMethod = null
        setMockLocationMethod = null
        enabled = false
    }

    private companion object {
        const val TAG = "FusedLocationInjector"
    }
}
