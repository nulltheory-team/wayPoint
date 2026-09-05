package `in`.nulltheory.waypoint.net

import `in`.nulltheory.waypoint.sim.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

data class Route(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val fromCache: Boolean = false
)

sealed interface RouteOutcome {
    data class Success(val route: Route) : RouteOutcome

    /** OSRM answered, but there is no road connecting these two points. */
    data object NoRoute : RouteOutcome

    data class Failed(val message: String) : RouteOutcome
}

/**
 * OSRM client. Asks for `geometries=geojson` rather than the default encoded polyline: a few
 * more bytes on the wire, and no hand-written polyline decoder to get an off-by-one wrong.
 */
class Router(private val baseUrl: String, private val cache: RouteCache?) {

    suspend fun route(from: LatLng, to: LatLng): RouteOutcome = withContext(Dispatchers.IO) {
        cache?.get(from, to)?.let { return@withContext RouteOutcome.Success(it) }

        val url = String.format(
            Locale.US,
            "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            baseUrl.trimEnd('/'), from.lon, from.lat, to.lon, to.lat
        )

        android.util.Log.i(TAG, "GET $url")
        try {
            Http.patientClient.newCall(Request.Builder().url(url).build()).await().use { res ->
                android.util.Log.i(TAG, "HTTP ${res.code}")
                if (!res.isSuccessful) {
                    return@withContext RouteOutcome.Failed("HTTP ${res.code}")
                }
                val json = JSONObject(res.body?.string().orEmpty())
                val code = json.optString("code")
                if (code != "Ok") return@withContext RouteOutcome.NoRoute

                val routes = json.optJSONArray("routes")
                if (routes == null || routes.length() == 0) return@withContext RouteOutcome.NoRoute

                val first = routes.getJSONObject(0)
                val coords = first.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: return@withContext RouteOutcome.NoRoute

                val points = ArrayList<LatLng>(coords.length())
                for (i in 0 until coords.length()) {
                    val pair = coords.optJSONArray(i) ?: continue
                    // GeoJSON order is [lon, lat].
                    points += LatLng(pair.getDouble(1), pair.getDouble(0))
                }
                if (points.size < 2) return@withContext RouteOutcome.NoRoute

                val route = Route(points, first.optDouble("distance", 0.0))
                cache?.put(from, to, route)
                RouteOutcome.Success(route)
            }
        } catch (e: IOException) {
            android.util.Log.w(TAG, "Route request failed", e)
            RouteOutcome.Failed(e.message ?: "network error")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Route response unusable", e)
            RouteOutcome.Failed(e.message ?: "bad response")
        }
    }

    private companion object {
        const val TAG = "Router"
    }
}
