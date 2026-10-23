package io.galva.sdk.impl.session

import android.content.Context

/** Small device-scoped SDK settings that must be readable synchronously (SharedPreferences). */
internal class SdkSettings(context: Context) : SessionTracker.LastSessionStartStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Persisted opt-out flag; survives restarts and logout. */
    var optedOut: Boolean
        get() = prefs.getBoolean(KEY_OPTED_OUT, false)
        set(value) = prefs.edit().putBoolean(KEY_OPTED_OUT, value).apply()

    /** Epoch millis of the last emitted `session_start`, or null before the first one. */
    override var lastSessionStartAt: Long?
        get() = prefs.getLong(KEY_LAST_SESSION_START, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_LAST_SESSION_START) else putLong(KEY_LAST_SESSION_START, value)
        }.apply()

    private companion object {
        const val FILE_NAME = "galva_settings"
        const val KEY_OPTED_OUT = "optedOut"
        const val KEY_LAST_SESSION_START = "lastSessionStartAt"
    }
}
