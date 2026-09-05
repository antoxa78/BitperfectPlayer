# Changelog

## Unreleased

### Added

- **Instant USB DAC recovery on wake:** the DAC's USB link is torn down during suspend and re-enumerates on wake — slowly and unreliably. The service now registers an `AudioDeviceCallback` and re-negotiates the audio sink the instant the DAC's audio side re-registers (the earliest moment a fresh bit-perfect session can open), instead of relying only on the single-shot wake check that can miss the re-link window.

### Fixed

- **No other app can play audio after the Shield wakes from sleep (USB DAC):** on Android 11 the `usb_audio` HAL is direct-only — while the player's AudioTrack is attached, the DAC's output is pinned to the track's sample rate and every other app's 48 kHz stream fails with `EINVAL` (silence everywhere else). Media3 keeps the track attached on pause and the framework's dead-object auto-restore re-attaches it, so the lock outlived pause and sleep/wake. The track is now released whenever playback is not actively running: on pause, on screen-off, and on wake — the DAC returns to the default mix rate and other apps play again immediately. Resume re-negotiates a fresh bit-perfect stream (`play()` re-prepares from `STATE_IDLE`; the MPD server reports the released state as "pause", not "stop").
- **Android 14+ stale bit-perfect mixer preference after sleep:** the uid-scoped `setPreferredMixerAttributes` preference survives suspend in AudioService; it is now cleared on `ACTION_SCREEN_ON`, and the sink is re-negotiated when playback is still active.

## 2.9.1 - 2026-09-02

### Fixed

- **Install on 32-bit Android TV:** release APK now includes native libs for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64), fixing `INSTALL_FAILED_NO_MATCHING_ABIS` on 32-bit devices like Xiaomi Mi TV.
- **Stream startup delay:** reduced minimum buffer from 60s to 5s — radio streams now start in ~5 seconds instead of ~60.
- **Radio stream buffering:** increased playback back-buffer from 2.5s to 5s, giving more headroom to absorb network jitter during peak hours.

## 2.9.0 - 2026-08-30

### Added

- **SACD ISO playback (DSD/DST → PCM):** Super Audio CD images (`.iso`) play natively over SMB or local storage. A native decoder (sacd-ripper libsacd + FFmpeg dsd2pcm) converts DSD/DST to float32 PCM at 176.4 kHz and feeds it through the existing bit-perfect `AudioTrack` chain. Stereo tracks are listed as individual items with correct titles, artists, and durations.
- **SACD ISO support in the MPD server:** ISOs now appear in `lsinfo`/`listall` and expand into their tracks when added/played from a remote client (MALP etc.).
- **MediaSession playback resumption:** the persisted queue/index/position is restored when the system resumes playback after the process is killed.

### Fixed

- **Startup crash:** registering the BouncyCastle security provider before OkHttp's TLS setup could race `SSLContext.init` ("BKS not found"); the SMB context is now pre-warmed after the HTTP client is built.
- **Playback reliability for SACD:** transient SMB errors no longer truncate a track (decode errors now retry instead of ending the stream), the extractor's native decoder no longer leaks on seeks, and the per-track SMB connection is closed on release.
- **Decode throughput:** the DSD→PCM decimation FIR is NEON-vectorized and the two channels decode in parallel, raising throughput from ~0.7× to ~1.7× realtime so playback no longer drains its buffer and stutters.

## 2.8.5 - 2026-08-29

### Added

- **SMB network share browsing over MPD:** Configured Samba shares are now exposed as virtual `smb://` roots in `lsinfo`, so remote MPD clients can browse them. `smb://` URIs (files or directories) expand into playable media items with embedded credentials — the player streams directly from the share via jcifs-ng; no files are transferred.
- **Folder album-art for `smb://` tracks** in MPD `albumart` responses.

### Fixed

- **MPD client connection drops on unroutable SMB hosts:** Jcifs exceptions (e.g. "network name cannot be found") are now converted into proper MPD ACK errors instead of closing the client socket.
- **Stray exceptions in the MPD command loop** now return `ACK 56` instead of terminating the connection.

### Improved

- `smb://` directory URIs are normalized with a trailing slash so jcifs-ng child paths keep the full share prefix (`smb://host/share/child`).

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
