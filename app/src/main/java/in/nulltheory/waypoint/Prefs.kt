package `in`.nulltheory.waypoint

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper. Every server URL is editable so that anyone running their
 * own OSRM, Photon or tile instance can point the app at it without forking.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** osmdroid wants its own store to scribble in; keeping it separate keeps ours clean. */
    val osmdroidPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)

    var updateRateHz: Int
        get() = sp.getInt(KEY_RATE, DEFAULT_RATE_HZ)
        set(v) = sp.edit().putInt(KEY_RATE, v).apply()

    var defaultSpeedKmh: Int
        get() = sp.getInt(KEY_SPEED, DEFAULT_SPEED_KMH)
        set(v) = sp.edit().putInt(KEY_SPEED, v.coerceIn(0, MAX_SPEED_KMH)).apply()

    var loop: Boolean
        get() = sp.getBoolean(KEY_LOOP, false)
        set(v) = sp.edit().putBoolean(KEY_LOOP, v).apply()

    var jitter: Boolean
        get() = sp.getBoolean(KEY_JITTER, false)
        set(v) = sp.edit().putBoolean(KEY_JITTER, v).apply()

    var tileUrl: String
        get() = sp.getString(KEY_TILE, DEFAULT_TILE_URL)!!
        set(v) = sp.edit().putString(KEY_TILE, v.trim()).apply()

    var routerUrl: String
        get() = sp.getString(KEY_ROUTER, DEFAULT_ROUTER_URL)!!
        set(v) = sp.edit().putString(KEY_ROUTER, v.trim().trimEnd('/')).apply()

    var geocoderUrl: String
        get() = sp.getString(KEY_GEOCODER, DEFAULT_GEOCODER_URL)!!
        set(v) = sp.edit().putString(KEY_GEOCODER, v.trim().trimEnd('/')).apply()

    /** Last map centre, so a relaunch does not drop the user in the middle of the ocean. */
    var lastMapLat: Double
        get() = Double.fromBits(sp.getLong(KEY_MAP_LAT, DEFAULT_LAT.toRawBits()))
        set(v) = sp.edit().putLong(KEY_MAP_LAT, v.toRawBits()).apply()

    var lastMapLon: Double
        get() = Double.fromBits(sp.getLong(KEY_MAP_LON, DEFAULT_LON.toRawBits()))
        set(v) = sp.edit().putLong(KEY_MAP_LON, v.toRawBits()).apply()

    var lastMapZoom: Double
        get() = Double.fromBits(sp.getLong(KEY_MAP_ZOOM, DEFAULT_ZOOM.toRawBits()))
        set(v) = sp.edit().putLong(KEY_MAP_ZOOM, v.toRawBits()).apply()

    companion object {
        private const val FILE = "waypoint"

        private const val KEY_RATE = "update_rate_hz"
        private const val KEY_SPEED = "default_speed_kmh"
        private const val KEY_LOOP = "loop_route"
        private const val KEY_JITTER = "position_jitter"
        private const val KEY_TILE = "tile_url"
        private const val KEY_ROUTER = "router_url"
        private const val KEY_GEOCODER = "geocoder_url"
        private const val KEY_MAP_LAT = "map_lat"
        private const val KEY_MAP_LON = "map_lon"
        private const val KEY_MAP_ZOOM = "map_zoom"

        const val DEFAULT_RATE_HZ = 1
        const val DEFAULT_SPEED_KMH = 50
        const val MAX_SPEED_KMH = 150

        /**
         * The OSM Foundation tile servers are a shared community resource and their usage
         * policy discourages distributed apps. This default keeps the app working out of the
         * box; point it at your own instance in Settings for anything beyond bench testing.
         */
        const val DEFAULT_TILE_URL = "https://tile.openstreetmap.org/"
        const val DEFAULT_ROUTER_URL = "https://router.project-osrm.org"
        const val DEFAULT_GEOCODER_URL = "https://photon.komoot.io"

        /**
         * 4 Hz is here because consuming SDKs commonly cap there — the LightMetrics
         * LocationTracker sizes its speed ring buffer at 4/sec and drops external input
         * above it. Above that rate the history window silently shrinks.
         */
        val UPDATE_RATES = intArrayOf(1, 2, 4, 5, 10)

        // Bengaluru, purely so the first launch has something on screen.
        private const val DEFAULT_LAT = 12.9716
        private const val DEFAULT_LON = 77.5946
        private const val DEFAULT_ZOOM = 12.0
    }
}
