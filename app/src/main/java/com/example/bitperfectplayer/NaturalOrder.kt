package com.example.bitperfectplayer

import androidx.media3.common.MediaItem

/**
 * Natural ("human") ordering for file names: runs of ASCII digits compare by
 * numeric value, everything else case-insensitively. So "2 Foo" < "10 Bar",
 * and "01 There is More to this World.flac" sorts before "02 ...".
 *
 * Used when a whole folder is added to the queue: the playlist follows the
 * folder's file names, not the song titles from the tags (which normally carry
 * no track number).
 */
object NaturalOrder {

    val COMPARATOR: Comparator<String> = Comparator { a, b -> compare(a, b) }

    fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                val si = i
                while (i < a.length && a[i] in '0'..'9') i++
                val sj = j
                while (j < b.length && b[j] in '0'..'9') j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    /** Folder children in play order: files first, then sub-folders, each naturally by name. */
    fun <T> sortChildren(children: Iterable<T>, isDir: (T) -> Boolean, name: (T) -> String): List<T> =
        children.sortedWith(compareBy<T> { isDir(it) }.thenComparator { x, y -> compare(name(x), name(y)) })

    /** Playlist / CUE / SACD-ISO files: each expands to its own ordered track list. */
    fun isListFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".m3u") || n.endsWith(".m3u8") || n.endsWith(".pls") || n.endsWith(".cue") || n.endsWith(".iso")
    }

    /**
     * Re-orders the tracks one folder just added (`items[from..]`) by their tags —
     * disc number, then track number — when *every* one of them has a track-number
     * tag. If any is untagged the folder keeps its file-name order, since mixing the
     * two orders would put the untagged tracks in an arbitrary place.
     *
     * The sort is stable, so tracks sharing a disc/track number (e.g. a mis-tagged
     * album where every file says "1") stay in file-name order among themselves.
     * Callers skip folders containing playlist/CUE/ISO files (see [isListFile]).
     */
    fun applyTagOrder(items: MutableList<MediaItem>, from: Int) {
        if (from < 0 || items.size - from < 2) return
        val folder = items.subList(from, items.size)
        if (folder.any { it.mediaMetadata.trackNumber == null }) return
        val ordered = folder.sortedWith(
            compareBy<MediaItem>({ it.mediaMetadata.discNumber ?: 1 }, { it.mediaMetadata.trackNumber ?: 0 })
        )
        for (i in ordered.indices) folder[i] = ordered[i]
    }
}
