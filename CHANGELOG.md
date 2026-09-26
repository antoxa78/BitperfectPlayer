# Changelog

## 3.1.0-beta7 - 2026-09-26

### Added

- **WavPack (`.wv`) playback:** `.wv` was already listed as playable in the browser, the library and the track picker, but nothing could actually play it — media3 has no WavPack extractor — so a listed `.wv` either failed outright or, worse, appeared to play. Decoding is `com.tianscar.javasound:javasound-wavpack` 1.4.0, a pure-Java port of the reference unpacker, rather than vendoring libwavpack and a JNI layer for it. The extractor owns its I/O outright, because neither API on either side of it can seek: media3 1.5.1's `ExtractorInput` has no seek, and `javasound-wavpack` has no seek API of its own. Seeking therefore closes the decoder, reopens it on a stream starting exactly at a block boundary, and decodes-and-discards the remainder of that block. Block headers are parsed as the bytes flow past, so the map from byte offset to first sample is built for free during ordinary playback and a seek into already-played territory is exact with no I/O at all. A seek past that recorded prefix bisects the byte range, using the block-resync scan as a monotonic oracle — an earlier estimate-and-walk scheme cost reads in proportion to the jump distance (a 40-minute seek began decoding at 1.5 MB), whereas bisection is flat at ~150–190 reads however far the jump. `GetSeekPoints`, which may run on the app thread, stops at the estimate and never pays for the search.
- **Playlists (`.m3u`, `.pls`, `.cue`) on SMB shares over MPD:** `add smb://host/share/list.m3u` and `load smb://host/share/list.m3u` now work, so playlists no longer have to be copied to local storage first. `lsinfo` on an `smb://` directory reports a playlist as a playlist rather than as a plain file, so clients list it in the right place, and entries in a playlist read from a share may be relative paths, absolute local paths, or further `smb://` URIs — all three resolve against the playlist's own location. The three playlist parsers moved out of `MainActivity` into `PlaylistParser` so the MPD server and the UI share one implementation.

### Notes

- WavPack bit-exactness is verified per block rather than taken on trust: decoding each of a 300 MB / 44.1 kHz / 16-bit test file's 5373 blocks individually reproduces the CRC stored in that block's header, 5373 of 5373 matching across 118,463,772 samples. Seeking was fuzzed with 200 randomised targets into unexplored territory (200/200 landing exactly, ≤189 reads) and confirmed on-device, with seeks landing on byte offsets identical to host predictions. `WavpackGetNumErrors()` is reported but deliberately not treated as an integrity signal: this port seeds its running block checksum once per open and never resets it between blocks, so a file that decodes perfectly still reports a handful of errors across a full playthrough. Each block's own stored checksum, which the encoder reseeds per block, is the check that means something.
- A clean WavPack playthrough therefore ends with a `decoder reported N error(s)` warning from `WavPackExtractor`. On files that are in fact intact this is the library's own bookkeeping, and the log says so; it is left in place because on a genuinely damaged file it is the only integrity signal available.
- WavPack 5.x is untested rather than known-broken: the Java port nominally declares stream versions 4.2–4.16, and no 5.x sample was available to check against. 4.x is confirmed working.

### Fixed

- **A path containing a space in an `smb://` URI was reported as `%20` and not found:** percent-escapes are now decoded on the way in, since jcifs does not do it itself. This is deliberately receive-side only — per the MPD protocol an argument containing a space is to be wrapped in double quotes (which `tokenize()` already handles), and response values are raw text after `NAME: ` where a literal space is unambiguous. Percent-encoding outgoing URIs would only surface `%20` in clients instead of a readable path.

## 3.1.0-beta6 - 2026-09-25

### Fixed

