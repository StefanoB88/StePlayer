package com.steplayer.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small, non-sensitive UI preferences. Secrets live in the shared CredentialStore. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    private val _softwareDecoder = MutableStateFlow(sp.getBoolean(KEY_SW_DECODER, false))
    val softwareDecoder: StateFlow<Boolean> = _softwareDecoder.asStateFlow()

    fun setSoftwareDecoder(value: Boolean) {
        sp.edit().putBoolean(KEY_SW_DECODER, value).apply()
        _softwareDecoder.value = value
    }

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLAIMER, false)
        set(value) { sp.edit().putBoolean(KEY_DISCLAIMER, value).apply() }

    var aspectMode: Int
        get() = sp.getInt(KEY_ASPECT, 0)
        set(value) { sp.edit().putInt(KEY_ASPECT, value).apply() }

    /** Android TV "Adatta frequenza schermo": 0 = off, 1 = only if seamless (default), 2 = always. */
    var frameRateMode: Int
        get() = sp.getInt(KEY_FRAME_RATE, 1)
        set(value) { sp.edit().putInt(KEY_FRAME_RATE, value).apply() }

    private companion object {
        const val KEY_FRAME_RATE = "frame_rate_mode"
        const val KEY_SW_DECODER = "software_decoder"
        const val KEY_DISCLAIMER = "disclaimer_accepted"
        const val KEY_ASPECT = "aspect_mode"
    }
}
