package com.example.bitperfectplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPlaylistParserTest {

    @Test fun playlistUrlDetection() {
        assertTrue(StreamPlaylistParser.isPlaylistUrl("http://158.69.114.190:8024/listen.pls?sid=1"))
        assertTrue(StreamPlaylistParser.isPlaylistUrl("https://host.example/radio.M3U"))
        assertFalse(StreamPlaylistParser.isPlaylistUrl("https://host.example/live.m3u8"))
        assertFalse(StreamPlaylistParser.isPlaylistUrl("https://host.example/stream.mp3"))
        assertFalse(StreamPlaylistParser.isPlaylistUrl("https://host.example/stream"))
    }

    @Test fun plsFile1Wins() {
        val content = """
            [playlist]
            NumberOfEntries=2
            File1=http://158.69.114.190:8024/stream
            Title1=The Big 80s Station
            File2=http://158.69.114.190:8024/other
            Version=2
        """.trimIndent()
        assertEquals(
            "http://158.69.114.190:8024/stream",
            StreamPlaylistParser.parse(content, "http://158.69.114.190:8024/listen.pls?sid=1")
        )
    }

    @Test fun plsKeysAreCaseInsensitiveAndBomIsIgnored() {
        val content = "\uFEFF[Playlist]\r\nFILE1=https://host.example/stream\r\nNumberofentries=1\r\n"
        assertEquals(
            "https://host.example/stream",
            StreamPlaylistParser.parse(content, "https://host.example/listen.pls")
        )
    }

    @Test fun m3uUsesFirstNonCommentLine() {
        val content = """
            #EXTM3U
            #EXTINF:-1,My Station
            #EXTVLCOPT:http-user-agent=Mozilla
            http://host.example:8000/live
        """.trimIndent()
        assertEquals(
            "http://host.example:8000/live",
            StreamPlaylistParser.parse(content, "http://host.example/listen.m3u")
        )
    }

    @Test fun relativeEntryResolvesAgainstPlaylistUrl() {
        assertEquals(
            "http://host.example/radio/stream",
            StreamPlaylistParser.parse("stream", "http://host.example/radio/listen.m3u")
        )
    }

    @Test fun hlsManifestIsNotExpanded() {
        val content = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
            low/index.m3u8
        """.trimIndent()
        assertNull(StreamPlaylistParser.parse(content, "http://host.example/master.m3u"))
    }

    @Test fun pureRadioHeaderIsNotMistakenForHls() {
        val content = """
            #EXTM3U
            #EXT-X-PURE-RADIO-HOME:genres=80s;countries=Germany
            #EXTINF:-1 tvg-id="x",Solar Radio High
            #EXT-X-PURE-RADIO-DATA:uuid=x;name=Solar+Radio+High
            https://listen-msmn.sharp-stream.com/solarhigh.mp3
        """.trimIndent()
        assertEquals(
            "https://listen-msmn.sharp-stream.com/solarhigh.mp3",
            StreamPlaylistParser.parse(content, "https://host.example/listen.m3u")
        )
    }

    @Test fun nonStreamAndGarbageAreRejected() {
        assertNull(StreamPlaylistParser.parse("", "http://host.example/listen.pls"))
        assertNull(StreamPlaylistParser.parse("not a playlist", "http://host.example/listen.pls"))
        assertNull(StreamPlaylistParser.parse("File1=icy://host.example/stream", "http://host.example/listen.pls"))
    }

    @Test fun relativePlsFileResolvesAgainstPlaylistUrl() {
        assertEquals(
            "http://host.example/radio/stream",
            StreamPlaylistParser.parse("[playlist]\nFile1=stream\n", "http://host.example/radio/listen.pls")
        )
    }
}
