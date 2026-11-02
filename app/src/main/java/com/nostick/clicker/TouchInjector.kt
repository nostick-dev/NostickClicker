/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 */
package com.nostick.clicker

/**
 * Anything that can put fingers on the screen. Two implementations exist:
 *  • the Accessibility engine inside ClickerService (works everywhere, but Android
 *    may interrupt it while the user is touching the screen), and
 *  • ShizukuInjector (optional): real multi-touch events, smooth and uninterrupted.
 */
interface TouchInjector {
    /**
     * Puts a finger down at (x, y) and keeps it there until [up].
     * [restoreFromStart]: if the system cancels the touch, press again at (x, y) and slide
     * back to the current position (needed by joysticks, whose base is where you press).
     */
    fun down(key: Any, x: Float, y: Float, restoreFromStart: Boolean = false)

    /** Slides a held finger to a new position. [timeMs] = uptime of the real event, if known. */
    fun move(key: Any, x: Float, y: Float, timeMs: Long = 0L)

    /** Lifts a held finger. */
    fun up(key: Any)

    fun isDown(key: Any): Boolean

    /** Quick tap at (x, y). */
    fun tap(x: Float, y: Float)

    fun cancelAll()
}
