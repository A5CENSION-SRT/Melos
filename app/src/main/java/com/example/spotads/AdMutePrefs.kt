package com.example.spotads

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persisted settings for the ad-muting service: a master on/off switch and a
 * per-day count of ads muted, so the feature can be tuned/disabled without
 * uninstalling and gives a visible signal that it's actually doing something.
 */
object AdMutePrefs {
    private const val PREFS_NAME = "spotads_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COUNT = "muted_count"
    private const val KEY_COUNT_DATE = "muted_count_date"
    private const val KEY_LIFETIME_COUNT = "lifetime_muted_count"

    fun isEnabledKey(key: String?): Boolean = key == KEY_ENABLED

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun todayString(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Ads muted so far today (resets automatically when the date rolls over). */
    fun todayMutedCount(context: Context): Int {
        val p = prefs(context)
        return if (p.getString(KEY_COUNT_DATE, null) == todayString()) p.getInt(KEY_COUNT, 0) else 0
    }

    /** Total ads muted since install. */
    fun lifetimeMutedCount(context: Context): Int =
        prefs(context).getInt(KEY_LIFETIME_COUNT, 0)

    /** Records one more muted ad and returns the new today-count. */
    fun incrementMutedCount(context: Context): Int {
        val p = prefs(context)
        val today = todayString()
        val current = if (p.getString(KEY_COUNT_DATE, null) == today) p.getInt(KEY_COUNT, 0) else 0
        val updated = current + 1
        val lifetime = p.getInt(KEY_LIFETIME_COUNT, 0) + 1
        p.edit()
            .putInt(KEY_COUNT, updated)
            .putString(KEY_COUNT_DATE, today)
            .putInt(KEY_LIFETIME_COUNT, lifetime)
            .apply()
        return updated
    }

    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
