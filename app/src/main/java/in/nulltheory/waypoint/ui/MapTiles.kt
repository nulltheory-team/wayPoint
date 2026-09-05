package `in`.nulltheory.waypoint.ui

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import kotlin.math.absoluteValue

/**
 * Builds an osmdroid tile source from the user-editable URL in Settings, so nobody has to
 * fork the app to point it at their own tile server.
 */
object MapTiles {

    private const val COPYRIGHT = "© OpenStreetMap contributors"

    fun from(url: String): OnlineTileSourceBase {
        val trimmed = url.trim()
        // Cache directory is keyed off the URL, so switching servers does not serve stale
        // tiles from the previous one.
        val name = "tiles-${trimmed.hashCode().absoluteValue}"

        return if (trimmed.contains("{z}") && trimmed.contains("{x}") && trimmed.contains("{y}")) {
            TemplateTileSource(name, trimmed)
        } else {
            XYTileSource(
                name, 0, 19, 256, ".png",
                arrayOf(if (trimmed.endsWith("/")) trimmed else "$trimmed/"),
                COPYRIGHT
            )
        }
    }

    /** Accepts the common `https://host/{z}/{x}/{y}.png` form as well as a bare prefix. */
    private class TemplateTileSource(name: String, private val template: String) :
        OnlineTileSourceBase(name, 0, 19, 256, ".png", arrayOf(template), COPYRIGHT) {

        override fun getTileURLString(pMapTileIndex: Long): String =
            template
                .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
                .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
                .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
    }
}
