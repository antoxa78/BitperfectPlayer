package com.example.bitperfectplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageNavigationTest {

    @Test
    fun internalStorageRootIsLabelled() {
        assertEquals(
            "Internal Storage",
            StorageNavigation.displayName("/storage/emulated/0", "/storage/emulated/0")
        )
    }

    @Test
    fun ordinaryFolderUsesItsName() {
        assertEquals(
            "Download",
            StorageNavigation.displayName("/storage/emulated/0/Download", "/storage/emulated/0")
        )
    }

    @Test
    fun emptyNameFallsBack() {
        assertEquals("Storage", StorageNavigation.displayName("/", "/storage/emulated/0"))
    }

    @Test
    fun backAtInternalStorageRootCloses() {
        assertNull(
            StorageNavigation.parentWithinRoot("/storage/emulated/0", "/storage/emulated/0")
        )
    }

    @Test
    fun backGoesUpOneFolder() {
        assertEquals(
            "/storage/emulated/0/Download",
            StorageNavigation.parentWithinRoot(
                "/storage/emulated/0/Download/PureRadio",
                "/storage/emulated/0/Download"
            )
        )
    }

    @Test
    fun backAtUsbMountRootClosesInsteadOfClimbingToMnt() {
        assertNull(
            StorageNavigation.parentWithinRoot("/mnt/usb/1234-ABCD", "/mnt/usb/1234-ABCD")
        )
    }

    @Test
    fun backInsideUsbMountStopsAtMountRoot() {
        assertEquals(
            "/mnt/usb/1234-ABCD",
            StorageNavigation.parentWithinRoot(
                "/mnt/usb/1234-ABCD/Music",
                "/mnt/usb/1234-ABCD"
            )
        )
    }

    @Test
    fun topLevelParentIsNotExposed() {
        assertNull(StorageNavigation.parentWithinRoot("/sdcard", "/sdcard"))
    }

    @Test
    fun genericUsbLabelGetsTheVolumeId() {
        assertEquals(
            "USB drive (1234-ABCD)",
            StorageNavigation.driveDisplayName("USB drive", "/storage/1234-ABCD")
        )
    }

    @Test
    fun customVolumeLabelIsKept() {
        assertEquals(
            "My Music",
            StorageNavigation.driveDisplayName("My Music", "/storage/1234-ABCD")
        )
    }

    @Test
    fun missingLabelOnVolumeIdIsStillReadable() {
        assertEquals(
            "USB Drive (1234-ABCD)",
            StorageNavigation.driveDisplayName(null, "/storage/1234-ABCD")
        )
    }

    @Test
    fun mountNameIsUsedWhenItIsNotAVolumeId() {
        assertEquals(
            "Music",
            StorageNavigation.driveDisplayName(null, "/mnt/usb/Music")
        )
    }
}
