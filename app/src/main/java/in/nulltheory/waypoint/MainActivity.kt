package `in`.nulltheory.waypoint

import android.Manifest
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import `in`.nulltheory.waypoint.databinding.ActivityMainBinding
import `in`.nulltheory.waypoint.net.Geocoder
import `in`.nulltheory.waypoint.net.Place
import `in`.nulltheory.waypoint.net.Route
import `in`.nulltheory.waypoint.net.RouteCache
import `in`.nulltheory.waypoint.net.RouteOutcome
import `in`.nulltheory.waypoint.net.Router
import `in`.nulltheory.waypoint.sim.GeoUtils
import `in`.nulltheory.waypoint.sim.LatLng
import `in`.nulltheory.waypoint.sim.MockLocationService
import `in`.nulltheory.waypoint.sim.MockPermission
import `in`.nulltheory.waypoint.sim.RunStatus
import `in`.nulltheory.waypoint.sim.SimSnapshot
import `in`.nulltheory.waypoint.ui.Endpoints
import `in`.nulltheory.waypoint.ui.Field
import `in`.nulltheory.waypoint.ui.Format
import `in`.nulltheory.waypoint.ui.MapTiles
import `in`.nulltheory.waypoint.ui.SearchAdapter
import `in`.nulltheory.waypoint.ui.SimState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.roundToInt

