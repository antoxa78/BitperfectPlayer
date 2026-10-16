package com.example.bitperfectplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The debug log is written to the box's storage and served over the LAN, so a
 * credential that reaches it has left the app. These cover the scrubbers that
 * stop MPD passwords and SMB `user:pass@` from getting there.
 */
class DebugLogScrubTest {

    @Test fun smbCredentialsAreMasked() {
        assertEquals(
            "SmbDataSource: open smb://guest:***@nas/music/Track.flac",
            DebugLog.scrub("SmbDataSource: open smb://guest:hunter2@nas/music/Track.flac")
        )
    }

    @Test fun smbCredentialsAreMaskedWithoutAUser() {
        assertEquals("open smb://guest:***@nas/share", DebugLog.scrub("open smb://guest:pw@nas/share"))
    }

    @Test fun mpdPasswordCommandIsMasked() {
        assertEquals(
            "D MpdServer: MPD <- password ***",
            DebugLog.scrub("D MpdServer: MPD <- password s3cr3t")
        )
    }

    @Test fun passwordAssignmentsAreMasked() {
        assertEquals("prefs password=***", DebugLog.scrub("prefs password=abc123"))
        assertEquals("prefs pwd: ***", DebugLog.scrub("prefs pwd: abc123"))
        assertEquals("mpd_password=***", DebugLog.scrub("mpd_password=abc123"))
    }

    /**
     * Regression: a bare "password <word>" rule also matched this real MpdServer
     * line, masking the harmless word "set" and eating the closing bracket.
     */
    @Test fun passwordSetIsNotMistakenForASecret() {
        val line = "I MpdServer: MPD server listening on 6600 — configure MALP host=<shield-ip> port=6600 (password set)"
        assertEquals(line, DebugLog.scrub(line))
    }

    @Test fun ordinaryLinesAreUntouched() {
        val line = "10-16 12:34:56.789  1234  1234 I UsbAudioSink: USB DAC live: 'Gustard X16'"
        assertEquals(line, DebugLog.scrub(line))
    }

    @Test fun httpUrlsWithoutCredentialsAreUntouched() {
        val line = "GET http://192.168.1.10:6790/log?lines=200"
        assertEquals(line, DebugLog.scrub(line))
        assertFalse(DebugLog.scrub(line).contains("***"))
    }
}
