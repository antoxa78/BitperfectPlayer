# Changelog

## 2.7.0 - 2026-08-17

### Fixed

- **Memory leaks and crashes:** Player listener in MainFragment and NowPlayingActivity now properly removed on view/activity destroy.
- **Screensaver handler leak in BaseActivity:** Bounce animation handler promoted to class field; `onDestroy()` cancels all callbacks and hides the screensaver.
- **Fragment detachment crash:** `requireContext()` in background-thread `runOnUiThread` blocks now guarded with `isAdded` check (3 call sites).
- **OkHttp response body leak in NowPlayingActivity:** Non-2xx art responses now closed via `.use {}` to prevent connection pool exhaustion.
- **Multiple progress-update loops in NowPlayingActivity:** Each `startProgressUpdate()` call now cancels any previous runnable before starting a new one.
- **Double-seek on D-pad in Now Playing:** `seekTo` removed from `onProgressChanged`; seeking now only occurs on `onStopTrackingTouch`.
- **Invisible control buttons:** Default tint in `updateControlButtonsTint` changed from `Color.TRANSPARENT` to theme colour.
- **Resume playback toggle default mismatch:** `toggleResumePlayback()` now reads pref with default `true`, matching `isResumeEnabled()`.
- **Position lost on Exit:** Exit action now calls `saveCurrentPositionSync()` (commit) before `System.exit(0)` instead of relying on async `apply()`.
- **Playlist scan thread race:** Stale background scan threads now discard results via a generation counter.
- **USB hotplug pileup:** `resetAudioSink()` restore step is now a cancellable field; rapid calls cancel the previous pending restore.
- **USB attach callback pileup:** `usbSettleRunnable` stored as field and cancelled before re-posting.
- **Resume playback now triggers correctly when entering while player is paused** (ready but not playing).

## 2.6.2 - 2026-08-14

### Added

- Android 14+ bit-perfect mixer negotiation for compatible USB audio devices.
- Direct high-resolution PCM output with Media3 fallback handling.
- USB DAC detection, reset controls, and output-device routing.
- Live ICY stream metadata in Now Playing and screensaver views.

### Improved

- Resume playback now restores the queue, URI, clipping range, position, and play state.
- Now Playing shows decoded sample rate and bit depth.
- Disabled audio offload and time-stretch processing for the bit-perfect playback path.
- Restricted external media-session access to this app and trusted controllers.

### Fixed

- Mixer cleanup races during USB disconnect and reconnect.
- Resume failures for CUE tracks and stale queue indexes.
- Media3 playback negotiation using a different USB device than the active audio track.
