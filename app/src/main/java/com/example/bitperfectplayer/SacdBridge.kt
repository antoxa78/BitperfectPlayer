package com.example.bitperfectplayer

/** JNI bridge to the native SACD ISO -> DSD -> PCM decoder (libbitperfectplayer.so). */
object SacdBridge {
    init {
        System.loadLibrary("bitperfectplayer")
    }

    external fun nativeLibraryVersion(): String

    /** Returns JSON metadata for the given area (0 = stereo, 1 = multichannel). */
    external fun nativeAlbumInfo(isoPath: String, area: Int): String

    /** Decodes one track to a 24-bit PCM WAV. Returns a status string. */
    external fun nativeDecodeTrackToWav(
        isoPath: String,
        area: Int,
        track: Int,
        outHz: Int,
        outWavPath: String
    ): String

    // ── Streaming reader API (SMB / remote ISOs) ────────────────────────────
    // The SacdRandomAccess object receives all ISO block reads via JNI callbacks.

    /** Opens a streaming decoder for one track. Returns a native handle (0 on failure). */
    external fun nativeOpenSacd(
        reader: SacdRandomAccess,
        area: Int,
        track: Int,
        outHz: Int
    ): Long

    /** Decodes up to maxFrames PCM frames into 24-bit packed bytes. Empty on EOF/error. */
    external fun nativeSacdReadInt24(handle: Long, maxFrames: Int): ByteArray

    /** Decodes up to maxFrames PCM frames into interleaved float32 bytes. Empty on EOF/error. */
    external fun nativeSacdReadFloat(handle: Long, maxFrames: Int): ByteArray

    /** Seeks to an absolute output frame index. Returns 0 on success. */
    external fun nativeSacdSeek(handle: Long, frame: Long): Int

    external fun nativeSacdClose(handle: Long)

    external fun nativeSacdOutRate(handle: Long): Int
    external fun nativeSacdChannels(handle: Long): Int
    external fun nativeSacdTotalFrames(handle: Long): Long
    external fun nativeSacdDurationMs(handle: Long): Long

    /** Album metadata for a callback-backed ISO. Returns a JSON string. */
    external fun nativeAlbumInfoReader(reader: SacdRandomAccess, area: Int): String
}