- **No sound in other apps after Bitperfect Player gives the USB DAC back (on Exit, on pause, or when another app takes audio focus):** in "Bit-perfect (USB driver)" the driver takes the DAC's USB audio interfaces with `force=true`, which detaches the kernel's `snd-usb-audio` — and every other app is silent for exactly as long as that claim lasts. Three separate holes let the claims outlive the moment they should have been dropped:
  - The claims are taken in `configureUsbBitPerfect()`, which the deferred cross-rate reconfiguration calls directly on its own thread, bypassing `configure()`. The service only learned about them through a `configure()` callback, so after a sample-rate switch it believed the driver no longer held the DAC for the rest of the track: it kept no foreground service (so the system could kill the process mid-stream), and the pause/idle/Exit hand-backs all skipped. Ownership is now also read from the sink itself (`UsbAudioSink.ownsUsbDevice`), and the sink reports it from the one place the claims are taken.
  - Another app taking *transient* audio focus leaves `playWhenReady` true, so `onPlayWhenReadyChanged` never fires and the driver kept its exclusive claims — the app that had just taken focus was silent. `onPlaybackSuppressionReasonChanged` now hands the DAC back (without stopping, so this player can regain focus and resume), and the sink re-claims the DAC on the next buffer.
  - Exit killed the process while a hand-back was still running. Ownership is reported as gone at the very *start* of a hand-back, but the claims are only dropped at the end, so `System.exit(0)` took the release thread down with it, the claims were never dropped, and the DAC's kernel driver stayed detached until a physical replug. Exit now waits for an in-flight hand-back (bounded, so a stuck release cannot hang it) and leaves the rebind itself alone in that state. A sink torn down without a hand-back first (`release()`) no longer leaks the claims either.

  The hand-back sequence itself is unchanged (release → `USBDEVFS_RESET` → close, plus the root-only sysfs unbind/bind fallback). On-device investigation showed the alternatives are not better: on a locked SHIELD TV (kernel 4.9) `USBDEVFS_CONNECT` returns `EBUSY` in either ordering, and releasing without the reset leaves the streaming interface deterministically unbound — no `pcmC0D0p`, so the audio HAL's `proxy_open()` fails and other apps are left on a stale route (bad enough to take `audioserver` down with it). The bus reset is the only rebind available without root and is kept.

### Added

- **Other-app audio routing in the debug log:** on the way out of a DAC hand-back, the debug log now records whether other apps can be expected to make sound on the returned DAC — Android TV's "USB Match content audio resolution" switch (`Settings.System usb_audio_mcar`, read only, never written) together with the DAC's advertised encodings. A DAC that advertises no 16-bit profile plus that switch on is the combination that makes 16-bit apps fail `createTrack()` with `-38`, so it is called out with the switch name and the fix. The exit-path log also carries the `USBDEVFS_CONNECT` errno, which is the actual reason a re-bind did or did not take.

## 3.1.0-beta5 - 2026-09-24

### Fixed

- **No sound after switching to another app that plays audio:** on Android TV, media3's own foreground notification is dropped during buffer/end-of-stream transitions and on audio-focus loss, so the service ended up in the cached state and the system killed the process. If that kill landed while the usbdevfs driver still held the DAC (or before the async `USBDEVFS_RESET` hand-back finished), the DAC's kernel driver was left detached and nothing had sound until a physical replug. The service now keeps its own foreground notification whenever the USB driver owns the DAC or a hand-back is in flight, so the process can no longer be killed mid-stream/mid-reset.

## 3.1.0-beta4 - 2026-09-24

### Fixed

- **No sound after switching Settings → Audio Output to "Bit-perfect via Android" or "Standard Android Output":** switching out of "Bit-perfect (USB driver)" while playing left the new system `AudioTrack` opening into a DAC that was still claimed. Two causes are fixed:
  - The old player kept playing through the whole hand-back wait; its `UsbAudioSink` could re-claim the DAC on the next buffer (after an idle release), or its `BitPerfectAudioSink` delegate — no longer blocked once driver ownership flipped to false — could open its own direct track / set `BIT_PERFECT` mixer attributes. The switch now stops the old player *first*, snapshots the queue/position/play-intent beforehand, and reads ownership *before* `stop()` so the soft-replug is always waited out. The rebuilt player resumes from the snapshot instead of from a post-stop timeline placeholder.
  - On SoCs where the `USBDEVFS_RESET` soft-replug re-enumerates the device but never re-binds the kernel `snd-usb-audio` driver, the system modes stayed silent until a physical replug. The sysfs unbind/bind fallback (`forceKernelRebind`, needs root; best-effort via `su`) is wired back into `resetUsbDevice()`, and the switch waits a settle beat after the rebind before rebuilding (matching the exit path).

- **Seeking a SACD ISO re-decoded the track from the start:** `SacdProgressiveMediaExtractor.init()` re-created the extractor on every load (seek that missed the buffer, period reset, retry), so each seek hit a fresh, unopened native reader and the seek was dropped. The extractor is now kept across loads, like `BundledExtractorsAdapter`, and seeks apply to the already-open reader.

