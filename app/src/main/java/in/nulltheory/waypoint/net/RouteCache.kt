package `in`.nulltheory.waypoint.net

import android.util.Log
import `in`.nulltheory.waypoint.sim.LatLng
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * A route fetched once should replay forever with no network, because the test bench will not
 * always have wifi. Backed by plain files in filesDir rather than SharedPreferences so a
 * 400-point geometry does not get parsed on every app start.
 */
class RouteCache(private val dir: File) {

    init {
        runCatching { dir.mkdirs() }
    }

    fun get(server: String, from: LatLng, to: LatLng): Route? {
        val file = File(dir, key(server, from, to))
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val coords = json.getJSONArray("coords")
            val points = ArrayList<LatLng>(coords.length())
            for (i in 0 until coords.length()) {
                val pair = coords.getJSONArray(i)
                points += LatLng(pair.getDouble(1), pair.getDouble(0))
            }
            if (points.size < 2) null
            else Route(points, json.getDouble("distance"), fromCache = true)
        }.onFailure {
            Log.w(TAG, "Dropping unreadable cache entry ${file.name}", it)
            file.delete()
        }.getOrNull()
    }

    fun put(server: String, from: LatLng, to: LatLng, route: Route) {
        runCatching {
            val coords = JSONArray()
            route.points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
            File(dir, key(server, from, to)).writeText(
                JSONObject()
                    .put("distance", route.distanceMeters)
                    .put("coords", coords)
                    .toString()
            )
            prune()
        }.onFailure { Log.w(TAG, "Could not cache route", it) }
    }

    /** Keep the cache bounded; these are the only files we ever write. */
    private fun prune() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_ENTRIES).forEach { it.delete() }
    }

    /**
     * Five decimal places is roughly a metre, which is finer than any point a user can pick
     * by search or long-press, so two attempts at the same route hit the same entry.
     */
    private fun key(server: String, from: LatLng, to: LatLng): String =
        String.format(
            Locale.US, // a locale with a comma decimal separator would collide keys
            // The server is part of the identity: pointing Settings at a different OSRM
            // instance must not replay geometry the previous one returned.
            "%d_%.5f_%.5f__%.5f_%.5f.json",
            server.hashCode(), from.lat, from.lon, to.lat, to.lon
        ).replace('-', 'm')

    private companion object {
        const val TAG = "RouteCache"
        const val MAX_ENTRIES = 60
    }
}
