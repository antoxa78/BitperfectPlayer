package com.example.bitperfectplayer

import java.io.File

/**
 * Path rules shared by the two internal-storage browsers
 * ([MainFragment.browseFileStorage] and [NowPlayingActivity.browseFileStorage]).
 *
 * Kept free of Android APIs so it can be unit-tested on the JVM.
 */
object StorageNavigation {

    const val INTERNAL_STORAGE_LABEL = "Internal Storage"

    private val genericDriveLabels = setOf(
        "usb drive", "usb storage", "external drive", "portable storage",
        "sd card", "removable storage", "drive"
    )
    private val volumeIdPattern = Regex("""^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$""")

    /**
     * Friendly browser title for a removable volume. StorageVolume.getDescription()
     * often returns a generic "USB drive", while the mount point is just the volume
     * ID ("1234-ABCD"), so combine the two instead of showing the raw ID. A label
     * the user set on the volume is kept as-is.
     */
    fun driveDisplayName(label: String?, path: String): String {
        val fallback = File(path).name.ifEmpty { "External Drive" }
        val provided = label?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            provided == null -> if (volumeIdPattern.matches(fallback)) "USB Drive ($fallback)" else fallback
            provided.contains(fallback, ignoreCase = true) -> provided
            provided.lowercase() in genericDriveLabels -> "$provided ($fallback)"
            else -> provided
        }
    }

    /**
     * Title for a folder. The API path of internal storage is only "0"
     * (`/storage/emulated/0`), which is not something to show to the user.
     */
    fun displayName(path: String, internalRoot: String, fallback: String = "Storage"): String {
        val dir = File(path)
        return if (dir.absolutePath == File(internalRoot).absolutePath) {
            INTERNAL_STORAGE_LABEL
        } else {
            dir.name.ifEmpty { fallback }
        }
    }

    /**
     * The folder Back should open, or null when [currentPath] is the browser's
     * root (where Back closes the browser instead of stepping further out).
     *
     * Comparing against the root explicitly means an external drive mounted
     * under `/mnt/...` stops at the drive instead of climbing into the virtual
     * mount directories.
     */
    fun parentWithinRoot(currentPath: String, rootPath: String): String? {
        val current = File(currentPath).absolutePath
        val root = File(rootPath).absolutePath
        if (current == root) return null
        val parent = File(current).parentFile ?: return null
        if (parent.absolutePath == "/") return null
        return parent.absolutePath
    }
}
