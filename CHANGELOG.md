# Changelog

## 2.8.3 - 2026-08-26

### Fixed

- **SMB data source thread safety:** Added synchronization to all SmbDataSource methods to prevent race conditions when ExoPlayer accesses the data source from multiple threads.
- **MPD queue version race condition:** `scheduleVersionBump()` now increments the pending bump token inside the queue lock to prevent two threads from obtaining the same token.
- **MPD nullable Boolean crash:** `binaryResponseSent.get()` (a nullable `ThreadLocal<Boolean>`) is now compared with `!= true` instead of using the `!` operator, preventing potential `NullPointerException`.
- **Null-safe URI authority access:** `DocumentsContract.buildTreeDocumentUri()` calls no longer use `!!` on `uri.authority`, preventing crashes on malformed URIs.
- **Playlist parser null safety:** M3U, PLS, and CUE parser loops no longer use `line!!`, safely handling null reads.
- **Browse adapter null safety:** `getItem()!!` in list adapters replaced with null-safe returns to prevent crashes on invalid positions.
- **SMB file resource cleanup:** `SmbDataSource.close()` now nulls the `SmbFile` reference alongside `SmbRandomAccessFile` to ensure full resource release.

### Improved

- **Removed redundant `@Volatile`** on `PlaybackService.activeMediaId` which is already protected by `icyInfoLock`.

## 2.8.2 - 2026-08-24

### Fixed

- **MPD playlist flashing empty in MALP:** Bursts of queue edits triggered one `changed` event each, so MALP cleared and refetched the playlist repeatedly. Version changes are now settled/coalesced into a single bump, and the queue version survives restarts so clients don't refetch an unchanged playlist.
- **MPD command during idle racing the event write:** A command arriving while `idle` was pending could emit `changed:` on top of the command's own response, causing "connection reset" + playlist refetch cycles in MALP. The pending idle event is now consumed and dispatched instead.
- **MPD empty playlist right after a process restart:** The saved queue is now restored at service start, so LAN clients never see a briefly-empty playlist.
- **M3U titles polluted with `tvg-*` attributes:** Title extraction now uses the last comma (attributes sit between the duration and the title).
- **Stored-playlist rename command:** Fixed a stray whitespace that produced `unknown command` for `rename`.

### Added

- **Album art over MPD:** `albumart` command, with online cover lookup for live streams in search results.
- **Live track metadata on system surfaces:** ICY stream info is now pushed to the MediaSession, so the Shield home Now Playing bar and other controllers show the real track/artist instead of "Bitperfect Player - Unknown".

### Improved

- **Shared track info resolution:** Extracted into `TrackInfoResolver` so the Now Playing screen and the main-screen card always show identical title/artist/album (including ICY streams, fallbacks, and "Artist - Title" splitting), with duplicate artist/album rows removed.

## 2.7.1 - 2026-08-18

### Fixed

- **Resume playback for radio stations:** Live streams (duration unknown) previously persisted an unbounded playback position. Restoring it made ExoPlayer seek past the stream end on seekable radio streams (e.g. MP3 with Xing/VBR headers), instantly ending playback instead of resuming the last station. Live streams now always save/restore position 0, including the Exit path and USB DAC sink resets.
- **Auto-resume after a radio error:** If a station errored and the reconnect loop left the player idle with items queued, relaunching the app no longer leaves it dead — it gets a fresh prepare+play.
- **Radio stations not resuming with a USB DAC:** The 1.5s startup DAC reset raced with auto-resume. A stream still buffering reported `isPlaying == false`, so the reset's restore step never called `play()` and the station stayed paused forever. The sink reset now captures and restores `playWhenReady` (play intent) instead of `isPlaying`.

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
