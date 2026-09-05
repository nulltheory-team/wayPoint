package `in`.nulltheory.waypoint

import android.os.Bundle
import android.text.method.LinkMovementMethod
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import `in`.nulltheory.waypoint.databinding.ActivityLicensesBinding
import kotlin.math.roundToInt

class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
                topMargin = bars.top
            }
            binding.scroll.updatePadding(bottom = bars.bottom + (24 * resources.displayMetrics.density).roundToInt())
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.licenseText.text = HtmlCompat.fromHtml(BODY, HtmlCompat.FROM_HTML_MODE_COMPACT)
        binding.licenseText.movementMethod = LinkMovementMethod.getInstance()
    }

    private companion object {
        val BODY = """
            <p><b>Waypoint</b> is licensed under the Apache License 2.0.</p>

            <p><b>OpenStreetMap data</b> — ODbL 1.0<br>
            Map data © OpenStreetMap contributors. The attribution is shown on the map itself,
            as the licence requires.</p>

            <p><b>osmdroid</b> — Apache-2.0<br>
            <a href="https://github.com/osmdroid/osmdroid">github.com/osmdroid/osmdroid</a></p>

            <p><b>OkHttp / Okio</b> — Apache-2.0<br>
            <a href="https://square.github.io/okhttp/">square.github.io/okhttp</a></p>

            <p><b>Kotlin standard library and kotlinx.coroutines</b> — Apache-2.0<br>
            <a href="https://kotlinlang.org">kotlinlang.org</a></p>

            <p><b>AndroidX and Material Components for Android</b> — Apache-2.0<br>
            <a href="https://github.com/material-components/material-components-android">material-components-android</a></p>

            <p><b>Photon</b> — Apache-2.0<br>
            Geocoding service courtesy of Komoot. The public instance is a shared community
            resource; heavy or automated use should point at a self-hosted instance, which is
            why the URL is editable in Settings.<br>
            <a href="https://github.com/komoot/photon">github.com/komoot/photon</a></p>

            <p><b>OSRM</b> — BSD-2-Clause<br>
            The demo server at router.project-osrm.org is best-effort and not intended for
            heavy use. Self-host and change the routing server in Settings.<br>
            <a href="https://github.com/Project-OSRM/osrm-backend">github.com/Project-OSRM/osrm-backend</a></p>

            <p><b>Tile servers</b><br>
            The default tile source is the OpenStreetMap Foundation's, whose usage policy
            discourages distributed applications. Point the tile server at your own instance
            in Settings for anything beyond bench testing.<br>
            <a href="https://operations.osmfoundation.org/policies/tiles/">OSMF tile usage policy</a></p>
        """.trimIndent()
    }
}
