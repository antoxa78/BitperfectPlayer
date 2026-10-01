package com.example.bitperfectplayer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * CUE sheet model and parser. Plain Kotlin with no Android or Media3 types, so
 * it runs in JVM unit tests ([PlaylistParser.parseCueFromStream] turns the
 * result into MediaItems).
 *
 * Covers what real-world rips contain:
 *  - Several FILE lines per sheet (one file per track, multi-disc sheets,
 *    "gaps appended to previous track" layouts). Each track is tied to the file
 *    that was current when its INDEX 01 was read, not when TRACK was read,
 *    because EAC-style sheets put a track's INDEX 00 in the previous file.
 *  - Text in UTF-8 (with or without BOM), UTF-16 (with or without BOM), or a
 *    legacy 8-bit code page; see [decode].
 *  - Loose formatting: extra whitespace, lower-case keywords, unquoted values,
 *    Windows path separators, malformed INDEX times (that one INDEX line is
 *    skipped instead of the whole sheet failing).
 *  - Data tracks (TRACK nn MODE1/2352 on enhanced CDs) are dropped.
 */
data class CueSheet(
    val title: String?,
    val performer: String?,
    val tracks: List<Track>,
) {
    data class Track(
        val number: Int,
        /** FILE value as written in the sheet (backslashes normalised to '/'). */
        val file: String,
        val title: String?,
        val performer: String?,
        /** Start of the track inside [file] (INDEX 01, or the first INDEX if 01 is missing). */
        val startMs: Long,
        /** Start of the next track in the same file; null = play to the end of [file]. */
        val endMs: Long?,
    )

    companion object {
        private val WS = Regex("\\s+")
        private val FILE_TYPE = Regex("[A-Z0-9]+")

        /**
         * Decodes raw CUE bytes to text:
         *  1. a BOM (UTF-8, UTF-16LE/BE) wins;
         *  2. BOM-less UTF-16 is spotted by its zero bytes (CUE keywords are ASCII);
         *  3. strictly valid UTF-8 (which includes plain ASCII) is taken as UTF-8;
         *  4. otherwise it's a legacy code page: [legacyCharset] is asked first
         *     (a caller with a code page detector can pass one in; the app does
         *     not, since Android's ICU CharsetDetector is a hidden API), and
         *     failing that we choose between windows-1251 and windows-1252
         *     ourselves.
         */
        fun decode(bytes: ByteArray, legacyCharset: (ByteArray) -> Charset? = { null }): String {
            fun b(i: Int) = bytes[i].toInt() and 0xFF
            if (bytes.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF)
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            if (bytes.size >= 2 && b(0) == 0xFF && b(1) == 0xFE)
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes.size >= 2 && b(0) == 0xFE && b(1) == 0xFF)
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bomlessUtf16(bytes)?.let { return String(bytes, it) }
            strictUtf8(bytes)?.let { return it }
            val cs = try { legacyCharset(bytes) } catch (_: Exception) { null } ?: guessWindowsCodePage(bytes)
            return String(bytes, cs)
        }

        private fun bomlessUtf16(bytes: ByteArray): Charset? {
            val n = minOf(bytes.size, 512) and 1.inv()
            if (n < 8) return null
            var zeroEven = 0; var zeroOdd = 0
            for (i in 0 until n) if (bytes[i].toInt() == 0) { if (i % 2 == 0) zeroEven++ else zeroOdd++ }
            val half = n / 2
            return when {
                zeroOdd > half * 0.4 && zeroEven < half * 0.05 -> Charsets.UTF_16LE
                zeroEven > half * 0.4 && zeroOdd < half * 0.05 -> Charsets.UTF_16BE
                else -> null
            }
        }

        private fun strictUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) { null }

        /**
         * Last-resort guess between the two most common legacy code pages.
         * Cyrillic text in windows-1251 is made of whole words of high bytes,
         * while Western text in windows-1252 has mostly isolated accented
         * letters inside ASCII words.
         */
        internal fun guessWindowsCodePage(bytes: ByteArray): Charset {
            var high = 0; var inRun = 0
            for (i in bytes.indices) {
                if (!isHigh(bytes, i)) continue
                high++
                if (isHigh(bytes, i - 1) || isHigh(bytes, i + 1)) inRun++
            }
            val name = if (high > 0 && inRun * 2 > high) "windows-1251" else "windows-1252"
            return try { Charset.forName(name) } catch (_: Exception) { Charsets.ISO_8859_1 }
        }

        private fun isHigh(bytes: ByteArray, i: Int) = i in bytes.indices && (bytes[i].toInt() and 0xFF) >= 0x80

        /** Parses decoded CUE text. Never throws; a sheet with nothing usable gives no tracks. */
        fun parse(text: String): CueSheet {
            class Pending(val number: Int) {
                var title: String? = null
                var performer: String? = null
                var index01: Pair<String, Long>? = null
                var firstIndex: Pair<String, Long>? = null
            }

            var albumTitle: String? = null
            var albumPerformer: String? = null
            var file: String? = null
            val pending = mutableListOf<Pending>()
            var current: Pending? = null
            var skippingDataTrack = false

            for (raw in text.lineSequence()) {
                val line = raw.trim().removePrefix("﻿").trim()
                if (line.isEmpty()) continue
                val sp = line.indexOfFirst { it.isWhitespace() }
                val keyword = (if (sp < 0) line else line.substring(0, sp)).uppercase(Locale.ROOT)
                val rest = if (sp < 0) "" else line.substring(sp).trim()

                when (keyword) {
                    "FILE" -> file = fileValue(rest)
                    "TRACK" -> {
                        val parts = rest.split(WS)
                        val type = parts.getOrNull(1)?.uppercase(Locale.ROOT)
                        skippingDataTrack = type != null && type != "AUDIO"
                        if (skippingDataTrack) {
                            current = null
                        } else {
                            val p = Pending(parts.firstOrNull()?.toIntOrNull() ?: (pending.size + 1))
                            pending.add(p)
                            current = p
                        }
                    }
                    "TITLE", "PERFORMER" -> {
                        if (skippingDataTrack) continue
                        val value = unquote(rest).trim().ifEmpty { null }
                        val p = current
                        when {
                            p == null && keyword == "TITLE" -> albumTitle = value
                            p == null -> albumPerformer = value
                            keyword == "TITLE" -> p.title = value
                            else -> p.performer = value
                        }
                    }
                    "INDEX" -> {
                        val p = current ?: continue
                        val f = file ?: continue
                        val parts = rest.split(WS)
                        val idx = parts.getOrNull(0)?.toIntOrNull() ?: continue
                        val time = parts.getOrNull(1)?.let { parseTime(it) } ?: continue
                        if (p.firstIndex == null) p.firstIndex = f to time
                        if (idx == 1 && p.index01 == null) p.index01 = f to time
                    }
                }
            }

            val starts = pending.mapNotNull { p -> (p.index01 ?: p.firstIndex)?.let { p to it } }
            val tracks = starts.mapIndexed { i, (p, start) ->
                val next = starts.getOrNull(i + 1)?.second
                val end = if (next != null && next.first == start.first && next.second > start.second) next.second else null
                Track(p.number, start.first, p.title, p.performer, start.second, end)
            }
            return CueSheet(albumTitle, albumPerformer, tracks)
        }

        private fun unquote(s: String): String {
            if (!s.startsWith("\"")) return s
            val end = s.lastIndexOf('"')
            return if (end > 0) s.substring(1, end) else s.substring(1)
        }

        /** FILE "name" TYPE, or FILE name TYPE when unquoted. */
        private fun fileValue(rest: String): String? {
            val v = if (rest.startsWith("\"")) {
                unquote(rest)
            } else {
                val lastSp = rest.indexOfLast { it.isWhitespace() }
                if (lastSp > 0 && FILE_TYPE.matches(rest.substring(lastSp + 1))) rest.substring(0, lastSp).trim() else rest
            }
            return v.replace('\\', '/').removePrefix("./").trim().ifEmpty { null }
        }

        /** MM:SS:FF (75 frames per second) to milliseconds; null if malformed. */
        fun parseTime(s: String): Long? {
            val parts = s.trim().split(':')
            if (parts.size != 3) return null
            val m = parts[0].toLongOrNull() ?: return null
            val sec = parts[1].toLongOrNull() ?: return null
            val fr = parts[2].toLongOrNull() ?: return null
            if (m < 0 || sec !in 0..59 || fr !in 0..74) return null
            return (m * 60 + sec) * 1000 + fr * 1000 / 75
        }
    }
}
