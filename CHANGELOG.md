# Changelog

The release workflow publishes the section matching the current `versionName`, falling back to
the commit log if there is no match.

## [0.3.0]

### Added

- **Brake** and **Recover** buttons while a simulation is running. Brake decelerates at
  8.5 m/s² (~0.87 g) to a standstill and stays there, still emitting fixes at 0 km/h; Recover
  climbs back to the previous speed at a gentler 2 m/s², so the return is not itself a second
  event. The open-ended stop doubles as dwell testing.

### Fixed

- Every push ran two workflows, testing the project twice. CI and release are now one
  workflow, with the release job gated on the build job.
- The Gradle wrapper was not marked executable, so every CI job died with exit code 126.
- Bumped GitHub Actions off the deprecated Node 20 runtime.

## [0.2.0]

### Added

- Map zoom controls, dimming at the tile source's limits.
- Wide-screen layout for the running screen, so landscape dashcams keep the map.
- Segmented progress bar, which reads as movement at 1 Hz where a solid bar looks frozen.
- Play Services fused provider feed, by reflection so the APK still links no GMS code.
- Per-fix logging under the `WaypointFix` tag.
- 4 Hz update rate; rates above 4 Hz are labelled as exceeding what most consumers accept.
- Retry on the route preview when routing fails.
- Launcher icons, CI and release workflows, release signing.

### Changed

- **One search box, used twice**: pick a start point, press Directions, pick a destination.
- **Fixes are emitted on `gps` only**, while `gps`, `network` and `fused` are all shadowed.
  Emitting on every provider gave consumers subscribed to two of them double the configured
  rate, which silently doubles any speed derived from timestamps.
- **Arriving no longer stops the fixes.** The vehicle holds at the destination at 0 km/h until
  Stop; falling silent reads as signal loss rather than as a vehicle that has arrived.
- The speed readout and log report the speed actually injected, not the slider position.
- Longer network timeout for routing than for search.
- Routes are cached per routing server.

### Fixed

- Test providers leaked by a killed process made every later run fail with
  `Provider "gps" already exists` until reboot.
- A failed start could kill the process with `ForegroundServiceDidNotStartInTimeException`.
- A revoked mock-location appop was swallowed, leaving the UI reporting a healthy run while
  nothing was injected.
- Backing out after arrival left the service injecting and holding a wake lock.
- Cancelled searches leaked their HTTP response and were reported as errors.
- Races on the simulation snapshot between the ticker and UI threads.

## [0.1.0]

First release. Route simulation between two points using OSRM and Photon, live speed control,
a foreground service that survives rotation and backgrounding, configurable tile/routing/
geocoding servers with per-server route caching, position jitter, route looping, and update
rates from 1 to 10 Hz.
