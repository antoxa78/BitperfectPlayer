package com.example.bitperfectplayer

import android.content.Context

/** Waveform type values and persistence shared by the settings, Now Playing and screensaver. */
object WaveformSettings {

    private const val PREFS = "AppSettings"
    private const val KEY_TYPE = "waveform_type"

    /** Value stored in the type list; no waveform view is shown. */
    const val TYPE_DISABLED = 5

    fun type(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TYPE, 0)

    fun isDisabled(context: Context): Boolean = type(context) == TYPE_DISABLED
}
