package `in`.nulltheory.waypoint.net

import `in`.nulltheory.waypoint.sim.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class Place(
    val name: String,
    val detail: String,
    val lat: Double,
    val lon: Double
) {
    val point: LatLng get() = LatLng(lat, lon)
}

/**
 * Photon client. Photon is built for autocomplete, needs no key, and is self-hostable, which
 * is the whole reason it is here rather than Nominatim.
 */
class Geocoder(private val baseUrl: String) {

    /**
     * @param near biases results towards the current map centre so local matches rank first.
     */
    suspend fun search(query: String, near: LatLng?, limit: Int = 8): List<Place> =
        withContext(Dispatchers.IO) {
            val url = (baseUrl.trimEnd('/') + "/api/").toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", query)
                ?.addQueryParameter("limit", limit.toString())
                ?.apply {
                    near?.let {
                        addQueryParameter("lat", it.lat.toString())
                        addQueryParameter("lon", it.lon.toString())
                    }
                }
                ?.build()
                ?: throw IOException("Bad geocoding server URL: $baseUrl")

            Http.client.newCall(Request.Builder().url(url).build()).await().use { res ->
                if (!res.isSuccessful) throw IOException("Photon returned HTTP ${res.code}")
                parseFeatures(res.body?.string().orEmpty())
            }
        }

    /** Used by long-press-to-set, so a dropped pin gets a readable label instead of digits. */
    suspend fun reverse(point: LatLng): Place? = withContext(Dispatchers.IO) {
        val url = (baseUrl.trimEnd('/') + "/reverse").toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("lat", point.lat.toString())
            ?.addQueryParameter("lon", point.lon.toString())
            ?.build()
            ?: return@withContext null

        Http.client.newCall(Request.Builder().url(url).build()).await().use { res ->
            if (!res.isSuccessful) return@withContext null
            parseFeatures(res.body?.string().orEmpty()).firstOrNull()
        }
    }

    private fun parseFeatures(body: String): List<Place> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>(features.length())

        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            // GeoJSON is [lon, lat]. Getting this backwards puts Bengaluru in the Indian Ocean.
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            val props = feature.optJSONObject("properties") ?: JSONObject()
            val name = props.optStringOrNull("name")
                ?: listOfNotNull(props.optStringOrNull("housenumber"), props.optStringOrNull("street"))
                    .takeIf { it.isNotEmpty() }?.joinToString(" ")
                ?: props.optStringOrNull("city")
                ?: String.format("%.5f, %.5f", lat, lon)

            val detail = listOfNotNull(
                props.optStringOrNull("city") ?: props.optStringOrNull("county"),
                props.optStringOrNull("state"),
                props.optStringOrNull("country")
            ).distinct().joinToString(", ")

            out += Place(name, detail, lat, lon)
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