- **Transient SMB read errors truncated SACD/DSD tracks:** read/decode failures were raised as `ParserException`, which media3 never retries, so a dropped TCP session skipped the rest of the track. They are now `IOException`s, which media3's load-error policy retries; the native reader re-reads the same sectors, a failed native seek is re-applied from the next read, and the SMB file handle is reopened once on a dropped connection.

- **content:// DSF/DFF files without a file name in the URI were not detected:** the extension check missed MediaStore ids and some document providers, sending DSD files to the default extractors. The provider's `DISPLAY_NAME` is now consulted for `content://` URIs.

- **Native FLAC engine races (USB driver mode):** the pending-seek target and flag pair could drop a seek requested while another was being carried, and the target was read/written unsynchronized; it is now a single atomic exchanged value. `NativeAudioEngine` is `@Synchronized`, so position reads or `setNextFd` racing `destroy()` can no longer dereference freed native memory. A fresh engine is started silently and resumed after the first seek, removing an audible blip at the start of a track on resume.

- **24-bit-in-32-bit USB DACs received the wrong container size:** the alt-setting parser reported the declared *resolution* instead of the *container* size (`bSubslotSize`), so a DAC expecting 32-bit containers was fed 3-byte samples (noise). It now derives the container width from `bSubslotSize`.

- **USB feedback misparsed for non-Q16.16 formats:** some DACs report feedback in another scale (10.14 in 3 bytes, or per-ms frames); the raw value was always divided by 65536. The power-of-two scale is now detected once from the first plausible reading, like Linux `snd-usb-audio`.

- **Audio thread busy-polling / per-URB allocations:** the USB reap loop polled every 125 µs (~8000 wake-ups/s at nice -19); it now sleeps in `poll()` until a URB completes. The residual-merge buffer is reused instead of `malloc`/`free` per submit, and `AsyncBufferedDataSource`'s I/O thread sleeps on a condition variable instead of waking every 5 ms when the buffer is full or at EOF.

- **DoP stream failure left DSD silent or reaching a speaker as noise:** a DoP stream the USB driver cannot open at `dsd_rate/16` (or that it drops mid-stream) now falls back to PCM for that track instead of failing outright, and re-evaluates on the next track. A new DAC or a changed DSD Output setting clears the fallback.

### Changed

- **Menu/icon refresh:** new vector icons for every settings card and dialog option (including DSD, LAN control, network buffer, screensaver, scan), a new launcher icon and banner, and a smaller `app_banner.png`.
- **DSD Output label:** "Convert to PCM" now reads "176.4 / 192 kHz" to reflect the output rate family.

### Removed

- **Settings → Stay Awake While Playing:** the keep-awake behaviour itself is unchanged (still handled automatically during playback), but the explanatory settings card was removed.

## 3.1.0-beta3 - 2026-09-23

### Fixed

