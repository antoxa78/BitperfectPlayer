# Bitperfect Player

A high-fidelity music player designed specifically for Nvidia Shield Android TV, focusing on bit-perfect audio delivery and seamless integration with both local and network storage.

## Features

*   **Bit-Perfect Audio**: Bypasses system mixers where possible to ensure high-resolution audio (FLAC, WAV, etc.) reaches your DAC as intended.
*   **SMB/Network Shares**: Stream your entire library directly from your PC, NAS, or server using SMB (jcifs-ng).
*   **Android TV Optimized**: A fully native Leanback interface designed for 1080p and 4K displays, controllable entirely by a standard TV remote.
*   **Internal File Explorer**: Robust file browsing that works even on Android TV 14, providing access to internal storage when system pickers are missing.
*   **Library Management**: Easily register local and network folders into a persistent "Local Music Library" on your home screen.
*   **Recursive Scanning**: Deeply scans folders for music files and `.m3u` / `.m3u8` playlists.
*   **Advanced Screensaver**: Features both a "Bouncing Text" mode and a "Pure Black" mode to prevent burn-in on OLED and Plasma screens.
*   **Smart Resume**: Remembers your entire queue, current track, and exact timestamp so you can pick up exactly where you left off.
*   **Detailed Metadata**: Displays bitrate, sample rate, bit depth, and full ID3 tag information (Artist, Album, Track).

## Installation

1.  Download the latest release APK.
2.  Enable "Unknown Sources" on your Android TV device.
3.  Install the APK using a file manager.

## Usage

*   **Browsing**: Use the D-pad to navigate rows.
*   **Adding Music**: Go to "Actions" and choose "Internal Storage" or "Add SMB Source".
*   **Playlist Management**: While browsing, long-press a folder to "Add All" or "Replace" your current playlist.
*   **Now Playing**: Access the "Now Playing" row at the top to see high-res audio details and control playback.
*   **Exit**: Use the dedicated "Exit" button in the main menu to completely release audio resources and close the app.

## Development

Built using Kotlin, AndroidX Media3 (ExoPlayer), and Leanback.

### Dependencies
*   `androidx.media3:media3-exoplayer`
*   `androidx.leanback:leanback`
*   `eu.agno3.jcifs:jcifs-ng`

---
*Developed for audiophiles who want the best sound quality on their Stereo System.*
