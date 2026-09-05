package `in`.nulltheory.waypoint

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import `in`.nulltheory.waypoint.databinding.ActivitySettingsBinding
import `in`.nulltheory.waypoint.databinding.RowSettingBinding
import `in`.nulltheory.waypoint.databinding.RowSwitchBinding
import `in`.nulltheory.waypoint.sim.MockPermission
import kotlin.math.roundToInt

/**
 * Screen 4. A plain preference-style list rather than androidx.preference, which would pull a
 * whole library in for eleven rows.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
                topMargin = bars.top
            }
            binding.scroll.updatePadding(bottom = bars.bottom + dp(24))
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        bindMockLocation()
        bindSimulation()
        bindServices()
        bindAbout()
    }

    override fun onResume() {
        super.onResume()
        bindMockLocation()
    }

    // ---------------------------------------------------------------- sections

    private fun bindMockLocation() {
        val granted = MockPermission.isGranted(this)
        binding.rowStatus.apply {
            rowTitle.text = getString(R.string.status)
            rowValue.text = getString(if (granted) R.string.granted else R.string.not_granted)
            rowValue.setTextColor(
                ContextCompat.getColor(
                    this@SettingsActivity,
                    if (granted) R.color.accent else R.color.warn
                )
            )
            rowChevron.setImageResource(
                if (granted) R.drawable.ic_check else R.drawable.ic_warning
            )
            androidx.core.widget.ImageViewCompat.setImageTintList(
                rowChevron,
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@SettingsActivity,
                        if (granted) R.color.accent else R.color.warn
                    )
                )
            )
            root.isClickable = false
            root.setOnLongClickListener {
                copyToClipboard(MockPermission.adbCommand(packageName))
                true
            }
        }

        binding.rowDevOptions.apply {
            rowTitle.text = getString(R.string.open_dev_options)
            rowSummary.visibility = View.VISIBLE
            rowSummary.text = MockPermission.adbCommand(packageName)
            rowValue.text = ""
            root.setOnClickListener {
                if (!MockPermission.openDeveloperOptions(this@SettingsActivity)) {
                    toast(getString(R.string.no_dev_options))
                }
            }
            root.setOnLongClickListener {
                copyToClipboard(MockPermission.adbCommand(packageName))
                true
            }
        }
    }

    private fun bindSimulation() {
        // Higher rates matter for SDKs that expect sub-second fixes.
        binding.rowUpdateRate.bindChoice(
            title = getString(R.string.update_rate),
            value = { getString(R.string.update_rate_value, prefs.updateRateHz) },
            onClick = { showUpdateRateDialog() },
            onReset = { prefs.updateRateHz = Prefs.DEFAULT_RATE_HZ }
        )

        binding.rowDefaultSpeed.bindChoice(
            title = getString(R.string.default_speed),
            value = { getString(R.string.default_speed_value, prefs.defaultSpeedKmh) },
            onClick = { showDefaultSpeedDialog() },
            onReset = { prefs.defaultSpeedKmh = Prefs.DEFAULT_SPEED_KMH }
        )

        binding.rowLoop.bindSwitch(
            title = getString(R.string.loop_route),
            summary = getString(R.string.loop_route_summary),
            get = { prefs.loop },
            set = { prefs.loop = it }
        )

        binding.rowJitter.bindSwitch(
            title = getString(R.string.position_jitter),
            summary = getString(R.string.position_jitter_summary),
            get = { prefs.jitter },
            set = { prefs.jitter = it }
        )
    }

    private fun bindServices() {
        binding.rowTileServer.bindUrl(
            title = getString(R.string.tile_server),
            hint = getString(R.string.tile_url_hint),
            get = { prefs.tileUrl },
            set = {
                prefs.tileUrl = it
                toast(getString(R.string.restart_for_tiles))
            },
            default = Prefs.DEFAULT_TILE_URL
        )

        binding.rowRoutingServer.bindUrl(
            title = getString(R.string.routing_server),
            hint = null,
            get = { prefs.routerUrl },
            set = { prefs.routerUrl = it },
            default = Prefs.DEFAULT_ROUTER_URL
        )

        binding.rowGeocodingServer.bindUrl(
            title = getString(R.string.geocoding_server),
            hint = null,
            get = { prefs.geocoderUrl },
            set = { prefs.geocoderUrl = it },
            default = Prefs.DEFAULT_GEOCODER_URL
        )
    }

    private fun bindAbout() {
        binding.rowVersion.apply {
            rowTitle.text = getString(R.string.version)
            rowValue.text = BuildConfig.VERSION_NAME
            rowChevron.visibility = View.GONE
            root.isClickable = false
        }
        binding.rowLicenses.apply {
            rowTitle.text = getString(R.string.licenses)
            rowValue.text = ""
            root.setOnClickListener {
                startActivity(Intent(this@SettingsActivity, LicensesActivity::class.java))
            }
        }
    }

    // ---------------------------------------------------------------- row helpers

    private fun RowSettingBinding.bindChoice(
        title: String,
        value: () -> String,
        onClick: () -> Unit,
        onReset: () -> Unit
    ) {
        rowTitle.text = title
        rowValue.text = value()
        root.setOnClickListener { onClick() }
        root.setOnLongClickListener {
            onReset()
            rowValue.text = value()
            toast(getString(R.string.reset_done))
            true
        }
    }

    private fun RowSettingBinding.bindUrl(
        title: String,
        hint: String?,
        get: () -> String,
        set: (String) -> Unit,
        default: String
    ) {
        rowTitle.text = title
        rowValue.text = get()
        root.setOnClickListener { showUrlDialog(title, hint, get, set, default) { rowValue.text = get() } }
        root.setOnLongClickListener {
            set(default)
            rowValue.text = get()
            toast(getString(R.string.reset_done))
            true
        }
    }

    private fun RowSwitchBinding.bindSwitch(
        title: String,
        summary: String,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ) {
        rowTitle.text = title
        rowSummary.text = summary
        rowSwitch.isChecked = get()
        root.setOnClickListener {
            val next = !get()
            set(next)
            rowSwitch.isChecked = next
        }
    }

    // ---------------------------------------------------------------- dialogs

    private fun showUpdateRateDialog() {
        val rates = Prefs.UPDATE_RATES
        // Rates above 4 Hz are offered but not as equals: consumers that size a ring buffer
        // at 4 samples/sec silently shrink their history window above it.
        val labels = rates.map {
            if (it > SAFE_MAX_RATE_HZ) getString(R.string.update_rate_value_above_cap, it)
            else getString(R.string.update_rate_value, it)
        }.toTypedArray()
        val current = rates.indexOf(prefs.updateRateHz).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.update_rate)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.updateRateHz = rates[which]
                binding.rowUpdateRate.rowValue.text =
                    getString(R.string.update_rate_value, prefs.updateRateHz)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDefaultSpeedDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.defaultSpeedKmh.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.default_speed)
            .setView(input.wrapped())
            .setPositiveButton(R.string.save) { _, _ ->
                val value = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                prefs.defaultSpeedKmh = value
                binding.rowDefaultSpeed.rowValue.text =
                    getString(R.string.default_speed_value, prefs.defaultSpeedKmh)
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                prefs.defaultSpeedKmh = Prefs.DEFAULT_SPEED_KMH
                binding.rowDefaultSpeed.rowValue.text =
                    getString(R.string.default_speed_value, prefs.defaultSpeedKmh)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showUrlDialog(
        title: String,
        hint: String?,
        get: () -> String,
        set: (String) -> Unit,
        default: String,
        after: () -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(get())
            setSelection(text.length)
            hint?.let { this.hint = it }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input.wrapped())
            .setPositiveButton(R.string.save) { _, _ ->
                val value = input.text.toString().trim()
                if (!value.startsWith("http://") && !value.startsWith("https://")) {
                    toast(getString(R.string.invalid_url))
                    return@setPositiveButton
                }
                set(value)
                after()
            }
            .setNeutralButton(R.string.reset_to_default) { _, _ ->
                set(default)
                after()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- helpers

    /** AlertDialog does not pad a bare view, and an EditText flush to the edge looks broken. */
    private fun EditText.wrapped(): View = FrameLayout(context).apply {
        val pad = dp(20)
        setPadding(pad, dp(8), pad, 0)
        addView(this@wrapped)
    }

    private fun copyToClipboard(text: String) {
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("waypoint", text))
        toast(getString(R.string.copied))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        /** Above this, consuming SDKs commonly start dropping or overwriting samples. */
        const val SAFE_MAX_RATE_HZ = 4
    }
}