- **NVIDIA Shield / Android TV still went to sleep during playback (3.1.0-beta2's fix was incomplete):** on Android TV, "screen off" *is* standby, and there are two independent standby timers. 3.1.0-beta2 switched the playback wake lock from `SCREEN_DIM_WAKE_LOCK` to `PARTIAL_WAKE_LOCK`, which keeps the CPU running but is not counted by `PowerManager` as keeping the device awake — so the normal inactivity timeout (`screen_off_timeout` → system screensaver → `sleep_timeout` "Put device to sleep") could fire again mid-listening, and the inattentive-sleep timer (`attentive_timeout`) was never blockable by any wake lock in the first place. Both are now handled while music is playing:
  - **Normal inactivity timeout:** the visible activity sets `FLAG_KEEP_SCREEN_ON` while `PlaybackService` reports active playback (new `PlaybackService.keepAwake` state mirrored by `BaseActivity`). The app's own screensaver still blanks the panel, so there is no extra burn-in risk; with the player in the background the system timers apply as before.
  - **Inattentive-sleep timer (`attentive_timeout`):** ignores every wake lock and window flag by design. If the user grants `WRITE_SECURE_SETTINGS` once over adb, the service sets it to "never" while playing and puts the user's value back 30 s after playback stops, on Exit/service teardown, and — if the app died while overriding it — on the next service start or boot. A value changed from Android TV settings in the meantime is left alone.
    ```
    adb shell pm grant com.github.antoxa78.bitperfectplayer android.permission.WRITE_SECURE_SETTINGS
    ```

- **Memory corruption and dropped audio at 705.6/768 kHz in "Bit-perfect (USB driver)" mode:** each URB carries 8 microframes of audio, which for 32-bit stereo at 705.6/768 kHz is ~5.6–6.2 KB, but the ring-slot buffers (`USB_AUDIO_URB_BUFFER_SIZE`, also the residual buffer) were sized for 384 kHz at most (4096 bytes). Every URB at those rates was `memcpy`'d past the end of its heap buffer, and leftover data too large for the residual buffer was silently dropped. The buffers are now 8192 bytes (32-bit stereo up to ~1 MHz); a stream whose worst-case URB (nominal rate +1% feedback headroom) still would not fit is refused at creation so playback falls back to system audio instead of corrupting memory, and `submitPcmToUrbs()` refuses to copy an oversized URB as a last line of defence. The initial feedback reading at stream start now gets the same ±1% sanity check as the continuous readings, so a bogus first value can no longer size packets.

- **Gaps between tracks with the native FLAC engine ("Bit-perfect (USB driver)" mode, local FLAC):** at the end of a file the engine's decode thread exited, the 80 ms USB ring drained, and the player then had to seek to the next item, reopen the file and wait for its first buffer before a new engine started — an audible gap (and a DAC underrun) at every track change, breaking gapless albums. The engine is now handed the next local FLAC in advance (`NativeAudioEngine.setNextFd`); a background thread opens and parses it, and at end of stream the decode thread swaps it in and keeps feeding the *same* USB stream, so consecutive tracks are output back to back without a single missing sample (host-tested bit-exact across 16→16, 16→24-bit and three-track chains, and under ThreadSanitizer). The player is then moved to that item purely for its timeline; `UsbAudioSink` ignores the seek-induced `pause()`/`flush()` so the engine is never stopped. The next item follows shuffle and repeat-all, repeat-one loops the same file gaplessly, queue edits re-queue, and a next track with a different sample rate or channel count still ends normally and reconfigures the DAC as before. The non-gapless fallback now also honours repeat-one (it used to skip to the next item).
- **Audio threads ran at normal priority:** the native decode thread — the only thread feeding the USB ring — requested `SCHED_FIFO`, which Android never grants to apps; the failure was ignored, so it ran at default priority. It now raises itself to nice -19 (Android's urgent-audio level, which apps are allowed), as does `UsbStreamingThread` (previously Java `MAX_PRIORITY`, i.e. nice -8). The ExoPlayer→USB path also reuses its PCM arrays instead of allocating one per buffer, removing a steady stream of garbage from the audio path. Fewer dropouts under load (SMB, UI work).
- **"Bit-perfect via Android" never used the Android 14+ bit-perfect mixer for FLAC/ALAC/MP3/AAC:** because the sink advertises float (so 24-bit sources aren't truncated), Media3 asks decoders for float output, but USB `MIXER_BEHAVIOR_BIT_PERFECT` mixers exist only for the DAC's integer formats — the float track never matched one (`No matching BIT_PERFECT mixer found … encoding=4`) and fell back to the resampling system mixer. `BitPerfectAudioSink` now opens the track as int32 (else int24) when such a mixer exists and converts float→int exactly (bit-exact for any ≤24-bit source). No change below Android 14 (the Shield's Android 11 has no 24/32-bit `AudioTrack`).
- **Undefined behaviour in the 16/24→32-bit padding (USB driver):** `padInt16ToInt32`/`padInt24ToInt32`/`shiftInt32From24` left-shifted negative signed samples, which is undefined in C++ before C++20. Now shifted as unsigned — the same bits, without relying on the compiler.
- **Native engine thread leak:** a decode thread that ended by itself at end of file was never joined, leaking its stack each track.

### Added

- **DoP (DSD over PCM) output — Settings → DSD Output:** with "DoP" selected, SACD ISOs, DSF and DFF are sent to the DAC as DoP instead of being converted to PCM in the app, so a DoP-capable DAC (e.g. the DR70) decodes native DSD itself — no in-app filtering at all. The DSD bits are packed 16 per 24-bit sample under the alternating 0x05/0xFA markers at dsd_rate/16 (176.4 kHz for DSD64, up to DSD256 at 705.6 kHz), verified bit-exact on the host. DoP is only used in "Bit-perfect (USB driver)" mode with a USB DAC attached — the one path that delivers the samples untouched; otherwise (and by default) DSD is converted to PCM. The choice is made when each track starts loading. Now Playing shows "DSD64 DoP" / "DSD64 → PCM".
- **DSF and DFF (DSDIFF) playback** — local, content:// and SMB, DSD64 to DSD512, mono/stereo, with seeking. In PCM mode a new native converter uses FFmpeg's dsd2pcm followed by an anti-alias filter designed for each DSD rate (the SACD path's naive sample-dropping for ratios above 2:1 is not used): a tone that would alias into the audio band is suppressed by 100 dB (DSD64), 125 dB (DSD128) and 145 dB (DSD256). DST-compressed DFF and multichannel files are rejected with a clear error.
- **Settings → Stay Awake While Playing:** shows whether the inattentive-sleep timer is handled (permission granted, or the device timer is already "Never") and, if not, the one-time adb command to enable it.

## 3.1.0-beta2 - 2026-09-22

### Fixed

- **Android TV / NVIDIA Shield no longer falls asleep during music playback:** the Shield's standby often came from the Android TV *attentive-sleep* timer (`Settings.Secure.attentive_timeout`, default 5 minutes on many boxes), which puts the TV to sleep after that long with no remote input — even while audio is actively playing with the screen on. That sleep cuts USB power, drops the DAC, and interrupts the stream. The app now holds a `PARTIAL_WAKE_LOCK` for the duration of playback (keeping the CPU from suspending while letting the screen time out normally), and documents that wake locks alone **cannot** suppress the TV-standby timer — the device must also set the sleep timers to never:
  ```
  adb shell settings put secure attentive_timeout 2147483647
  adb shell settings put secure sleep_timeout 2147483647
  ```
- **Android / standard audio output went silent after using the USB-driver mode (regression):** the previous build dropped the `USBDEVFS_RESET` from the driver's DAC-release path (believing it caused disconnects), which left the kernel's `snd-usb-audio` driver un-rebound — so switching out of "Bit-perfect (USB driver)" produced no sound. The reset is restored in `resetUsbDevice()`; the disconnect events it had been blamed for were actually HDMI-CEC/TV power-down cutting power to the whole USB port, not this per-device port reset. The sysfs unbind/bind fallback is no longer needed on the release path.

### Notes

- **Debug help for DAC front-display issues:** `adb shell setprop debug.decent.nofeedback 1` disables the continuous async-feedback polling (keeping the one-shot initial calibration read), for isolating clock-adaptation and display-update interactions with the DAC.

## 3.1.0-beta1 - 2026-09-10

### Added

- **"Standard Android Output" audio output mode:** a third option in Settings → Audio Output alongside "Bit-perfect via Android" and "Bit-perfect (USB driver)". Uses the plain system audio pipeline — the normal Android mixer, normal resampling/volume ducking — with no direct/bit-perfect `AudioTrack` and no bit-perfect mixer-attribute negotiation. Useful when bit-perfect output isn't wanted or isn't working on a given device/DAC.

### Fixed

- **Wrong cover art shown on multi-disc releases:** `NowPlayingActivity.loadAlbumArt()`'s dedup/cache key was `artist|album` (falling back to the track title) only, with no reference to which folder the track actually came from. A box set split into per-disc folders (or any duplicate rip of an album kept in two places) has the same artist/album tags on every disc, so once art was fetched or matched for the first disc, every later disc's tracks matched the same cache key and kept showing that first disc's cover — including a wrong online (MusicBrainz/iTunes) match getting "stuck" across discs. The key now also folds in the track's source folder (`folderKeyFor()`), so each disc/folder is looked up independently while same-folder track changes still skip the redundant network lookup as before.

## 3.0.7-beta1 - 2026-09-08

### Fixed

- **Kernel rebind of the USB-audio driver on Exit could be skipped on some hardware:** `resetUsbDevice()` relied on `USBDEVFS_RESET` alone to re-probe `snd-usb-audio` when handing the DAC back to the system. On a Mi TV box (Android 14) the reset re-enumerates the device at the bus level (Android reports the audio device "added"), but the ALSA card never reappeared — every other app stayed silent until a physical unplug/replug. The driver now also issues `USBDEVFS_CONNECT` on each audio interface after the reset (the exact inverse of the `DISCONNECT` its force-claim issued), which asks the kernel to run its own driver matching instead of relying on the reset side-effect.
- **Sysfs unbind/bind hand-back fallback now works under root:** when the framework still cannot re-arm the vendor HAL (its USB output only recovers on a real host-level detach, which no usbfs call produces), `forceKernelRebind()` performs a genuine sysfs unbind/bind of the USB device. It now also escalates via `su` when the default SELinux policy blocks app-level `/sys/bus/usb` access, so a rooted box can hand the DAC back automatically where a locked one cannot.
- **Exit no longer kills the process mid-release:** the post-exit USB-rebind wait used to trust the USB audio device "added" callback immediately, which can fire while `releaseUsbStream()` is still dropping its interface claims — letting Exit kill the process before the hand-back finished and leaving every other app silent. The release is now always waited out first (bounded by `USB_REBIND_MAX_WAIT_MS`), with the watcher honored only once the release has actually completed.

### Notes

- This is a **beta** of the ongoing "hand the USB DAC back to other apps after Exit" work. Diagnosis confirmed the framework can be handed back correctly, but on non-rooted boxes the vendor USB HAL still requires a real host-level disconnect (a physical replug) before other apps get sound, as described in 3.0.4.

## 3.0.6 - 2026-09-07

### Fixed

- **DAC stays locked on the last played stream rate after Exit:** the usbdevfs release only stopped the stream, drained the in-flight URBs, and soft-replugged (`USBDEVFS_RESET`) so the kernel could rebind its USB-audio driver. But a UAC2 Clock Source's rate is a control value retained in the DAC's firmware — a USB reset re-enumerates the device without power-cycling it, so the last `SET_CUR` (the final track's rate) survived the hand-back and the DAC kept clocking/displaying it until physically unplugged. `releaseUsbForIdle()` now restores the DAC's default idle rate first: it selects alt-setting 0 (zero-bandwidth, freeing the ISO ring) and issues `SET_CUR` to the lowest rate advertised in the device's AS Format Type I descriptors (44.1 kHz fallback) before the soft-replug. This also covers the Settings → Audio Output switch, screen-off/pause, and the LAN-control exit path, all of which hand the DAC back through the same release.

## 3.0.5 - 2026-09-07

### Fixed

- **16-bit PCM never got the Android 14+ bit-perfect mixer:** `BitPerfectAudioSink.isDirectCandidate()` only matched `ENCODING_PCM_24BIT`/`_32BIT`/`_FLOAT`, so a plain 16-bit FLAC/WAV (ordinary CD-quality rips — the most common case) fell through to the delegate's regular `AudioTrack` in "Bit-perfect via Android" mode. That path never calls `BitPerfectManager.updateAudioTrack()`, so `MIXER_BEHAVIOR_BIT_PERFECT` was never requested for 16-bit tracks, leaving the system mixer free to silently resample them (e.g. 44.1kHz → 48kHz) to its internal rate. `isDirectCandidate()` now also matches `ENCODING_PCM_16BIT`, so 16-bit content gets the same direct-`AudioTrack` + bit-perfect-mixer negotiation as 24/32-bit content.
- **Float→int conversion truncated instead of rounding:** `convertFloatToInt16/24/32()` in `usb-audio-output.cpp` (the path used for ExoPlayer-decoded, non-native-FLAC playback through the USB driver) cast the scaled float straight to an integer, which truncates toward zero — a consistent up-to-1-LSB downward bias on every sample. The round-trip is normally exact for on-spec input, but any upstream gain/mixing/resampling that isn't itself power-of-two-exact turns that bias real. Now rounds to nearest (`roundf`/`round`) before clamping and casting.
- **Gapless transition into a different sample rate could stall the main thread:** `UsbAudioSink.configureUsbBitPerfect()` does blocking native ioctls plus a flat 50ms sleep for DAC PLL lock. When the native FLAC engine finished a track and a deferred cross-rate reconfiguration was pending, `cleanupFinishedEngine()` ran that call inline from `onMediaItemTransition()` — the player's application-thread callback, which is the main thread here since `ExoPlayer.Builder` is never given a custom `Looper`. A gapless transition into a track with a different sample rate/channel count therefore briefly stalled the main thread on every such transition. The deferred call now runs on a background thread; `configureUsbBitPerfect()` is guarded by a new `configureLock` so a concurrent call from `configure()` (ExoPlayer's renderer thread, for the same transition) waits for it instead of racing it on the same USB device/fd.

### Removed

- **Dead debug-log readers:** `PlaybackService.readDebugLog()` and `clearDebugLog()` had no remaining callers now that the Settings → Debug Log screen was removed in 3.0.4 — `appendDebugLog()` (still used by the exit/DAC-release path) and its supporting `debugLogDir`/`DEBUG_LOG_FILE`/`debugLogLock` are unaffected.

## 3.0.4 - 2026-09-06

### Fixed

- **Still no sound in other apps after Exit on some devices (root cause):** `UsbAudioDevice.resetUsbDevice()` — the function that hands the DAC back to the system — issued `USBDEVFS_RESET` *before* releasing this app's force-claimed interfaces, with the actual release happening only afterward. While an interface is still claimed via usbfs there is no kernel driver attached to it for the reset's rebind cycle to act on, so the kernel simply preserved the app's own claim straight through the reset; releasing a moment later did not itself trigger a fresh driver probe. The DAC was therefore never genuinely handed back on any Exit, even though the process had already terminated — only a real physical unplug/replug (a from-scratch enumeration) actually restored other apps' sound. `resetUsbDevice()` now releases the streaming and control interfaces first, then resets, then closes the connection.
- **USB rebind detection could falsely report success or exhaust its entire wait doing nothing:** on devices where `/dev/snd` cannot be listed at all (e.g. SELinux-restricted), the post-exit rebind wait had no way to detect the DAC coming back and either gave up after a blind fixed grace or, worse, could spend its whole 10s budget stuck waiting on the driver's internal "release in flight" flag before ever checking for the device — leaving zero time to actually observe the rebind. Detection now falls back to `AudioDeviceCallback.onAudioDevicesAdded` on those devices, registered up front and raced against every wait phase (not only consulted afterward), and a short settle delay is added once a rebind is observed before the process actually exits, matching how the app already treats that same event elsewhere (`USB_SETTLE_MS`) rather than acting on it instantly.

### Removed

- **Settings → Debug Log:** the on-device, adb-free log viewer added while diagnosing the above (needed because wireless debugging disables the USB port entirely on some Android TV boxes, making logcat unavailable with the DAC connected). No longer needed now that the underlying bug is fixed.

## 3.0.3 - 2026-09-05

### Fixed

- **Still no sound in other apps after Exit (3.0.1's fix was insufficient):** `prepareForProcessExit()` requested the DAC release but only waited a fixed 300ms before calling `System.exit(0)`. `releaseUsbForIdle()` merely kicks off a USBDEVFS_RESET soft-replug on a background thread — the kernel can take several seconds to re-probe and re-register the USB sound card afterward (the same window `applyOutputModeAudioReset()` already blocks on for the Settings → Audio Output switch). 300ms was nowhere near enough, so the process was still usually killed mid-reset. `prepareForProcessExit()` now takes a completion callback and does the same bounded wait-for-rebind (`waitForUsbRebind`, capped at 10s) on a background thread; both Exit handlers in `MainFragment.kt` (back-button confirmation and Settings menu) now call `System.exit(0)` from that callback instead of after a fixed delay. Exit can take noticeably longer in the worst case (up to ~10s) since it now genuinely waits for the kernel hand-back, but the app UI closes immediately either way.
- **USB DAC unplugged mid-stream could leave a stale exclusive claim:** `onAudioDevicesRemoved` (fired when the system notices the USB audio device disappear) only called `bitPerfectManager.clear()`, unlike every other DAC-losing path (pause, idle/ended, screen off/on, onDestroy, exit) which also calls `releaseUsbDriverDac()`. The vendored usbdevfs driver has no disconnect detection of its own, so a physical unplug while it owned the DAC could leave `usbDriverOwnsDac` stale. Now calls `releaseUsbDriverDac()` too.

## 3.0.2 - 2026-09-05

### Fixed

- **Tunneling could silently rebuild a non-tunneled direct AudioTrack:** `BitPerfectAudioSink.enableTunnelingV21()` released the active direct track but left `directMode` set, so a `handleBuffer()` call arriving before the next `configure()` would re-create a plain direct `AudioTrack` from the stale format instead of routing to the (tunneled) delegate — contradicting `isDirectCandidate()`'s own tunneling check. `directMode`/`directFormat` are now cleared in the same step as the track.
- **A dead `AudioTrack` could crash mid-teardown:** `releaseDirectTrack()` guarded `track.pause()` against exceptions but called `track.release()` unguarded; a track already in a bad state (e.g. dead object after the DAC drops mid-stream) could throw out of `reset()`/`configure()`/`setAudioAttributes()`/tunneling changes on ExoPlayer's playback thread. `release()` is now wrapped the same way as `pause()`.

## 3.0.1 - 2026-09-05

### Fixed

- **No sound in other apps after exiting the player:** `PlaybackService.onDestroy()` cleared the Android bit-perfect mixer preference but never released the usbdevfs driver's exclusive USB claim on the DAC, so exiting while that mode (the default) was streaming left the DAC unusable for every other app. Worse, both Exit actions (back-button confirmation and the Settings menu) called `System.exit(0)` on the same tick as `stopService()`, which almost always won the race against the service's own (async) teardown and the driver's background release thread. `onDestroy()` now also calls `releaseUsbDriverDac()`, and the Exit handlers request the release synchronously via a new `PlaybackService.prepareForProcessExit()` and give it a short grace period before killing the process.

## 3.0.0 - 2026-09-05

### Added

- **Bit-perfect (USB driver) output mode:** a userspace USB Audio 2.0 driver (vendored from decent-player, `third_party/decent-player/libs/`) now streams PCM directly to the DAC over usbdevfs, bypassing the entire Android audio stack. Local FLAC files are decoded by a native engine writing straight to the USB isochronous endpoint; all other formats use the ExoPlayer pipeline through the driver. The driver claims the DAC only while actively streaming and soft-replugs (USBDEVFS_RESET) on release, so other apps and the system HAL can use the DAC whenever playback is idle.
- **Settings → Audio Output:** new settings card to switch between "Bit-perfect via Android" (direct AudioTrack at the DAC's native rate through the system media stack) and "Bit-perfect (USB driver)". Switching at runtime releases the previous mode's DAC claim, waits for the USB sound card to re-register after the soft-replug, and rebuilds ExoPlayer in the new mode with the queue, position and play-state restored.
- **USB attach handling:** the app now matches USB audio-class devices (`USB_DEVICE_ATTACHED` intent filter + `usb.host` feature), and the service requests USB permission up front so playback does not silently fall back to the AudioTrack path on the first track. A bounded startup probe retries while the DAC is mid-re-enumeration.
- **Instant USB DAC recovery on wake:** the DAC's USB link is torn down during suspend and re-enumerates on wake — slowly and unreliably. The service now registers an `AudioDeviceCallback` and re-negotiates the audio sink the instant the DAC's audio side re-registers (the earliest moment a fresh bit-perfect session can open), instead of relying only on the single-shot wake check that can miss the re-link window.

### Fixed

- **Audio Output switch could stall for up to 60 s:** the wait for the DAC's USB sound card to re-register after the soft-replug keyed on a *new* `controlC` name — but the card usually re-registers under the same index, and `/dev/snd` may not be listable at all, so the switch could hang for the full budget with silence. Detection now watches for the card to unbind and rebind (any index), the budget is bounded at 10 s with a short fixed grace when `/dev/snd` is unobservable, and the player rebuild always proceeds afterwards.
- **Audio Output switch waited when nothing was released:** if the usbdevfs driver did not own the DAC (paused/idle), no USB reset was triggered, yet the switch still waited for a re-enumeration that could never happen. The release now reports whether it actually fired, and the rebuild is immediate when it did not.
- **Re-selecting the active Audio Output mode tore the DAC down for nothing:** the dialog now no-ops when the selected mode equals the effective current mode (including the migrated legacy setting).
- **Settings subtitle could disagree with the service:** the "Audio Output" card now applies the same legacy-`usbdevfs_driver` migration and removed-mode mapping as the service, so the displayed mode always matches actual playback.
- **No other app can play audio after the Shield wakes from sleep (USB DAC):** on Android 11 the `usb_audio` HAL is direct-only — while the player's AudioTrack is attached, the DAC's output is pinned to the track's sample rate and every other app's 48 kHz stream fails with `EINVAL` (silence everywhere else). Media3 keeps the track attached on pause and the framework's dead-object auto-restore re-attaches it, so the lock outlived pause and sleep/wake. The track is now released whenever playback is not actively running: on pause, on screen-off, and on wake — the DAC returns to the default mix rate and other apps play again immediately. Resume re-negotiates a fresh bit-perfect stream (`play()` re-prepares from `STATE_IDLE`; the MPD server reports the released state as "pause", not "stop").
- **Android 14+ stale bit-perfect mixer preference after sleep:** the uid-scoped `setPreferredMixerAttributes` preference survives suspend in AudioService; it is now cleared on `ACTION_SCREEN_ON`, and the sink is re-negotiated when playback is still active.

### Changed

- The legacy "usbdevfs driver" boolean setting is migrated to the new `audio_output_mode` preference on first read; the removed "Default (Android audio)" mode maps to "Bit-perfect via Android".
- media3 is pinned to 1.5.1 across all configurations (the vendored driver wrapper builds against the same version).

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
