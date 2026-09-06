package `in`.nulltheory.waypoint.sim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import `in`.nulltheory.waypoint.MainActivity
import `in`.nulltheory.waypoint.Prefs
import `in`.nulltheory.waypoint.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

enum class RunStatus { IDLE, RUNNING, PAUSED, FINISHED }

/** Everything the UI needs to render screen 3, published as one immutable value. */
data class SimSnapshot(
    val status: RunStatus = RunStatus.IDLE,
    val speedKmh: Int = Prefs.DEFAULT_SPEED_KMH,
    val fix: Fix? = null,
    val points: List<LatLng> = emptyList(),
    val totalMeters: Double = 0.0,
    val updateRateHz: Int = Prefs.DEFAULT_RATE_HZ,
    val loop: Boolean = false,
    /** Where a harsh-braking event has got to, so the UI can gate Brake and Recover. */
    val brakePhase: BrakePhase = BrakePhase.NONE,
    val error: String? = null
)

/** Stages of an injected harsh-braking event. */
enum class BrakePhase { NONE, DECELERATING, STOPPED, RECOVERING }

/**
 * Owns the simulation. The Activity binds and observes, so a fake drive survives rotation and
 * backgrounding — which matters, because watching the SDK under test usually means switching
 * to another app while the drive continues.
 */
class MockLocationService : Service() {

