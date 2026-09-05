# Changelog

Notable changes per release. The release workflow uses the section matching the current
`versionName` as the release body; if there is no matching section it falls back to the commit
log since the previous tag.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0]

### Added

- Zoom controls on the map, dimming at the tile source's limits.
- Wide-screen layout for the running simulation, folding the controls into two columns so
  landscape-locked dashcam hardware keeps most of the screen for the map.
- Segmented route progress bar, which reads as movement at 1 Hz where a solid bar looks frozen.
- Play Services fused provider feed, via reflection so the APK still links no GMS code. A
  consumer built on `FusedLocationProviderClient` cannot see test providers at all.
- Per-fix logging under the `WaypointFix` tag, with a start banner and lifecycle events.
- 4 Hz update rate. Rates above 4 Hz are now labelled as exceeding what most consumers accept.
- Retry on the route preview when routing fails, instead of forcing a re-pick.
- Launcher icons, GitHub Actions for CI and releases, and release signing configuration.

### Changed

- **One search box, used twice**, replacing the side-by-side From/To fields: pick a start
  point, press Directions, pick a destination, and the route is fetched on selection.
- **Fixes are emitted on `gps` only** while `gps`, `network` and `fused` are all shadowed.
  Emitting on every registered provider delivered one callback per provider per tick, so a
  consumer subscribed to both saw double the configured rate — which silently doubles any
  speed derived from consecutive timestamps.
- **Arriving no longer stops the fixes.** The vehicle holds at the destination at 0 km/h until
  Stop. Falling silent reads as signal loss, and a speed history that goes stale reads as
  unknown rather than zero.
- The speed readout and the log now report the speed actually injected rather than the slider
  position, so they agree while holding at the destination.
- Separate network timeouts for search and routing: routing is a one-shot commitment that is
  cached forever, so it is worth waiting on where an interactive search is not.
- Routes are cached per routing server, so changing it in Settings no longer replays the
  previous server's geometry.
- The destination collapses to a one-line summary during a run, matching the start point.

### Fixed

- Test providers left registered by a killed process made every later run fail with
  `Provider "gps" already exists` until reboot. They are now cleared before registering.
- A failed start could kill the process with `ForegroundServiceDidNotStartInTimeException`,
  because both early-return paths skipped `startForeground()`.
- A revoked mock-location appop was swallowed mid-run, leaving the UI and logs reporting a
  healthy simulation while nothing was being injected.
- Backing out after arrival left the service injecting, holding a wake lock and shadowing real
  GPS indefinitely.
- A failed start stranded the UI on the simulation screen with no way back.
- Cancelled searches leaked their HTTP response, and were reported as errors rather than as
  the superseded requests they are.
- Races on the simulation snapshot between the ticker thread and the UI thread, and a stale
  tick that could write back after a stop.

## [0.1.0]

First release.

### Added

- Route simulation between two points, using OSRM for road geometry and Photon for search.
  One search box, used twice; long-press the map as a keyboard-free alternative.
- Live speed control from 0–150 km/h with preset chips, taking effect on the next tick.
  Speed 0 keeps emitting a stationary fix, which is distinct from Pause.
- Foreground service that owns the simulation, so a run survives rotation, backgrounding and
  the Activity being destroyed.
- Fixed-width per-fix logging under the `WaypointFix` tag, reporting what was actually
  injected rather than what was computed.
- Configurable tile, routing and geocoding servers, with routes cached per server so a fetched
  route replays offline.
- Position jitter, route looping, and update rates of 1–10 Hz.
- Play Services fused provider support via reflection, so consumers on
  `FusedLocationProviderClient` are fed too without the APK linking any GMS code.
