# Waypoint

Spoofs GPS along a real road route, with live speed control. Built for testing fleet
telematics and geofencing SDKs on dashcam hardware that runs AOSP without Google Play
Services.

You search for a start and end point, the app fetches a real road route, and on Start it
injects synthetic GPS fixes along that route into the Android location framework at a speed
you control with a slider. Every app on the device, including the SDK under test, sees those
fixes as if the device were actually moving.

- No Google Play Services, no API keys, no signup.
- Single APK, sideloaded. API 26 (Android 8.0) through 36.
- Apache-2.0.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set in.nulltheory.waypoint android:mock_location allow
```

The appop is reset on every reinstall, so re-run that last line each time.

On a device with a Settings UI you can instead pick the app under
**Settings → Developer options → Select mock location app**. The Settings screen deep-links
there, and shows the adb command as selectable text for headless units.

## Prove it works on new hardware first

The one thing that can invalidate the whole approach is hardware that bypasses
`LocationManager` — some OEMs read NMEA from a UART straight into a vendor HAL. Check that
before trusting the app on a new dashcam model:

```bash
adb shell appops set in.nulltheory.waypoint android:mock_location allow
adb shell pm grant in.nulltheory.waypoint android.permission.ACCESS_FINE_LOCATION
./gradlew connectedDebugAndroidTest
```

`MockInjectionTest` registers a test provider, injects one fix, and asserts it comes back out
of a `LocationListener` with speed, bearing and `elapsedRealtimeNanos` intact. If it fails,
mock location cannot reach that unit and the approach has to change.

Note that injected fixes report `Location.isMock()` (or `isFromMockProvider()` below API 31)
as true. An SDK that filters mock fixes needs a debug flag to accept them.

### One emitting provider, several shadowed

A test provider is registered on `gps`, `network` (and `fused` on API 31+) so that real fixes
from those provider names are suppressed — but samples are pushed to **`gps` only**.

This matters. Consumers commonly subscribe to both GPS and NETWORK; pushing to both delivers
one `onLocationChanged` per provider per tick, so the SDK under test sees **double** the
configured rate. Anything deriving speed or acceleration from consecutive timestamps then
reads half the true interval and silently doubles its answer.

### What the fix carries

`speed` is set in **metres per second**, which is what `Location.getSpeed()` means everywhere,
along with `speedAccuracyMetersPerSecond`. `bearing`, `accuracy`, `altitude`, `time` and
`elapsedRealtimeNanos` are all populated on every tick, so consumers that gate on
`hasSpeed()` / `hasBearing()` behave exactly as they would with a real fix.

## Using it

One search box, used twice. Search a start point and select it, press **Directions**, search a
destination and select it — the route is fetched on selection and the preview appears. Or
long-press the map to drop either point, which is the fastest path when the keyboard is
awkward over `scrcpy`, and the only path that works when the geocoder is unreachable.

On the running screen the speed slider takes effect on the very next tick, with no apply
button. Preset chips are the primary control on dashcam hardware. Setting speed to **0** keeps
the simulation alive and stationary, which is the correct way to test geofence dwell and idle
detection — it is not the same as **Pause**, which stops emitting fixes entirely.

The simulation lives in a foreground service, so it survives rotation and backgrounding. Back
out of the running screen and the drive continues, which is what you want while watching the
SDK under test in another app.

**Reaching the end of the route does not stop the fixes.** The vehicle holds at the
destination at 0 km/h until you press Stop. Going silent would read as signal loss to the
consumer rather than as a vehicle that has arrived — and a speed history that merely goes
stale reads as *unknown*, not as zero. (The LightMetrics `LocationTracker.getRecentSpeed()`
returns −1 once the newest sample is more than 8 s old, for instance.) **Pause** is the
control that genuinely stops emission.

### Update rate and consuming SDKs

1, 2, 4, 5 and 10 Hz are offered, but check what the SDK under test expects. LightMetrics
sizes its speed ring buffer at 4 samples/sec and drops external input above that, so **2–4 Hz
is the right range there**; 10 Hz silently shrinks its history window.

## Watching the fixes

Every injected fix is logged, one aligned line per tick:

```bash
adb logcat -s WaypointFix
```

```
┌───────────────────────────────────────────┐
│ SIMULATION STARTED                        │
├───────────────────────────────────────────┤
│ route       24.72 km · 387 points         │
│ emitting on gps                           │
│ shadowing   gps, network (real suppressed)│
│ update rate 1 Hz  (every 1000 ms)         │
│ speed       50 km/h                       │
│ jitter      off                           │
│ loop        off                           │
└───────────────────────────────────────────┘
seq   │ latitude     longitude   │ speed            │ hdg  │ acc    │ alt    │ pct  │ travelled/total │ state   │ tick
#00001 │   12.995743,   77.757949 │  50 km/h  13.89 m/s │ 143° │ ± 3.0m │   920m │   0% │    0.01/24.72   km │ RUNNING │ dt 1.002s
#00002 │   12.995812,   77.757861 │  50 km/h  13.89 m/s │ 143° │ ± 3.0m │   920m │   0% │    0.03/24.72   km │ RUNNING │ dt 1.000s
──── SPEED · 50 -> 0 km/h
#00003 │   12.995812,   77.757861 │   0 km/h   0.00 m/s │ 143° │ ± 3.0m │   920m │   0% │    0.03/24.72   km │ HOLDING │ dt 1.001s
```

The coordinates are what was **actually injected**, after jitter — not what the simulator
computed. If those two ever disagree, the log is the truth.

`dt` is the real measured interval between ticks, so drift or a stalled device shows up
directly. `HOLDING` means speed 0 with fixes still flowing (dwell testing); `PAUSED` means no
fixes at all. Speed changes, pause, resume, completion and stop are all logged inline.

Stopping deregisters every test provider. Confirm real GPS is healthy again with:

```bash
adb shell dumpsys location | grep -A5 "gps provider"
```

## Services, and why they are all configurable

| Concern | Default | License |
|---|---|---|
| Map tiles | `https://tile.openstreetmap.org/` | OSM data is ODbL |
| Routing | `https://router.project-osrm.org` | BSD-2-Clause |
| Geocoding | `https://photon.komoot.io` | Apache-2.0 |

