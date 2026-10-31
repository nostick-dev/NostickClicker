/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 */
package com.nostick.clicker

/** Kinds of controls. KEY is kept only so very old profiles still parse; it is ignored. */
enum class ControlEventType { SCREEN, KEY }

/**
 * What a button does when pressed.
 * CLICK/HOLD/TOGGLE/SPAM/HOLD_TOGGLE act on the control's target point (TOGGLE = auto spam,
 * HOLD_TOGGLE = hold lock); BACK/HOME/RECENTS are system actions.
 */
enum class ControlAction { CLICK, HOLD, TOGGLE, SPAM, BACK, HOME, RECENTS, HOLD_TOGGLE }

enum class ControlWidget { BUTTON, MOVE_JOYSTICK, CAMERA_JOYSTICK }

/**
 * x / y: top-left of the on-screen control, as a fraction of the screen.
 * width: control size in dp divided by 360.
 * targetX / targetY: centre of the point the control taps / drags, as a fraction
 * of the screen. Negative means "not set yet".
 */
data class ControlEvent(
    val id: Long,
    var label: String,
    var type: ControlEventType,
    var action: ControlAction,
    var widget: ControlWidget = ControlWidget.BUTTON,
    var keySequence: MutableList<String> = mutableListOf(),
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var width: Float = 0.16f,
    var height: Float = 0.12f,
    var sensitivity: Float = 1f,
    var intervalMs: Long = 120L,
    var enabled: Boolean = true,
    var targetX: Float = -1f,
    var targetY: Float = -1f
)

data class ControlProfile(
    var id: String,
    var name: String,
    var packageName: String,
    var screenEventsEnabled: Boolean = true,
    var keyEventsEnabled: Boolean = false,
    var controls: MutableList<ControlEvent> = mutableListOf()
)