    inner class LocalBinder : Binder() {
        val service: MockLocationService get() = this@MockLocationService
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow(SimSnapshot())
    val state: StateFlow<SimSnapshot> = _state.asStateFlow()

    private lateinit var locationManager: LocationManager
    private lateinit var prefs: Prefs
    private lateinit var notifications: NotificationManager

    private var executor: ScheduledExecutorService? = null
    private var tick: ScheduledFuture<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var simulator: RouteSimulator? = null

    /** No-op unless the device actually has Play Services. See [FusedLocationInjector]. */
    private lateinit var fused: FusedLocationInjector

    /**
     * Providers we shadow with a test provider, so real fixes from those provider names are
     * suppressed. Teardown only touches these.
     */
    private val activeProviders = mutableListOf<String>()

    /**
     * The single provider we actually push samples to. Registering every provider but
     * emitting on one is deliberate: a consumer that subscribes to both GPS and NETWORK —
     * which is the common pattern, and what the LightMetrics DirectLocationReceiver does —
     * would otherwise get one onLocationChanged per provider per tick, i.e. double the
     * configured rate. That silently corrupts anything deriving speed from timestamps.
     */
    private var primaryProvider: String = LocationManager.GPS_PROVIDER

    @Volatile private var speedMps: Double = 0.0
    @Volatile private var paused: Boolean = false
    @Volatile private var ticking: Boolean = false

    @Volatile private var brakePhase: BrakePhase = BrakePhase.NONE

    /** The cruising speed to climb back to when Recover is pressed. */
    @Volatile private var brakeResumeMps: Double = 0.0
    private var fixSeq: Long = 0
    private var lastArrivedLogMs: Long = 0L
    private var lastTickNanos: Long = 0L
    private var lastNotificationMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        prefs = Prefs(this)
        fused = FusedLocationInjector(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- control

    fun start(points: List<LatLng>, speedKmh: Int) {
        // The Activity reaches us via startForegroundService(), which gives us a few seconds
        // to call startForeground() or the process is killed with
        // ForegroundServiceDidNotStartInTimeException. Every early return below would
        // otherwise skip it, so enter the foreground before anything can fail.
        startForegroundNotification()

        if (points.size < 2) {
            _state.update { it.copy(error = "Route has fewer than two points") }
            return
        }

        stopTicking()
        val sim = RouteSimulator(points)
        simulator = sim
        speedMps = speedKmh / 3.6
        paused = false

        _state.value = SimSnapshot(
            status = RunStatus.RUNNING,
            speedKmh = speedKmh,
            fix = null,
            points = points,
            totalMeters = sim.totalMeters,
            updateRateHz = prefs.updateRateHz,
            loop = prefs.loop
        )

        if (!registerProviders()) return

        fixSeq = 0
        SimLog.started(
            totalMeters = sim.totalMeters,
            pointCount = points.size,
            emittingOn = primaryProvider,
            fusedActive = fused.isActive,
            providers = activeProviders.toList(),
            updateRateHz = _state.value.updateRateHz,
            speedKmh = speedKmh,
            jitter = prefs.jitter,
            loop = prefs.loop
        )

        pushNotification(force = true)
        acquireWakeLock()
        startTicking()
    }

    /**
     * Slams to a standstill and stays there until [recoverFromBrake].
     *
     * The stop is open-ended rather than timed, so the same control also covers dwell: sit at
     * the kerb for as long as the test needs, then pull away. The speed the vehicle was doing
     * is remembered so recovery can return to it.
     *
     * Speed ramps across ticks rather than jumping — a single 120-to-0 step between two fixes
     * is not a braking event to anything downstream, it is an implausible outlier that filters
     * discard.
     */
    fun harshBrake() {
        if (_state.value.status != RunStatus.RUNNING) return
        if (speedMps <= 0.0 || brakePhase != BrakePhase.NONE) return

        brakeResumeMps = speedMps
        setBrakePhase(BrakePhase.DECELERATING)
        SimLog.event(
            "HARSH BRAKE",
            "from ${(speedMps * 3.6).roundToInt()} km/h at $HARSH_BRAKE_DECEL_MPS2 m/s² " +
                "(~${"%.2f".format(HARSH_BRAKE_DECEL_MPS2 / 9.81)} g)"
        )
    }

    /**
     * Climbs back to the speed held before the brake. Also usable mid-deceleration, to abort
     * a stop that has not finished.
     */
    fun recoverFromBrake() {
        if (brakePhase != BrakePhase.DECELERATING && brakePhase != BrakePhase.STOPPED) return
        setBrakePhase(BrakePhase.RECOVERING)
        SimLog.event("RECOVER", "climbing back to ${(brakeResumeMps * 3.6).roundToInt()} km/h")
    }

    private fun setBrakePhase(phase: BrakePhase) {
        brakePhase = phase
        _state.update { it.copy(brakePhase = phase) }
    }

    private fun cancelBraking() {
        if (brakePhase == BrakePhase.NONE) return
        setBrakePhase(BrakePhase.NONE)
    }

    fun setSpeed(kmh: Int) {
        val previous = _state.value.speedKmh
        // Touching the slider or a preset overrides a brake in progress.
        cancelBraking()
        speedMps = kmh / 3.6
        _state.update { it.copy(speedKmh = kmh) }
        pushNotification(force = true)
        if (previous != kmh) SimLog.event("SPEED", "$previous -> $kmh km/h")
    }

    fun pause() {
        if (_state.value.status != RunStatus.RUNNING) return
        paused = true
        _state.update { it.copy(status = RunStatus.PAUSED) }
        pushNotification(force = true)
        SimLog.event("PAUSED", "no fixes are being emitted")
    }

    fun resume() {
        if (_state.value.status != RunStatus.PAUSED) return
        paused = false
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
        _state.update { it.copy(status = RunStatus.RUNNING) }
        pushNotification(force = true)
        SimLog.event("RESUMED")
    }

    /** Replays the current route from the beginning without re-registering providers. */
    fun runAgain() {
        val sim = simulator ?: return
        sim.reset()
        paused = false
        _state.update { it.copy(status = RunStatus.RUNNING, error = null) }
        lastTickNanos = SystemClock.elapsedRealtimeNanos()
        if (tick == null) startTicking()
        pushNotification(force = true)
        fixSeq = 0
        SimLog.event("RUN AGAIN", "cursor reset to the start of the route")
    }

    fun stop() {
        val registered = activeProviders.toList()
        teardown()
        SimLog.stopped("stopped by the user after $fixSeq fixes", registered)
        _state.value = SimSnapshot(speedKmh = prefs.defaultSpeedKmh)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Live updates from Settings while a run is in progress. */
    fun applyPrefs() {
        val newRate = prefs.updateRateHz
        val rateChanged = newRate != _state.value.updateRateHz
        _state.update { it.copy(updateRateHz = newRate, loop = prefs.loop) }
        if (rateChanged && tick != null) {
            stopTicking()
            startTicking()
        }
    }

    // ---------------------------------------------------------------- ticking

    private fun startTicking() {
        ticking = true
        val hz = _state.value.updateRateHz.coerceIn(1, 10)
        val periodMs = (1000L / hz).coerceAtLeast(1L)
        lastTickNanos = SystemClock.elapsedRealtimeNanos()

        val exec = executor ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "waypoint-sim").apply { priority = Thread.NORM_PRIORITY + 1 }
        }.also { executor = it }

        // scheduleAtFixedRate, not Handler.postDelayed: postDelayed accumulates drift, and
        // over a twenty-minute drive the injected timestamps visibly diverge from wall clock.
        tick = exec.scheduleAtFixedRate({ onTick() }, 0L, periodMs, TimeUnit.MILLISECONDS)
    }

    private fun stopTicking() {
        // cancel(false) lets an in-flight tick finish, so this flag is what stops it writing
        // a stale fix back into a snapshot that has already been reset.
        ticking = false
        tick?.cancel(false)
        tick = null
    }

    private fun onTick() {
        try {
            if (!ticking) return
            val sim = simulator ?: return
            val now = SystemClock.elapsedRealtimeNanos()
            // Real elapsed time, not the nominal interval: if the device stalls for 300ms the
            // vehicle covers the distance it would have covered.
            val dt = ((now - lastTickNanos).coerceAtLeast(0L)) / 1_000_000_000.0
            lastTickNanos = now

            if (paused) return

            applyBraking(dt)

            var fix = sim.advance(speedMps, dt)
            if (fix == null) {
                if (_state.value.loop) {
                    sim.reset()
                    fix = sim.advance(speedMps, 0.0)
                } else {
                    if (_state.value.status != RunStatus.FINISHED) onRouteConsumed()
                    // Keep emitting a stationary fix at the destination instead of going
                    // silent. Silence reads as signal loss to a consuming SDK, and speed
                    // history that merely goes stale reads as unknown rather than zero.
                    fix = sim.destinationFix()
                }
            }
            if (fix == null) return

            val injected = inject(fix)
            _state.update { it.copy(fix = fix) }
            pushNotification(force = false)

            fixSeq++
            if (!shouldLogThisFix()) return

            SimLog.fix(
                seq = fixSeq,
                lat = injected.lat,
                lon = injected.lon,
                // Derived from the fix, not from the slider. Holding at the destination
                // injects 0 m/s while the slider still reads 120; reporting the slider value
                // here would make the log disagree with what actually went out.
                speedKmh = (fix.speedMps * 3.6).roundToInt(),
                speedMps = fix.speedMps,
                bearing = fix.bearing,
                accuracyM = injected.accuracy,
                altitudeM = ALTITUDE_M,
                progress = fix.progress,
                travelledMeters = fix.distanceMeters,
                totalMeters = _state.value.totalMeters,
                status = when {
                    _state.value.status == RunStatus.FINISHED -> "ARRIVED"
                    fix.speedMps == 0.0 -> "HOLDING"
                    else -> "RUNNING"
                },
                dtSeconds = dt,
                jitter = prefs.jitter
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Lost mock location permission mid-run", e)
            failWith("mock location permission was revoked")
        } catch (e: Exception) {
            Log.e(TAG, "Tick failed", e)
            failWith(e.message ?: "unknown error")
        }
    }

    /**
     * The route is used up, but the ticker deliberately keeps running: the vehicle has
     * arrived, not vanished. Fixes continue at the destination with speed 0 until Stop.
     */
    /** Drives the harsh-braking state machine. Runs on the ticker thread. */
    private fun applyBraking(dt: Double) {
        when (brakePhase) {
            BrakePhase.NONE -> return

            BrakePhase.DECELERATING -> {
                speedMps = (speedMps - HARSH_BRAKE_DECEL_MPS2 * dt).coerceAtLeast(0.0)
                if (speedMps <= 0.0) {
                    setBrakePhase(BrakePhase.STOPPED)
                    SimLog.event(
                        "HARSH BRAKE",
                        "stopped · still emitting fixes, press Recover to pull away"
                    )
                }
            }

            // Open-ended. Fixes keep flowing at 0 km/h, which is what dwell and idle
            // detection need to see; the run only resumes when Recover is pressed.
            BrakePhase.STOPPED -> speedMps = 0.0

            BrakePhase.RECOVERING -> {
                // Deliberately gentler than the braking rate, and below the ~0.3 g most
                // platforms treat as harsh acceleration. Pulling away hard would register a
                // second event and muddy whatever you were trying to measure.
                speedMps = (speedMps + BRAKE_RECOVER_ACCEL_MPS2 * dt).coerceAtMost(brakeResumeMps)
                if (speedMps >= brakeResumeMps) {
                    speedMps = brakeResumeMps
                    cancelBraking()
                    SimLog.event("RECOVER", "back to ${(speedMps * 3.6).roundToInt()} km/h")
                    pushNotification(force = true)
                }
            }
        }

        val kmh = (speedMps * 3.6).roundToInt()
        if (_state.value.speedKmh != kmh) _state.update { it.copy(speedKmh = kmh) }
    }

    private fun onRouteConsumed() {
        _state.update { it.copy(status = RunStatus.FINISHED) }
        pushNotification(force = true)
        lastArrivedLogMs = 0L
        SimLog.event(
            "ROUTE COMPLETE",
            "$fixSeq fixes injected · holding at the destination at 0 km/h until you press " +
                "Stop · still injecting, logging once every ${ARRIVED_LOG_INTERVAL_MS / 1000}s"
        )
    }

    /**
     * Injection continues at the destination forever, but logging every identical stationary
     * fix at 10 Hz would bury whatever you are actually watching in logcat. Once arrived, the
     * readout drops to a heartbeat.
     */
    private fun shouldLogThisFix(): Boolean {
        if (_state.value.status != RunStatus.FINISHED) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastArrivedLogMs < ARRIVED_LOG_INTERVAL_MS) return false
        lastArrivedLogMs = now
        return true
    }

    private fun failWith(message: String) {
        stopTicking()
        _state.update { it.copy(status = RunStatus.FINISHED, error = message) }
        pushNotification(force = true)
        SimLog.failed(message)
    }

    // ---------------------------------------------------------------- injection

    /**
     * Lint wants ProviderProperties for the last two arguments, but that class is API 31 and
     * this app runs from API 26. The Criteria constants carry the identical values
     * (POWER_LOW/POWER_USAGE_LOW = 1, ACCURACY_FINE = 1), so they are correct on every
     * supported release.
     */
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("WrongConstant")
    private fun registerProviders(): Boolean {
        val wanted = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) wanted += LocationManager.FUSED_PROVIDER

        activeProviders.clear()
        var lastError: Exception? = null

        wanted.forEach { provider ->
            // Wrapped individually: on some OEM builds NETWORK_PROVIDER does not exist and
            // throws, and that must not take down GPS injection with it.
            runCatching {
                // If the process was killed mid-run (a reinstall, a crash, the OOM killer)
                // the provider is still registered and addTestProvider would throw
                // "Provider already exists", locking the app out until reboot.
                runCatching { locationManager.removeTestProvider(provider) }

                locationManager.addTestProvider(
                    provider,
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ false,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
                activeProviders += provider
            }.onFailure {
                lastError = it as? Exception
                Log.w(TAG, "Could not register test provider $provider", it)
            }
        }

        if (activeProviders.isEmpty()) {
            val why = when (lastError) {
                is SecurityException ->
                    "Waypoint is not the selected mock location app"
                else -> lastError?.message ?: "no test provider could be registered"
            }
            _state.update { it.copy(status = RunStatus.IDLE, error = why) }
            return false
        }
        // Prefer GPS as the emitting provider; fall back to whatever did register.
        primaryProvider = activeProviders.firstOrNull { it == LocationManager.GPS_PROVIDER }
            ?: activeProviders.first()

        // A consumer on FusedLocationProviderClient cannot see test providers at all.
        fused.enable()

        Log.i(
            TAG,
            "Emitting on $primaryProvider, shadowing ${activeProviders.joinToString()}"
        )
        return true
    }

    /** What actually went into the location framework, after jitter. */
    private data class Injected(val lat: Double, val lon: Double, val accuracy: Float)

    private fun inject(fix: Fix): Injected {
        val jitterOn = prefs.jitter
        val lat: Double
        val lon: Double
        val accuracy: Float

        if (jitterOn) {
            // Gaussian noise with ~3 m sigma, plus a matching accuracy, to exercise the
            // consuming SDK's filtering.
            val nLat = Random.nextGaussian() * JITTER_SIGMA_M
            val nLon = Random.nextGaussian() * JITTER_SIGMA_M
            lat = fix.lat + nLat / METERS_PER_DEGREE
            lon = fix.lon + nLon / (METERS_PER_DEGREE * cos(Math.toRadians(fix.lat)).coerceAtLeast(1e-6))
            accuracy = JITTER_ACCURACY_M
        } else {
            lat = fix.lat
            lon = fix.lon
            accuracy = BASE_ACCURACY_M
        }

        val wallClock = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()

        // One sample, one provider. See [primaryProvider].
        val loc = Location(primaryProvider).apply {
            latitude = lat
            longitude = lon
            // Metres per second, which is what Location.getSpeed() means everywhere. ADAS
            // consumers convert to km/h themselves.
            speed = fix.speedMps.toFloat()
            bearing = fix.bearing.toFloat()
            this.accuracy = accuracy
            altitude = ALTITUDE_M
            time = wallClock
            // The single most common omission. Many location pipelines, the AOSP fused
            // engine included, drop fixes whose elapsed-realtime stamp is zero.
            elapsedRealtimeNanos = elapsed
            bearingAccuracyDegrees = 1.0f
            speedAccuracyMetersPerSecond = 0.5f
            verticalAccuracyMeters = 3.0f
        }
        try {
            locationManager.setTestProviderLocation(primaryProvider, loc)
        } catch (e: SecurityException) {
            // The appop was revoked mid-run. Must reach onTick's handler and stop the run —
            // swallowing it here would leave the UI and logcat reporting a healthy
            // simulation while nothing at all is being injected.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "setTestProviderLocation failed for $primaryProvider", e)
        }

        // Separate pipe, separate audience: a fused consumer never sees the test provider,
        // and a LocationManager consumer never sees this, so there is no double delivery.
        fused.push(loc)

        return Injected(lat, lon, accuracy)
    }

    private fun teardown() {
        stopTicking()
        executor?.shutdownNow()
        executor = null
        simulator = null
        releaseWakeLock()
        if (this::fused.isInitialized) fused.disable()

        // Leaking a test provider leaves the device with a broken location stack until reboot.
        activeProviders.forEach { provider ->
            runCatching { locationManager.setTestProviderEnabled(provider, false) }
            runCatching { locationManager.removeTestProvider(provider) }
        }
        activeProviders.clear()
    }

    // ---------------------------------------------------------------- plumbing

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waypoint:sim").apply {
            setReferenceCounted(false)
            runCatching { acquire(MAX_RUN_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        }.onFailure { Log.w(TAG, "Could not enter the foreground", it) }
    }

    private fun pushNotification(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // At 10 Hz the notification would be rebuilt ten times a second for no benefit.
        if (!force && now - lastNotificationMs < NOTIFICATION_MIN_INTERVAL_MS) return
        lastNotificationMs = now
        runCatching { notifications.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val snap = _state.value
        val percent = ((snap.fix?.progress ?: 0.0) * 100).toInt().coerceIn(0, 100)

        val title = when (snap.status) {
            RunStatus.PAUSED -> getString(R.string.notif_paused, percent)
            RunStatus.FINISHED -> getString(R.string.notif_finished)
            else -> getString(R.string.notif_title, snap.speedKmh, percent)
        }

        val content = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_waypoint)
            .setContentTitle(title)
            .setContentText(snap.error ?: getString(R.string.app_name))
            .setContentIntent(content)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stop)
            .setProgress(1000, ((snap.fix?.progress ?: 0.0) * 1000).toInt(), false)
            .setOngoing(snap.status == RunStatus.RUNNING || snap.status == RunStatus.PAUSED)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "MockLocationService"
        const val ACTION_STOP = "in.nulltheory.waypoint.STOP"

        private const val CHANNEL_ID = "simulation"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_MIN_INTERVAL_MS = 900L

        private const val BASE_ACCURACY_M = 3.0f
        private const val JITTER_SIGMA_M = 3.0
        private const val JITTER_ACCURACY_M = 8.0f
        private const val ALTITUDE_M = 920.0
        private const val METERS_PER_DEGREE = 111_320.0

        private const val MAX_RUN_MS = 6L * 60 * 60 * 1000
        private const val ARRIVED_LOG_INTERVAL_MS = 10_000L

        /**
         * ~0.87 g — an emergency stop, at the edge of what tyres on dry tarmac can deliver.
         * Well past the 0.3–0.45 g most telematics platforms flag as harsh, and still
         * physically plausible, so nothing rejects it as an impossible outlier.
         */
        private const val HARSH_BRAKE_DECEL_MPS2 = 8.5

        /** ~0.2 g. Below harsh-acceleration thresholds, so recovery is not a second event. */
        private const val BRAKE_RECOVER_ACCEL_MPS2 = 2.0

        fun intent(ctx: Context): Intent = Intent(ctx, MockLocationService::class.java)
    }
}

/** Box-Muller. kotlin.random has no Gaussian, and pulling in java.util.Random is noisier. */
private fun Random.nextGaussian(): Double {
    var u: Double
    do {
        u = nextDouble()
    } while (u <= 0.0)
    return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * nextDouble())
}