All three are **shared community resources**, and all three are editable in Settings for
exactly that reason. Point them at your own instances for anything beyond bench testing:

- The OSM Foundation [tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
  discourages distributed applications. The app sends an identifying User-Agent, but heavy use
  belongs on your own tile server. The field accepts either a prefix
  (`https://host/`) or a template (`https://host/{z}/{x}/{y}.png`).
- The OSRM demo server is best-effort. Self-host
  [osrm-backend](https://github.com/Project-OSRM/osrm-backend).
- Photon is courtesy of Komoot. Self-host [photon](https://github.com/komoot/photon).

Routes are cached to `filesDir` keyed by their endpoints, so a route fetched once replays
forever with no network — the test bench will not always have wifi.

**If search fails but the map still loads**, the device cannot reach the geocoding host
specifically. Long-press the map to set both points instead; that path needs no geocoder.

## Architecture

```
MainActivity.kt              Screens 1-3, state machine, map wiring
SettingsActivity.kt          Screen 4
LicensesActivity.kt          Attribution
net/  Geocoder Router RouteCache Http
sim/  RouteSimulator GeoUtils MockLocationService MockPermission
ui/   SearchAdapter SimState MapTiles Format
```

`RouteSimulator` and `GeoUtils` have no Android dependencies at all, so they unit-test on a
bare JVM (`./gradlew testDebugUnitTest`). The service owns the simulation; the Activity binds
and observes.

The one thing deliberately over-engineered is the fix payload in `MockLocationService.inject`.
`elapsedRealtimeNanos` is the most common omission, and many location pipelines — the AOSP
fused engine included — silently drop fixes whose elapsed-realtime stamp is zero. Everything
else in this app is replaceable; a malformed `Location` is what makes somebody conclude the
tool does not work.

## Not built yet

Signal dropout, per-segment speed profiles, harsh-braking event injection, dwell scripting,
GPX replay, offline MBTiles, and `am broadcast` control for CI. Position jitter is wired to the
Settings toggle (Gaussian, ~3 m sigma).
