package com.example.bitperfectplayer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug/test screen for the SACD ISO decoder. Not part of the normal player UI;
 * launch it via adb:
 *   adb shell am start -n com.github.antoxa78.bitperfectplayer/.SacdTestActivity
 * Optionally pass an ISO to test directly:
 *   adb shell am start -n com.github.antoxa78.bitperfectplayer/.SacdTestActivity \
 *       --es iso_path /storage/emulated/0/Album.iso
 */
class SacdTestActivity : androidx.activity.ComponentActivity() {

    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var decoding = false

    private val isos = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        content.addView(TextView(this).apply {
            text = "SACD ISO Decoder Test"
            textSize = 34f
            setPadding(0, 0, 0, 24)
        })
        status = TextView(this).apply {
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }
        content.addView(status)
        val scanBtn = Button(this).apply { text = "Rescan for ISOs" }
        scanBtn.setOnClickListener { scanForIsos() }
        content.addView(scanBtn)
        content.addView(Button(this).apply {
            text = "Grant all-files access"
            setOnClickListener { openAllFilesSetting() }
        })
        scroll.addView(content)
        setContentView(scroll)

        appendStatus("Native: ${trySafely { SacdBridge.nativeLibraryVersion() } ?: "lib missing"}")
        appendStatus("Decode: SACD ISO -> DSD -> PCM 176400 Hz (24-bit WAV output)")

        intent.getStringExtra("iso_path")?.let { p ->
            val f = File(p)
            if (f.isFile) {
                isos.clear()
                isos.add(f)
                renderIsoList()
            } else {
                appendStatus("Extra iso_path not readable: $p")
            }
        }
        if (isos.isEmpty()) scanForIsos()
    }

    private fun openAllFilesSetting() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            appendStatus("No all-files-access settings available (already granted?)")
        }
    }

    private fun appendStatus(line: String) {
        status.text = status.text.toString().let { if (it.isEmpty()) line else "$it\n$line" }
    }

    private fun scanForIsos() {
        appendStatus("Scanning shared storage for *.iso ...")
        Thread {
            val found = mutableListOf<File>()
            val roots = listOf(
                android.os.Environment.getExternalStorageDirectory(),
                File("/storage/emulated/0")
            ).distinct().filter { it.exists() }
            val budget = intArrayOf(0)
            for (r in roots) collectIsos(r, found, 0, budget)
            handler.post {
                isos.clear()
                isos.addAll(found.sortedBy { it.absolutePath })
                appendStatus(if (isos.isEmpty()) "No ISO files found." else "Found ${isos.size} ISO(s).")
                renderIsoList()
            }
        }.start()
    }

    private fun collectIsos(dir: File, out: MutableList<File>, depth: Int, budget: IntArray) {
        if (depth > 6 || budget[0] >= 20_000) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (budget[0]++ >= 20_000) return
            if (f.isDirectory) {
                if (!f.name.startsWith(".")) collectIsos(f, out, depth + 1, budget)
            } else if (f.extension.equals("iso", ignoreCase = true)) {
                out.add(f)
            }
        }
    }

    private fun renderIsoList() {
        content.removeViews(4, content.childCount - 4) // keep header, status, buttons
        for (iso in isos) content.addView(isoSection(iso))
    }

    private fun isoSection(iso: File): ViewGroup {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 24)
        }
        section.addView(TextView(this).apply {
            text = iso.path
            textSize = 22f
        })
        val infoBtn = Button(this).apply { text = "Load album info + tracks" }
        infoBtn.setOnClickListener {
            infoBtn.isEnabled = false
            Thread {
                try {
                    val json = SacdBridge.nativeAlbumInfo(iso.absolutePath, 0)
                    handler.post {
                        infoBtn.isEnabled = true
                        addAlbumInfo(iso, section, JSONObject(json))
                    }
                } catch (e: Throwable) {
                    handler.post {
                        infoBtn.isEnabled = true
                        appendStatus("Album info failed for ${iso.name}: $e")
                    }
                }
            }.start()
        }
        section.addView(infoBtn)
        return section
    }

    private fun addAlbumInfo(iso: File, section: LinearLayout, obj: JSONObject) {
        val album = obj.optString("album_title")
        val artist = obj.optString("album_artist")
        val count = obj.optInt("track_count")
        appendStatus("Album: $artist — $album ($count tracks)")
        section.addView(TextView(section.context).apply {
            text = "$artist — $album (${count} tracks)"
            textSize = 20f
        })

        val tracks = obj.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until tracks.length()) {
            val t = tracks.getJSONObject(i)
            val title = t.optString("title")
            val dur = t.optLong("duration_ms")
            val b = Button(section.context).apply {
                text = "Track ${i + 1}: $title [${msToMinSec(dur)}] — decode"
            }
            b.setOnClickListener { decodeTrack(iso, 0, i, b) }
            section.addView(b)
        }
    }

    private fun decodeTrack(iso: File, area: Int, track: Int, button: Button) {
        if (decoding) {
            appendStatus("A decode is already running; please wait.")
            return
        }
        decoding = true
        button.isEnabled = false
        appendStatus("Decoding ${iso.name} track ${track + 1} ... (logcat tag SacdBridge)")

        Thread {
            val outDir = getExternalFilesDir(null) ?: cacheDir
            val out = File(outDir, "${iso.nameWithoutExtension}.t${track + 1}.wav")
            val result = try {
                SacdBridge.nativeDecodeTrackToWav(iso.absolutePath, area, track, 176400, out.absolutePath)
            } catch (e: Throwable) {
                "CRASH: $e"
            }
            handler.post {
                decoding = false
                button.isEnabled = true
                appendStatus("Result: $result  ->  ${out.absolutePath} (${out.length()} B)")
                Log.i("SacdTest", "decode result: $result out=${out.absolutePath}")
            }
        }.start()
    }

    private fun msToMinSec(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private inline fun <T> trySafely(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        null
    }
}