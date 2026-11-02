/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 */
package com.nostick.clicker

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight wrapper around SharedPreferences.
 * Stores only user preferences (click speed, stop-after limit, disclosure
 * acknowledgement, active profile id). No personal data is ever written here.
 */
object Prefs {

    private const val FILE = "nostick_prefs"

    private const val KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted"
    private const val KEY_CLICK_INTERVAL_MS = "click_interval_ms"
    private const val KEY_MAX_CLICKS = "max_clicks"
    private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    private const val KEY_SHIZUKU = "shizuku_enabled"

    const val MIN_INTERVAL_MS = 40L
    const val MAX_INTERVAL_MS = 2_000L
    const val DEFAULT_INTERVAL_MS = 100L

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isDisclosureAccepted(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_DISCLOSURE_ACCEPTED, false)

    fun setDisclosureAccepted(ctx: Context) =
        sp(ctx).edit().putBoolean(KEY_DISCLOSURE_ACCEPTED, true).apply()

    fun getClickIntervalMs(ctx: Context): Long =
        sp(ctx).getLong(KEY_CLICK_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    fun setClickIntervalMs(ctx: Context, ms: Long) =
        sp(ctx).edit()
            .putLong(KEY_CLICK_INTERVAL_MS, ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS))
            .apply()

    /** 0 means "never stop". */
    fun getMaxClicks(ctx: Context): Int =
        sp(ctx).getInt(KEY_MAX_CLICKS, 0).coerceAtLeast(0)

    fun setMaxClicks(ctx: Context, count: Int) =
        sp(ctx).edit().putInt(KEY_MAX_CLICKS, count.coerceAtLeast(0)).apply()

    fun getActiveProfileId(ctx: Context): String =
        sp(ctx).getString(KEY_ACTIVE_PROFILE, "") ?: ""

    fun setActiveProfileId(ctx: Context, id: String) =
        sp(ctx).edit().putString(KEY_ACTIVE_PROFILE, id).apply()

    /** Optional Shizuku mode (real multi-touch). Off by default. */
    fun isShizukuEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_SHIZUKU, false)

    fun setShizukuEnabled(ctx: Context, enabled: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHIZUKU, enabled).apply()
}
