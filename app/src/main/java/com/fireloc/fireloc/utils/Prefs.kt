package com.fireloc.fireloc.util

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object Prefs {
    private const val PREFS_FILENAME = "com.fireloc.fireloc.prefs"
    private const val KEY_DEVICE_ID = "device_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets the unique device ID. If it doesn't exist, generates and saves a new one.
     */
    fun getDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    /**
     * Clears the stored device ID (e.g., on uninstall/reinstall simulation or debug).
     * Use with caution.
     */
    fun clearDeviceId(context: Context) {
        getPrefs(context).edit().remove(KEY_DEVICE_ID).apply()
    }
}