/**
 * Screens 1 through 3: map and search, route preview, and the running simulation.
 *
 * There is one search box, used twice. Pick a start point, press Directions, pick a
 * destination, and the route is fetched on selection. Two boxes side by side read as a form
 * to fill in; one box reads as a question being asked, which is what this actually is.
 *
 * The simulation itself lives in [MockLocationService]; this class only binds and observes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var state: SimState = SimState.Idle
    private var endpoints = Endpoints()

    private lateinit var adapter: SearchAdapter
    private var searchJob: Job? = null
    private var routeJob: Job? = null

    /** Whether the result list is currently expanded. Orthogonal to [state]. */
    private var listOpen = false

    /** Guards the text watcher while the box is filled in programmatically. */
    private var suppressWatcher = false

    // Map overlays.
    private var routeLine: Polyline? = null
    private var travelledLine: Polyline? = null
    private var markerFrom: Marker? = null
    private var markerTo: Marker? = null
    private var markerVehicle: Marker? = null
    private var vehicleAnimator: ValueAnimator? = null
    private var routeCumulative: DoubleArray = DoubleArray(0)
    private var lastTrailUpdateMs = 0L

    private val serviceFlow = MutableStateFlow<MockLocationService?>(null)
    private val service: MockLocationService? get() = serviceFlow.value
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceFlow.value = (service as? MockLocationService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceFlow.value = null
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == false) {
                toast(getString(R.string.need_location_permission))
            }
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        configureOsmdroid()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyInsets()
        setUpMap()
        setUpSearch()
        setUpSheets()
        observeService()
        requestPermissions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        render()
    }

    override fun onStart() {
        super.onStart()
        bindService(MockLocationService.intent(this), connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    override fun onStop() {
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        // Settings is a separate Activity, so anything changed there lands on the way back:
        // the appop may now be granted, the update rate or loop flag may have moved, and the
        // preview's duration line is derived from the default speed.
        service?.applyPrefs()
        render()
    }

    override fun onPause() {
        binding.map.onPause()
        prefs.lastMapLat = binding.map.mapCenter.latitude
        prefs.lastMapLon = binding.map.mapCenter.longitude
        prefs.lastMapZoom = binding.map.zoomLevelDouble
        super.onPause()
    }

    override fun onDestroy() {
        vehicleAnimator?.cancel()
        binding.map.onDetach()
        super.onDestroy()
    }

    private fun onBack() {
        if (listOpen) {
            closeList()
            clearFocus()
            return
        }
        when (val s = state) {
            // Backing out of a live run keeps it running: watching the SDK under test means
            // switching to another app while the fake drive continues.
            is SimState.Live -> moveTaskToBack(true)
            // Arriving does not end the run — the service keeps injecting at the destination,
            // holding the wake lock and shadowing real GPS. Leaving screen 3 has to tear that
            // down, or it runs invisibly until the process dies.
            is SimState.Finished -> stopSimulation()
            is SimState.Routed -> backToDestination()
            SimState.Routing -> {
                routeJob?.cancel()
                backToDestination()
            }
            SimState.PickingDestination -> backToOrigin()
            SimState.OriginSet -> {
                setEndpoint(Field.ORIGIN, null)
                setBoxText("")
                transitionTo(SimState.Idle)
            }
            SimState.Idle -> finish()
        }
    }

    private fun backToDestination() {
        clearRoute()
        setBoxText(endpoints.to?.name.orEmpty())
        transitionTo(SimState.PickingDestination)
    }

    private fun backToOrigin() {
        clearRoute()
        setEndpoint(Field.DESTINATION, null)
        setBoxText(endpoints.from?.name.orEmpty())
        transitionTo(SimState.OriginSet)
    }

    // ---------------------------------------------------------------- setup

    private fun configureOsmdroid() {
        Configuration.getInstance().apply {
            load(this@MainActivity, prefs.osmdroidPrefs)
            // The OSM tile policy requires a real, identifying User-Agent.
            userAgentValue = BuildConfig.APPLICATION_ID
            osmdroidBasePath = File(cacheDir, "osmdroid").apply { mkdirs() }
            osmdroidTileCache = File(osmdroidBasePath, "tiles").apply { mkdirs() }
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The settings button is constrained to the card's top, so it inherits this.
            binding.searchCard.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                topMargin = bars.top + dp(12)
            }
            // Reserved even when no sheet is showing, so the map never sits under the nav bar.
            binding.sheetContainer.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun setUpMap() {
        val map = binding.map
        map.setTileSource(MapTiles.from(prefs.tileUrl))
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.setUseDataConnection(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(prefs.lastMapZoom)
        map.controller.setCenter(GeoPoint(prefs.lastMapLat, prefs.lastMapLon))

        // Long-press is the fastest path when the keyboard is awkward over scrcpy.
        map.overlays.add(
            0,
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p?.let { onMapLongPress(LatLng(it.latitude, it.longitude)) }
                    return true
                }
            })
        )

        binding.locateButton.setOnClickListener { centreOnLastKnownFix() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.zoomInButton.setOnClickListener { map.controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { map.controller.zoomOut() }

        // Grey the buttons out at the tile source's limits rather than letting them look
        // live and do nothing.
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false

            override fun onZoom(event: ZoomEvent?): Boolean {
                syncZoomButtons()
                return false
            }
        })
        syncZoomButtons()
    }

    private fun syncZoomButtons() {
        binding.zoomInButton.isEnabled = binding.map.canZoomIn()
        binding.zoomOutButton.isEnabled = binding.map.canZoomOut()
        binding.zoomInButton.alpha = if (binding.zoomInButton.isEnabled) 1f else DISABLED_ALPHA
        binding.zoomOutButton.alpha = if (binding.zoomOutButton.isEnabled) 1f else DISABLED_ALPHA
    }

    private fun setUpSearch() {
        adapter = SearchAdapter { place -> onPlacePicked(place) }
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter
        binding.results.itemAnimator = null

        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            val text = binding.searchInput.text.toString()
            if (hasFocus && text.length >= MIN_QUERY && !isPicked(text)) scheduleSearch(text)
        }
        binding.searchInput.doAfterTextChanged { text ->
            binding.clearSearch.visibility =
                if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (suppressWatcher) return@doAfterTextChanged
            onQueryTyped(text?.toString().orEmpty())
        }
        binding.clearSearch.setOnClickListener {
            setBoxText("")
            onQueryTyped("")
            binding.searchInput.requestFocus()
            showKeyboard()
        }

        // Tapping the collapsed origin line takes you back to editing the start point.
        binding.originCrumb.setOnClickListener {
            if (state is SimState.Live || state is SimState.Finished) return@setOnClickListener
            backToOrigin()
            binding.searchInput.requestFocus()
            showKeyboard()
        }
    }

    private fun setUpSheets() {
        binding.directionsButton.setOnClickListener { askForDestination() }
        binding.sheetRoute.startButton.setOnClickListener { startSimulation() }
        binding.sheetRoute.fixThisButton.setOnClickListener { openDeveloperOptions() }
        binding.sheetRoute.adbCommand.setOnClickListener { copyAdbCommand() }

        binding.sheetRun.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            applySpeed(value.roundToInt(), syncSlider = false)
        }
        presetButtons().forEach { (kmh, button) ->
            button.setOnClickListener { applySpeed(kmh, syncSlider = true) }
        }
        binding.sheetRun.pauseButton.setOnClickListener { onPauseOrRunAgain() }
        binding.sheetRun.stopButton.setOnClickListener { stopSimulation() }
    }

    private fun presetButtons() = listOf(
        0 to binding.sheetRun.chip0,
        20 to binding.sheetRun.chip20,
        30 to binding.sheetRun.chip30,
        50 to binding.sheetRun.chip50,
        80 to binding.sheetRun.chip80,
        120 to binding.sheetRun.chip120
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeService() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                serviceFlow.filterNotNull()
                    .flatMapLatest { it.state }
                    .collect { onSnapshot(it) }
            }
        }
    }

    private fun requestPermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // ---------------------------------------------------------------- search

    /** The point the box is standing for right now. */
    private fun currentField(): Field = state.field

    private fun isPicked(text: String): Boolean {
        val picked = if (currentField() == Field.ORIGIN) endpoints.from else endpoints.to
        return picked != null && picked.name == text
    }

    private fun onQueryTyped(query: String) {
        // Typing over a chosen point discards it and steps the flow back one stage.
        when (currentField()) {
            Field.ORIGIN -> if (endpoints.from != null) {
                setEndpoint(Field.ORIGIN, null)
                transitionTo(SimState.Idle)
            }

            Field.DESTINATION -> if (endpoints.to != null) {
                clearRoute()
                setEndpoint(Field.DESTINATION, null)
                transitionTo(SimState.PickingDestination)
            }
        }
        scheduleSearch(query)
    }

    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        if (query.length < MIN_QUERY) {
            closeList()
            return
        }

        searchJob = lifecycleScope.launch {
            delay(DEBOUNCE_MS)
            binding.searchProgress.visibility = View.VISIBLE
            val geocoder = Geocoder(prefs.geocoderUrl)
            val centre = LatLng(binding.map.mapCenter.latitude, binding.map.mapCenter.longitude)
            val started = SystemClock.elapsedRealtime()
            try {
                val places = geocoder.search(query, centre)
                Log.i(TAG, "search '$query' -> ${places.size} in ${elapsed(started)}ms")
                binding.searchProgress.visibility = View.INVISIBLE
                openList(places)
            } catch (e: CancellationException) {
                // A newer keystroke superseded this one. Leave the progress bar alone: the
                // request that replaced it is still running.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "search '$query' failed after ${elapsed(started)}ms", e)
                binding.searchProgress.visibility = View.INVISIBLE
                openList(emptyList(), failure = getString(R.string.search_failed))
            }
        }
    }

    private fun openList(places: List<Place>, failure: String? = null) {
        listOpen = true
        adapter.submit(places)
        binding.results.visibility = if (places.isEmpty()) View.GONE else View.VISIBLE
        if (places.isNotEmpty()) {
            // Cap the list so it cannot swallow a small dashcam screen.
            binding.results.updateLayoutParams { height = dp(60) * minOf(places.size, MAX_ROWS) }
        }
        binding.emptyHint.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyHint.text = failure ?: getString(R.string.no_results)
    }

    private fun closeList() {
        listOpen = false
        adapter.submit(emptyList())
        binding.results.visibility = View.GONE
        binding.searchProgress.visibility = View.INVISIBLE
        renderHint()
    }

    private fun onPlacePicked(place: Place) {
        val field = currentField()
        setBoxText(place.name)
        setEndpoint(field, place)
        closeList()
        clearFocus()

        if (field == Field.ORIGIN) {
            binding.map.controller.animateTo(GeoPoint(place.lat, place.lon), 15.0, 400L)
            transitionTo(SimState.OriginSet)
        } else {
            // Selecting the destination is the commitment: go straight to the preview.
            fitTo(listOfNotNull(endpoints.from, endpoints.to).map { it.point })
            fetchRoute()
        }
    }

    /** Hands the one box over to the destination. */
    private fun askForDestination() {
        if (endpoints.from == null) return
        setBoxText("")
        closeList()
        transitionTo(SimState.PickingDestination)
        binding.searchInput.requestFocus()
        showKeyboard()
    }

    private fun onMapLongPress(point: LatLng) {
        if (state is SimState.Live || state is SimState.Finished) return

        val field = currentField()
        val placeholder = Place(
            "${Format.coordinate(point.lat)}, ${Format.coordinate(point.lon)}", "",
            point.lat, point.lon
        )
        setBoxText(placeholder.name)
        setEndpoint(field, placeholder)
        closeList()
        clearFocus()

        if (field == Field.ORIGIN) {
            transitionTo(SimState.OriginSet)
        } else {
            fitTo(listOfNotNull(endpoints.from, endpoints.to).map { it.point })
            fetchRoute()
        }

        // Upgrade the coordinate label to a readable name if the geocoder can supply one.
        lifecycleScope.launch {
            val named = runCatching { Geocoder(prefs.geocoderUrl).reverse(point) }.getOrNull()
                ?: return@launch
            val current = if (field == Field.ORIGIN) endpoints.from else endpoints.to
            if (current !== placeholder) return@launch
            val labelled = placeholder.copy(name = named.name, detail = named.detail)
            endpoints = if (field == Field.ORIGIN) endpoints.copy(from = labelled)
            else endpoints.copy(to = labelled)
            if (currentField() == field) setBoxText(labelled.name)
            renderCrumb()
        }
    }

    private fun setBoxText(text: String) {
        suppressWatcher = true
        binding.searchInput.setText(text)
        binding.searchInput.setSelection(text.length)
        binding.clearSearch.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        suppressWatcher = false
    }

    private fun setEndpoint(field: Field, place: Place?) {
        endpoints = if (field == Field.ORIGIN) endpoints.copy(from = place)
        else endpoints.copy(to = place)
        drawEndpointMarkers()
        renderCrumb()
    }

    // ---------------------------------------------------------------- routing

    private fun fetchRoute() {
        val from = endpoints.from ?: return
        val to = endpoints.to ?: return

        routeJob?.cancel()
        transitionTo(SimState.Routing)
        routeJob = lifecycleScope.launch {
            val router = Router(prefs.routerUrl, RouteCache(File(filesDir, "routes")))
            val started = SystemClock.elapsedRealtime()
            Log.i(TAG, "routing ${from.name} -> ${to.name}")
            val outcome = router.route(from.point, to.point)
            Log.i(TAG, "routing finished in ${elapsed(started)}ms: ${outcome.javaClass.simpleName}")
            when (outcome) {
                is RouteOutcome.Success -> {
                    drawRoute(outcome.route)
                    transitionTo(SimState.Routed(outcome.route))
                }

                RouteOutcome.NoRoute -> {
                    // Markers stay put so the user can nudge one and try again. No retry
                    // button: asking the same server again cannot invent a road.
                    transitionTo(SimState.PickingDestination)
                    showRouteError(getString(R.string.no_route), canRetry = false)
                }

                is RouteOutcome.Failed -> {
                    // Almost always a slow or flaky link, where trying again is the fix.
                    transitionTo(SimState.PickingDestination)
                    showRouteError(getString(R.string.route_error, outcome.message), canRetry = true)
                }
            }
        }
    }

    private fun showRouteError(message: String, canRetry: Boolean) {
        val route = binding.sheetRoute
        route.root.visibility = View.VISIBLE
        route.routeProgress.visibility = View.GONE
        route.routeSummary.text = message
        route.routeSummary.setTextColor(ContextCompat.getColor(this, R.color.warn))
        route.routeDetail.visibility = View.GONE
        route.mockWarnBlock.visibility = View.GONE

        route.startButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        if (canRetry) {
            route.startButton.isEnabled = true
            route.startButton.text = getString(R.string.retry)
            route.startButton.setOnClickListener { fetchRoute() }
        }
    }

    // ---------------------------------------------------------------- simulation

    private fun startSimulation() {
        val route = (state as? SimState.Routed)?.route ?: return
        if (!MockPermission.isGranted(this)) {
            renderMockPermission()
            return
        }
        // A location-typed foreground service is refused without this on Android 14+.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.need_location_permission))
            requestPermissions()
            return
        }
        ContextCompat.startForegroundService(this, MockLocationService.intent(this))
        lifecycleScope.launch {
            // Binding may not have landed yet on a cold start; wait for the connection.
            serviceFlow.filterNotNull().first().start(route.points, prefs.defaultSpeedKmh)
        }
        transitionTo(SimState.Live(route))
    }

    private fun applySpeed(kmh: Int, syncSlider: Boolean) {
        service?.setSpeed(kmh)
        binding.sheetRun.speedValue.text = kmh.toString()
        if (syncSlider) {
            binding.sheetRun.speedSlider.value =
                kmh.toFloat().coerceIn(0f, Prefs.MAX_SPEED_KMH.toFloat())
        }
        syncPresetSelection(kmh)
    }

    /**
     * The chips are independently checkable buttons in a plain row rather than a
     * MaterialButtonToggleGroup, which forces them to share borders. Selection is therefore
     * ours to maintain: exactly the one matching the current speed is checked, and none are
     * when the slider sits on an off-preset value.
     */
    private fun syncPresetSelection(kmh: Int) {
        presetButtons().forEach { (value, button) ->
            val selected = value == kmh
            if (button.isChecked != selected) button.isChecked = selected
        }
    }

    private fun onPauseOrRunAgain() {
        val svc = service ?: return
        when (svc.state.value.status) {
            RunStatus.RUNNING -> svc.pause()
            RunStatus.PAUSED -> svc.resume()
            RunStatus.FINISHED -> svc.runAgain()
            RunStatus.IDLE -> Unit
        }
    }

    private fun stopSimulation() {
        service?.stop()
        vehicleAnimator?.cancel()
        markerVehicle?.let { binding.map.overlays.remove(it) }
        markerVehicle = null
        travelledLine?.setPoints(emptyList())
        binding.map.invalidate()

        val route = when (val s = state) {
            is SimState.Live -> s.route
            is SimState.Finished -> s.route
            else -> null
        }
        transitionTo(if (route != null) SimState.Routed(route) else SimState.Idle)
    }

    private fun onSnapshot(snap: SimSnapshot) {
        // start() is optimistic: the UI moves to screen 3 before the service has registered
        // providers. If that failed we are stranded there, where Back only backgrounds the
        // app, so retreat to the preview and surface the reason.
        if (snap.status == RunStatus.IDLE && snap.error != null) {
            val route = (state as? SimState.Live)?.route
                ?: (state as? SimState.Finished)?.route
            if (route != null) {
                toast(getString(R.string.sim_failed, snap.error))
                transitionTo(SimState.Routed(route))
                return
            }
        }

        snap.error?.let {
            if (snap.status != RunStatus.RUNNING) {
                binding.sheetRun.runNotice.text = getString(R.string.sim_failed, it)
                binding.sheetRun.runNotice.setTextColor(
                    ContextCompat.getColor(this, R.color.warn)
                )
                binding.sheetRun.runNotice.visibility = View.VISIBLE
            }
        }

        // The service is the source of truth for whether a drive is in progress. Re-entering
        // the Activity mid-run has to land back on screen 3.
        if (snap.status != RunStatus.IDLE && snap.points.size >= 2) {
            val current = state
            val needsRoute = current !is SimState.Live && current !is SimState.Finished
            if (needsRoute) {
                val restored = Route(snap.points, snap.totalMeters)
                drawRoute(restored)
                transitionTo(
                    if (snap.status == RunStatus.FINISHED) SimState.Finished(restored)
                    else SimState.Live(restored)
                )
            } else if (snap.status == RunStatus.FINISHED && current is SimState.Live) {
                transitionTo(SimState.Finished(current.route))
            }
        }

        if (state !is SimState.Live && state !is SimState.Finished) return

        val run = binding.sheetRun
        // The big number is a readout of what is being emitted, not a mirror of the slider.
        // Holding at the destination emits 0 while the slider stays where you left it, ready
        // for Run again.
        val emittedKmh =
            if (snap.status == RunStatus.FINISHED) 0 else snap.speedKmh
        run.speedValue.text = emittedKmh.toString()
        if (!run.speedSlider.isPressed) {
            run.speedSlider.value =
                snap.speedKmh.toFloat().coerceIn(0f, Prefs.MAX_SPEED_KMH.toFloat())
        }
        syncPresetSelection(snap.speedKmh)

        val progress = snap.fix?.progress ?: 0.0
        run.runProgress.progress = progress.toFloat()
        run.runProgressPct.text = getString(R.string.progress_pct, (progress * 100).toInt())

        val (statusText, live) = when (snap.status) {
            RunStatus.RUNNING ->
                (if (snap.speedKmh == 0) getString(R.string.status_holding)
                else getString(R.string.status_running)) to true

            RunStatus.PAUSED -> getString(R.string.status_paused) to false
            RunStatus.FINISHED -> getString(R.string.status_finished) to false
            RunStatus.IDLE -> "" to false
        }
        run.runStatus.text = statusText
        run.liveDot.visibility = if (live) View.VISIBLE else View.INVISIBLE

        run.pauseButton.text = when (snap.status) {
            RunStatus.PAUSED -> getString(R.string.resume)
            RunStatus.FINISHED -> getString(R.string.run_again)
            else -> getString(R.string.pause)
        }
        run.pauseButton.setIconResource(
            when (snap.status) {
                RunStatus.PAUSED -> R.drawable.ic_play
                RunStatus.FINISHED -> R.drawable.ic_refresh
                else -> R.drawable.ic_pause
            }
        )

        if (snap.status == RunStatus.FINISHED && snap.error == null) {
            run.runNotice.text = getString(R.string.route_complete)
            run.runNotice.setTextColor(ContextCompat.getColor(this, R.color.accent))
            run.runNotice.visibility = View.VISIBLE
        } else if (snap.error == null) {
            run.runNotice.visibility = View.GONE
        }

        val fix = snap.fix
        if (fix == null) {
            run.telemetry.text = getString(R.string.telemetry_waiting)
        } else {
            run.telemetry.text = getString(
                R.string.telemetry,
                Format.coordinate(fix.lat),
                Format.coordinate(fix.lon),
                Format.bearing(fix.bearing),
                Format.km(fix.distanceMeters),
                Format.km(snap.totalMeters)
            )
            moveVehicle(fix.lat, fix.lon, fix.bearing, snap.updateRateHz)
            updateTrail(fix.distanceMeters, fix.lat, fix.lon)
        }
    }

    // ---------------------------------------------------------------- map drawing

    private fun drawEndpointMarkers() {
        markerFrom?.let { binding.map.overlays.remove(it) }
        markerTo?.let { binding.map.overlays.remove(it) }
        markerFrom = endpoints.from?.let { addMarker(it.point, R.drawable.marker_start) }
        markerTo = endpoints.to?.let { addMarker(it.point, R.drawable.marker_end) }
        binding.map.invalidate()
    }

    private fun addMarker(point: LatLng, iconRes: Int): Marker =
        Marker(binding.map).apply {
            position = GeoPoint(point.lat, point.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(this@MainActivity, iconRes)
            setInfoWindow(null)
            binding.map.overlays.add(this)
        }

    private fun drawRoute(route: Route) {
        clearRoute()
        val geo = route.points.map { GeoPoint(it.lat, it.lon) }

        // Remaining route underneath, travelled portion drawn over it.
        routeLine = Polyline(binding.map).apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.accent_dim)
            outlinePaint.strokeWidth = dp(6).toFloat()
            outlinePaint.isAntiAlias = true
            setPoints(geo)
            setInfoWindow(null)
            binding.map.overlays.add(this)
        }
        travelledLine = Polyline(binding.map).apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.accent)
            outlinePaint.strokeWidth = dp(6).toFloat()
            outlinePaint.isAntiAlias = true
            setInfoWindow(null)
            binding.map.overlays.add(this)
        }

        routeCumulative = DoubleArray(route.points.size).also { c ->
            for (i in 1 until route.points.size) {
                c[i] = c[i - 1] + GeoUtils.haversine(route.points[i - 1], route.points[i])
            }
        }

        drawEndpointMarkers()
        fitTo(route.points)
    }

    private fun clearRoute() {
        routeLine?.let { binding.map.overlays.remove(it) }
        travelledLine?.let { binding.map.overlays.remove(it) }
        routeLine = null
        travelledLine = null
        routeCumulative = DoubleArray(0)
        binding.map.invalidate()
    }

    private fun fitTo(points: List<LatLng>) {
        if (points.size < 2) return
        binding.map.post {
            val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lon) })
            runCatching {
                binding.map.zoomToBoundingBox(box.increaseByScale(1.15f), true, dp(48))
            }
        }
    }

    private fun moveVehicle(lat: Double, lon: Double, bearing: Double, hz: Int) {
        val marker = markerVehicle ?: addMarker(LatLng(lat, lon), R.drawable.marker_vehicle)
            .also { markerVehicle = it }
        // osmdroid rotates the canvas by the negated value, so this points the arrow along
        // the bearing rather than mirrored across it.
        marker.rotation = -bearing.toFloat()

        val from = marker.position
        val target = GeoPoint(lat, lon)
        vehicleAnimator?.cancel()

        // Interpolate between fixes so the marker glides rather than jumping, even at 1 Hz.
        vehicleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (1000L / hz.coerceIn(1, 10))
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                marker.position = GeoPoint(
                    from.latitude + (target.latitude - from.latitude) * t,
                    from.longitude + (target.longitude - from.longitude) * t
                )
                binding.map.invalidate()
            }
            start()
        }
    }

    private fun updateTrail(distanceMeters: Double, lat: Double, lon: Double) {
        val line = travelledLine ?: return
        if (routeCumulative.isEmpty()) return

        // Rebuilding a 400-point list ten times a second is wasted work on dashcam silicon.
        val now = System.currentTimeMillis()
        if (now - lastTrailUpdateMs < TRAIL_INTERVAL_MS) return
        lastTrailUpdateMs = now

        var index = routeCumulative.binarySearch(distanceMeters)
        if (index < 0) index = -index - 2
        index = index.coerceIn(0, routeCumulative.size - 1)

        val travelled = ArrayList<GeoPoint>(index + 2)
        routeLine?.actualPoints?.let { all ->
            for (i in 0..index) travelled += all[i]
        }
        travelled += GeoPoint(lat, lon)
        line.setPoints(travelled)
        binding.map.invalidate()
    }

    private fun centreOnLastKnownFix() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions()
            return
        }
        val lm = getSystemService(android.location.LocationManager::class.java)
        val fix = runCatching {
            listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER
            ).firstNotNullOfOrNull { lm?.getLastKnownLocation(it) }
        }.getOrNull()

        if (fix == null) {
            toast(getString(R.string.no_last_fix))
            return
        }
        binding.map.controller.animateTo(GeoPoint(fix.latitude, fix.longitude), 16.0, 500L)
    }

    // ---------------------------------------------------------------- rendering

    private fun transitionTo(next: SimState) {
        state = next
        render()
    }

    private fun render() {
        // The only transition in the app.
        TransitionManager.beginDelayedTransition(
            binding.root,
            ChangeBounds().setDuration(200).excludeTarget(binding.map, true)
        )

        val route = binding.sheetRoute
        val run = binding.sheetRun

        when (val s = state) {
            SimState.Idle -> {
                binding.directionsButton.visibility = View.GONE
                route.root.visibility = View.GONE
                run.root.visibility = View.GONE
            }

            SimState.OriginSet -> {
                binding.directionsButton.visibility = View.VISIBLE
                route.root.visibility = View.GONE
                run.root.visibility = View.GONE
            }

            SimState.PickingDestination -> {
                binding.directionsButton.visibility = View.GONE
                run.root.visibility = View.GONE
                // showRouteError() re-shows this sheet immediately after, when there was one.
                route.root.visibility = View.GONE
            }

            SimState.Routing -> {
                binding.directionsButton.visibility = View.GONE
                run.root.visibility = View.GONE
                route.root.visibility = View.VISIBLE
                route.routeProgress.visibility = View.VISIBLE
                route.routeSummary.text = getString(R.string.routing)
                route.routeSummary.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
                route.routeDetail.visibility = View.GONE
                route.startButton.visibility = View.GONE
                route.mockWarnBlock.visibility = View.GONE
            }

            is SimState.Routed -> {
                binding.directionsButton.visibility = View.GONE
                run.root.visibility = View.GONE
                route.root.visibility = View.VISIBLE
                route.routeProgress.visibility = View.GONE
                route.routeSummary.text = getString(
                    R.string.route_summary,
                    Format.distance(s.route.distanceMeters),
                    s.route.points.size
                )
                route.routeSummary.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
                // showRouteError() may have repurposed this button as Retry.
                route.startButton.text = getString(R.string.start)
                route.startButton.setOnClickListener { startSimulation() }
                route.routeDetail.visibility = View.VISIBLE
                route.routeDetail.text = getString(
                    if (s.route.fromCache) R.string.route_from_cache else R.string.route_duration,
                    Format.duration(s.route.distanceMeters, prefs.defaultSpeedKmh),
                    prefs.defaultSpeedKmh
                )
                route.startButton.visibility = View.VISIBLE
                renderMockPermission()
            }

            is SimState.Live, is SimState.Finished -> {
                binding.directionsButton.visibility = View.GONE
                route.root.visibility = View.GONE
                run.root.visibility = View.VISIBLE
            }
        }

        renderCrumb()
        renderHint()

        // Editing the route out from under a running simulation makes no sense, so the box
        // collapses to a crumb matching the origin's and the map takes back the space.
        val editable = state !is SimState.Live && state !is SimState.Finished
        binding.rowSearch.visibility = if (editable) View.VISIBLE else View.GONE
        binding.destinationCrumb.visibility =
            if (!editable && endpoints.to != null) View.VISIBLE else View.GONE
        endpoints.to?.let {
            binding.destinationCrumb.text = getString(R.string.destination_crumb, it.name)
        }
        binding.searchInput.isEnabled = editable
        binding.clearSearch.visibility = when {
            !editable -> View.GONE
            binding.searchInput.text.isNullOrEmpty() -> View.GONE
            else -> View.VISIBLE
        }

        // Coming back to a run in progress recreates this Activity with no endpoint labels
        // (the service carries the route, not its names), which would otherwise leave an
        // empty card floating over the map. INVISIBLE rather than GONE so the settings
        // button, which is constrained to this card's top, keeps its position.
        val cardHasContent = binding.rowSearch.visibility == View.VISIBLE ||
            binding.originCrumb.visibility == View.VISIBLE ||
            binding.destinationCrumb.visibility == View.VISIBLE
        binding.searchCard.visibility = if (cardHasContent) View.VISIBLE else View.INVISIBLE

        // Screen 3 must keep the display awake.
        if (state is SimState.Live || state is SimState.Finished) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** The chosen origin, collapsed to one line once the box has moved on to the destination. */
    private fun renderCrumb() {
        val from = endpoints.from
        val show = from != null && currentField() == Field.DESTINATION
        binding.originCrumb.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.originCrumb.text = getString(R.string.origin_crumb, from!!.name)
            binding.originCrumb.contentDescription = getString(R.string.change_start)
        }
    }

    private fun renderHint() {
        val destination = currentField() == Field.DESTINATION
        binding.searchDot.setBackgroundResource(
            if (destination) R.drawable.dot_end else R.drawable.dot_start
        )
        binding.searchInput.hint =
            getString(if (destination) R.string.hint_destination else R.string.hint_origin)

        if (listOpen) return
        // The hint line only earns its space while the box is the whole screen.
        val idle = state is SimState.Idle || state is SimState.PickingDestination
        binding.emptyHint.visibility = if (idle) View.VISIBLE else View.GONE
        binding.emptyHint.text = getString(
            if (destination) R.string.search_empty_state_destination
            else R.string.search_empty_state
        )
    }

    private fun renderMockPermission() {
        if (state !is SimState.Routed) return
        val granted = MockPermission.isGranted(this)
        val route = binding.sheetRoute
        route.startButton.isEnabled = granted
        route.mockWarnBlock.visibility = if (granted) View.GONE else View.VISIBLE
        route.adbCommand.text = MockPermission.adbCommand(packageName)
    }

    private fun openDeveloperOptions() {
        if (!MockPermission.openDeveloperOptions(this)) {
            toast(getString(R.string.no_dev_options))
        }
    }

    private fun copyAdbCommand() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText("adb", MockPermission.adbCommand(packageName))
        )
        toast(getString(R.string.copied))
    }

    // ---------------------------------------------------------------- helpers

    private fun clearFocus() {
        binding.searchInput.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun showKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun elapsed(sinceMs: Long): Long = SystemClock.elapsedRealtime() - sinceMs

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "Waypoint"
        const val MIN_QUERY = 3
        const val DEBOUNCE_MS = 400L
        const val MAX_ROWS = 5
        const val TRAIL_INTERVAL_MS = 400L
        const val DISABLED_ALPHA = 0.35f
    }
}
