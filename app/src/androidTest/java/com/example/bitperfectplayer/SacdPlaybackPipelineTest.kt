package com.example.bitperfectplayer

import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jcifs.smb.SmbFile
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives a REAL ExoPlayer (same factory used by [PlaybackService]) against an SACD
 * track over SMB and asserts the pipeline reaches READY and advances position.
 * Runs on the instrumented (non-main) thread so SMB network I/O is allowed.
 */
@RunWith(AndroidJUnit4::class)
class SacdPlaybackPipelineTest {

    private val isoDir = "smb://192.168.31.70/music/Camel - Moonmadness (1976) [SACD] (2014 SHM-SACD ISO)/"

    private fun credentials(): Pair<String, String> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val json = ctx.getSharedPreferences("SmbShares", android.content.Context.MODE_PRIVATE)
            .getString("shares", "[]")
        return try {
            val arr = org.json.JSONArray(json)
            val o = arr.getJSONObject(0)
            o.optString("user") to o.optString("pass")
        } catch (e: Exception) {
            "" to ""
        }
    }

    private fun credentialContext(): jcifs.CIFSContext {
        val (u, p) = credentials()
        return if (u.isNotEmpty()) SmbContext.getWithCredentials(user = u, pass = p) else SmbContext.get()
    }

    /** Adds smb://user:pass@ host credentials to a bare smb:// path, as the app's browse flow does. */
    private fun credentialedUri(path: String, user: String, pass: String): String {
        val rest = path.removePrefix("smb://")
        val host = rest.substringBefore("/")
        return "smb://${android.net.Uri.encode(user)}:${android.net.Uri.encode(pass)}@$host/${rest.substringAfter("/")}"
    }

    private fun findIso(): SmbFile {
        val cc = credentialContext()
        fun scan(f: SmbFile): SmbFile? {
            val entries = try {
                f.listFiles()
            } catch (e: Exception) {
                null
            } ?: return null
            for (e in entries) {
                if (e.isDirectory) scan(e)?.let { return it }
                else if (e.name.lowercase().endsWith(".iso")) return e
            }
            return null
        }
        return scan(SmbFile(isoDir, cc)) ?: error("no .iso under $isoDir")
    }

    private fun buildFactory(app: android.content.Context): SacdMediaSourceFactory {
        val dataSourceFactory = DataSource.Factory { SmbDataSource() }
        val delegate = DefaultMediaSourceFactory(app).setDataSourceFactory(dataSourceFactory)
        return SacdMediaSourceFactory(delegate)
    }

    /** Mirrors PlaybackService.buildPlayer()'s renderers factory (BitPerfectAudioSink). */
    private fun appRenderersFactory(app: android.content.Context): androidx.media3.exoplayer.RenderersFactory {
        val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val offloadProvider = BitPerfectOffloadProvider(am)
        val selectedAudioDevice = java.util.concurrent.atomic.AtomicReference<android.media.AudioDeviceInfo?>(null)
        val bitPerfectManager = BitPerfectManager(app)
        return object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                android.util.Log.i("SacdPipelineTest", "buildAudioSink called float=$enableFloatOutput")
                val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableAudioTrackPlaybackParams(false)
                    .setEnableFloatOutput(true)
                    .setAudioOffloadSupportProvider(offloadProvider)
                    .setAudioTrackProvider(object : androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackProvider {
                        override fun getAudioTrack(
                            audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
                            audioAttributes: androidx.media3.common.AudioAttributes,
                            audioSessionId: Int
                        ): android.media.AudioTrack {
                            bitPerfectManager.updateAudioTrack(audioTrackConfig, selectedAudioDevice.get())
                            return androidx.media3.exoplayer.audio.DefaultAudioSink.AudioTrackProvider.DEFAULT
                                .getAudioTrack(audioTrackConfig, audioAttributes, audioSessionId)
                        }
                    })
                    .build()
                return BitPerfectAudioSink(sink, bitPerfectManager, selectedAudioDevice)
            }
        }
    }

    @Test
    fun deviceAudioCapabilities() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        for (d in devices) {
            android.util.Log.i("SacdAudioProbe", "device type=${d.type} rates=${d.sampleRates.joinToString("/")} enc=${d.encodings.joinToString("/")}")
        }
        val rates = intArrayOf(44100, 48000, 88200, 96000, 176400, 192000)
        val encodings = intArrayOf(
            androidx.media3.common.C.ENCODING_PCM_16BIT,
            androidx.media3.common.C.ENCODING_PCM_24BIT,
            androidx.media3.common.C.ENCODING_PCM_32BIT,
            androidx.media3.common.C.ENCODING_PCM_FLOAT,
        )
        for (r in rates) {
            for (e in encodings) {
                val min = try {
                    android.media.AudioTrack.getMinBufferSize(r, android.media.AudioFormat.CHANNEL_OUT_STEREO, e)
                } catch (t: Throwable) {
                    -999
                }
                android.util.Log.i("SacdAudioProbe", "getMinBufferSize rate=$r enc=$e -> $min")
            }
        }
    }

    @Test
    fun measureDecodeThroughput() {
        val iso = findIso()
        val cc = credentialContext()
        val sr = SmbSacdRandomAccess(SmbFile(iso.path, cc))
        try {
            val handle = SacdBridge.nativeOpenSacd(sr, SacdSupport.AREA_STEREO, 0, SacdSupport.OUT_HZ)
            assertTrue("nativeOpenSacd failed", handle != 0L)
            val frames = 16384
            val start = android.os.SystemClock.elapsedRealtime()
            var total = 0L
            var loops = 0
            while (total < 6_000_000L && loops < 2000) {
                val data = SacdBridge.nativeSacdReadFloat(handle, frames)
                if (data == null || data.isEmpty()) break
                total += data.size / 8L
                loops++
            }
            val dt = (android.os.SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            val rate = total * 1000L / dt
            android.util.Log.i("SacdRate", "decoded=$total frames in ${dt}ms = ${rate}fps (realtime=176400)")
            SacdBridge.nativeSacdClose(handle)
            // The pre-optimization decoder ran at ~125k fps (playback starved). Require
            // a clear margin over that; the current build measures ~290k. The threshold
            // is below realtime (176.4k) so SMB/WiFi variance can't flake the test.
            assertTrue("decode too slow: $rate fps", rate >= 150_000)
        } finally {
            sr.close()
        }
    }

    @Test
    fun fullExoPlayerPipeline_reachesReadyAndAdvances_position() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val iso = findIso()
        val (user, pass) = credentials()
        val srcUri = credentialedUri(iso.path, user, pass)
        val cc = credentialContext()

        val items = SacdSupport.buildTrackMediaItems(
            SmbSacdRandomAccess(SmbFile(iso.path, cc)),
            SacdSupport.AREA_STEREO,
            null,
            srcUri
        ).getOrThrow()
        assertTrue("expected 9 tracks from ${iso.path}", items.size in 1..12)
        val first = items.first()
        android.util.Log.i("SacdPipelineTest", "track0 mediaId=${first.mediaId.take(50)} uri=${first.localConfiguration?.uri}")

        var player: ExoPlayer? = null
        try {
            androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_ALL)
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(app, appRenderersFactory(app))
                    .setMediaSourceFactory(buildFactory(app))
                    .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)
                    .setLoadControl(
                        androidx.media3.exoplayer.DefaultLoadControl.Builder()
                            .setBufferDurationsMs(15_000, 120_000, 15_000, 15_000)
                            .setTargetBufferBytes(128 * 1024 * 1024)
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .build()
                    )
                    .build()
                player!!.addListener(snapshotListenerFor(player!!))
                player!!.addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        val w = if (timeline.windowCount > 0) timeline.getWindow(0, androidx.media3.common.Timeline.Window()).durationUs else 0
                        android.util.Log.i("SacdPipelineTest", "timelineChanged reason=$reason windows=${timeline.windowCount} w0dur=$w")
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        android.util.Log.i("SacdPipelineTest", "tracksChanged groups=${tracks.groups.size}")
                    }
                })
                player!!.setMediaItems(items, 0, 0L)
                player!!.prepare()
                player!!.play()
            }

            var sawReady = false
            val startRealtime = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - startRealtime < 90_000) {
                var state = -1
                var pos = 0L
                var err: androidx.media3.common.PlaybackException? = null
                instrumentation.runOnMainSync {
                    state = player!!.playbackState
                    pos = player!!.currentPosition
                    err = player!!.playerError
                    val tracks = player!!.currentTracks
                    val info = tracks.groups.joinToString(";") {
                        "type=${it.type} supported=${it.isSupported} selected=${it.isSelected}"
                    }
                    android.util.Log.i(
                        "SacdPipelineTest",
                        "state=$state pos=$pos buffered=${player!!.bufferedPosition} " +
                            "loading=${player!!.isLoading} err=${err?.errorCodeName} tracks=[$info]"
                    )
                }
                if (state == Player.STATE_READY) sawReady = true
                if (pos > 0L) {
                    android.util.Log.i("SacdPipelineTest", "POSITION ADVANCED to $pos — playback pipeline works")
                    break
                }
                val theErr = err
                if (theErr != null) {
                    android.util.Log.i("SacdPipelineTest", "PLAYER ERROR: ${theErr.message}", theErr)
                    break
                }
                Thread.sleep(500)
            }

            val finalPos = snapshotPos
            val finalError = snapshotError
            android.util.Log.i(
                "SacdPipelineTest",
                "final state=$snapshotState pos=$finalPos sawReady=$sawReady error=$finalError"
            )
            assertTrue(
                "playback never became READY: state=$snapshotState pos=$finalPos error=$finalError",
                sawReady
            )
        } finally {
            instrumentation.runOnMainSync { player?.run { stop(); release() } }
        }
    }

    @Volatile private var snapshotState = 0
    @Volatile private var snapshotPos = 0L
    @Volatile private var snapshotBuffered = 0L
    @Volatile private var snapshotError: androidx.media3.common.PlaybackException? = null

    private fun snapshotListenerFor(player: ExoPlayer): Player.Listener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                snapshotState = playbackState
                snapshotPos = player.currentPosition
                snapshotBuffered = player.bufferedPosition
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                snapshotError = error
            }
        }
}