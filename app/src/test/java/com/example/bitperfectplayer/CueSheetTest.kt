package com.example.bitperfectplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class CueSheetTest {

    private fun ms(m: Long, s: Long, f: Long) = (m * 60 + s) * 1000 + f * 1000 / 75

    @Test fun singleFileAlbum() {
        val cue = CueSheet.parse(
            """
            REM GENRE Rock
            PERFORMER "The Band"
            TITLE "The Album"
            FILE "The Band - The Album.flac" WAVE
              TRACK 01 AUDIO
                TITLE "One"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Two"
                PERFORMER "Guest"
                INDEX 00 03:58:50
                INDEX 01 04:00:10
              TRACK 03 AUDIO
                TITLE "Three"
                INDEX 01 08:12:74
            """.trimIndent()
        )
        assertEquals("The Album", cue.title)
        assertEquals("The Band", cue.performer)
        assertEquals(3, cue.tracks.size)
        val (t1, t2, t3) = cue.tracks
        assertEquals("The Band - The Album.flac", t1.file)
        assertEquals(0L, t1.startMs)
        assertEquals(ms(4, 0, 10), t1.endMs)          // ends at next INDEX 01 (pregap stays with track 1)
        assertEquals("Guest", t2.performer)
        assertNull(t1.performer)
        assertEquals(ms(4, 0, 10), t2.startMs)
        assertEquals(ms(8, 12, 74), t2.endMs)
        assertNull(t3.endMs)                          // last track plays to end of file
    }

    @Test fun oneFilePerTrack() {
        val cue = CueSheet.parse(
            """
            TITLE "Split"
            FILE "01 - A.flac" WAVE
              TRACK 01 AUDIO
                TITLE "A"
                INDEX 01 00:00:00
            FILE "02 - B.flac" WAVE
              TRACK 02 AUDIO
                TITLE "B"
                INDEX 01 00:00:00
            """.trimIndent()
        )
        assertEquals(listOf("01 - A.flac", "02 - B.flac"), cue.tracks.map { it.file })
        assertTrue(cue.tracks.all { it.startMs == 0L && it.endMs == null })
    }

    @Test fun eacGapsAppendedLayout() {
        // Track 2's pregap (INDEX 00) lives at the end of file 1; INDEX 01 is in file 2.
        val cue = CueSheet.parse(
            """
            FILE "01.wav" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                INDEX 00 03:10:00
            FILE "02.wav" WAVE
                INDEX 01 00:00:00
              TRACK 03 AUDIO
                INDEX 01 02:00:00
            """.trimIndent()
        )
        val (t1, t2, t3) = cue.tracks
        assertEquals("01.wav", t1.file); assertNull(t1.endMs)     // plays to end of 01.wav, gap included
        assertEquals("02.wav", t2.file); assertEquals(0L, t2.startMs); assertEquals(ms(2, 0, 0), t2.endMs)
        assertEquals("02.wav", t3.file)
    }

    @Test fun multiDiscSheet() {
        val cue = CueSheet.parse(
            """
            FILE "CD1\disc1.ape" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                INDEX 01 05:00:00
            FILE "CD2\disc2.ape" WAVE
              TRACK 03 AUDIO
                INDEX 01 00:00:00
            """.trimIndent()
        )
        assertEquals(listOf("CD1/disc1.ape", "CD1/disc1.ape", "CD2/disc2.ape"), cue.tracks.map { it.file })
        assertNull(cue.tracks[1].endMs)   // last track of disc 1 must not end at disc 2's 00:00:00
    }

    @Test fun looseFormatting() {
        val cue = CueSheet.parse(
            "﻿file my album.flac WAVE\r\n" +
            "track  01   audio\r\n" +
            "\ttitle Untitled one\r\n" +
            "    INDEX   01   00:00:00\r\n" +
            "TRACK xx AUDIO\r\n" +
            "    INDEX 01 0x:00:00\r\n" +              // malformed: skipped, track has no index
            "TRACK 03 AUDIO\r\n" +
            "    INDEX 01 01:00:00\r\n"
        )
        assertEquals("my album.flac", cue.tracks[0].file)
        assertEquals("Untitled one", cue.tracks[0].title)
        assertEquals(listOf(1, 3), cue.tracks.map { it.number })
        assertEquals(ms(1, 0, 0), cue.tracks[0].endMs)
    }

    @Test fun dataTrackIsDropped() {
        val cue = CueSheet.parse(
            """
            FILE "a.bin" BINARY
              TRACK 01 MODE1/2352
                TITLE "Data"
                INDEX 01 00:00:00
            FILE "a.flac" WAVE
              TRACK 02 AUDIO
                TITLE "Song"
                INDEX 01 00:00:00
            """.trimIndent()
        )
        assertEquals(1, cue.tracks.size)
        assertEquals("Song", cue.tracks[0].title)
        assertNull(cue.title)
    }

    @Test fun missingIndex01FallsBackToFirstIndex() {
        val cue = CueSheet.parse("FILE \"a.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 00 00:01:00\n")
        assertEquals(1000L, cue.tracks.single().startMs)
    }

    @Test fun parseTime() {
        assertEquals(ms(102, 3, 74), CueSheet.parseTime("102:03:74"))
        assertNull(CueSheet.parseTime("01:60:00"))
        assertNull(CueSheet.parseTime("01:00:75"))
        assertNull(CueSheet.parseTime("1:00"))
    }

    // ── Encodings ──────────────────────────────────────────────────────────

    private val ru = "TITLE \"Кино - Группа крови\"\nFILE \"Кино.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"Звезда по имени Солнце\"\nINDEX 01 00:00:00\n"
    private val fr = "TITLE \"Café Società\"\nPERFORMER \"Mötley Crüe\"\nFILE \"élan.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"Déjà vu\"\nINDEX 01 00:00:00\n"

    @Test fun utf8WithAndWithoutBom() {
        assertEquals(ru, CueSheet.decode(ru.toByteArray(Charsets.UTF_8)))
        assertEquals(ru, CueSheet.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ru.toByteArray(Charsets.UTF_8)))
    }

    @Test fun utf16WithAndWithoutBom() {
        assertEquals(ru, CueSheet.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + ru.toByteArray(Charsets.UTF_16LE)))
        assertEquals(ru, CueSheet.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + ru.toByteArray(Charsets.UTF_16BE)))
        assertEquals(ru, CueSheet.decode(ru.toByteArray(Charsets.UTF_16LE)))
        assertEquals(ru, CueSheet.decode(ru.toByteArray(Charsets.UTF_16BE)))
    }

    @Test fun legacyCodePagesWithoutDetector() {
        assertEquals(ru, CueSheet.decode(ru.toByteArray(Charset.forName("windows-1251"))))
        assertEquals(fr, CueSheet.decode(fr.toByteArray(Charset.forName("windows-1252"))))
    }

    @Test fun legacyDetectorIsConsultedFirst() {
        val he = "TITLE \"שלום\"\n"
        val bytes = he.toByteArray(Charset.forName("windows-1255"))
        assertEquals(he, CueSheet.decode(bytes) { Charset.forName("windows-1255") })
    }

    @Test fun decodedLegacySheetParses() {
        val cue = CueSheet.parse(CueSheet.decode(ru.toByteArray(Charset.forName("windows-1251"))))
        assertEquals("Кино - Группа крови", cue.title)
        assertEquals("Кино.flac", cue.tracks.single().file)
        assertEquals("Звезда по имени Солнце", cue.tracks.single().title)
    }
}
