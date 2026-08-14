# Changelog

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
