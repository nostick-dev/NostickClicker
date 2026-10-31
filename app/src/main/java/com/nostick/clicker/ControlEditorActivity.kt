/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 */
package com.nostick.clicker

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Edits one profile: name, visibility, and the list of its controls. */
class ControlEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "profile_id"
    }

    private var profile: ControlProfile? = null
    private var deleted = false
    private lateinit var nameInput: EditText
    private lateinit var controlsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID) ?: ""
        profile = ProfileStore.find(this, id)
        if (profile == null) {
            finish()
            return
        }
        render()
    }

    override fun onRestart() {
        super.onRestart()
        // The on-screen editor may have changed this profile while the app was in the background.
        val id = profile?.id ?: return
        profile = ProfileStore.find(this, id)
        if (profile == null) finish() else render()
    }

    override fun onPause() {
        super.onPause()
        if (!deleted && !isFinishing) persist()
    }

    override fun onStop() {
        super.onStop()
        if (!deleted) persist()
    }

    private fun persist() {
        val p = profile ?: return
        if (!::nameInput.isInitialized) return
        p.name = nameInput.text.toString().trim().ifBlank { p.name }
        ProfileStore.upsert(this, p)
        ClickerService.instance?.reloadActiveProfile()
    }

    private fun render() {
        val p = profile ?: return
        val root = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(root)
        Ui.setupScreen(this, root, 20)

        content.addView(Ui.header(this, getString(R.string.editor_title)) { finish() })

        // ── Basics ──────────────────────────────────────────────────────────
        val basics = Ui.card(this)
        basics.addView(Ui.text(this, getString(R.string.profile_name), 13f, Ui.MUTED))
        nameInput = Ui.input(this, getString(R.string.profile_name), p.name)
        basics.addView(nameInput, Ui.lp(this, top = 6))
        val show = SwitchCompat(this).apply {
            text = getString(R.string.show_controls_switch)
            setTextColor(Ui.TEXT)
            isChecked = p.screenEventsEnabled
            setOnCheckedChangeListener { _, checked -> p.screenEventsEnabled = checked }
        }
        basics.addView(show, Ui.lp(this, top = 14))
        content.addView(basics, Ui.lp(this, top = 18))

        // ── Edit on screen ──────────────────────────────────────────────────
        content.addView(
            Ui.button(this, getString(R.string.btn_edit_layout), Ui.PRIMARY) { editOnScreen() },
            Ui.lp(this, h = Ui.dp(this, 54), top = 16)
        )
        content.addView(
            Ui.text(this, getString(R.string.editor_help), 12f, Ui.MUTED),
            Ui.lp(this, top = 8)
        )

        // ── Controls list ───────────────────────────────────────────────────
        content.addView(
            Ui.text(this, getString(R.string.controls_title), 13f, Ui.MUTED, true),
            Ui.lp(this, top = 24, bottom = 8)
        )
        controlsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(controlsBox)
        renderControls()

        // ── Footer ──────────────────────────────────────────────────────────
        content.addView(
            Ui.button(this, getString(R.string.btn_save_changes), Ui.SECONDARY) {
                persist()
                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                finish()
            },
            Ui.lp(this, h = Ui.dp(this, 52), top = 20)
        )
        content.addView(
            Ui.button(this, getString(R.string.btn_delete_profile), Ui.DANGER_KIND) { confirmDelete() },
            Ui.lp(this, h = Ui.dp(this, 48), top = 10)
        )
    }

    private fun describe(ev: ControlEvent): String = when (ev.widget) {
        ControlWidget.MOVE_JOYSTICK -> getString(R.string.widget_move)
        ControlWidget.CAMERA_JOYSTICK -> getString(R.string.widget_look)
        ControlWidget.BUTTON -> getString(
            when (ev.action) {
                ControlAction.CLICK -> R.string.action_click
                ControlAction.HOLD -> R.string.action_hold
                ControlAction.TOGGLE -> R.string.action_toggle
                ControlAction.SPAM -> R.string.action_spam
                ControlAction.BACK -> R.string.action_back
                ControlAction.HOME -> R.string.action_home
                ControlAction.RECENTS -> R.string.action_recents
                ControlAction.HOLD_TOGGLE -> R.string.action_hold_toggle
            }
        )
    }

    private fun renderControls() {
        val p = profile ?: return
        controlsBox.removeAllViews()
        if (p.controls.isEmpty()) {
            controlsBox.addView(Ui.text(this, getString(R.string.no_controls), 14f, Ui.MUTED))
            return
        }
        p.controls.toList().forEach { ev ->
            val row = Ui.card(this, 14)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(Ui.text(this, ev.label, 16f, Ui.TEXT, true))
            names.addView(Ui.text(this, describe(ev), 12f, Ui.MUTED), Ui.lp(this, top = 2))
            line.addView(names, Ui.lp(this, w = 0, weight = 1f))
            val toggle = SwitchCompat(this).apply {
                isChecked = ev.enabled
                setOnCheckedChangeListener { _, checked -> ev.enabled = checked }
            }
            line.addView(toggle)
            line.addView(Ui.iconButton(this, R.drawable.ic_trash, Ui.DANGER, 40) {
                p.controls.remove(ev)
                renderControls()
            })
            row.addView(line)
            controlsBox.addView(row, Ui.lp(this, bottom = 8))
        }
    }

    private fun editOnScreen() {
        val p = profile ?: return
        persist()
        val service = ClickerService.instance
        if (service == null) {
            Toast.makeText(this, R.string.toast_service_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setActiveProfileId(this, p.id)
        service.activateProfile(p.id)
        service.openEditor()
        moveTaskToBack(true)
    }

    private fun confirmDelete() {
        val p = profile ?: return
        val card = Ui.card(this)
        card.addView(Ui.text(this, getString(R.string.confirm_delete_title), 18f, Ui.TEXT, true))
        card.addView(
            Ui.text(this, getString(R.string.confirm_delete_body, p.name), 14f, Ui.MUTED),
            Ui.lp(this, top = 8)
        )
        val dialog = Ui.dialog(this, card)
        val row = LinearLayout(this)
        row.addView(
            Ui.button(this, getString(android.R.string.cancel), Ui.SECONDARY) { dialog.dismiss() },
            Ui.lp(this, w = 0, h = Ui.dp(this, 48), weight = 1f, end = 8)
        )
        row.addView(
            Ui.button(this, getString(R.string.btn_delete), Ui.DANGER_KIND) {
                dialog.dismiss()
                deleted = true
                ProfileStore.delete(this, p.id)
                if (Prefs.getActiveProfileId(this) == p.id) Prefs.setActiveProfileId(this, "")
                ClickerService.instance?.reloadActiveProfile()
                finish()
            },
            Ui.lp(this, w = 0, h = Ui.dp(this, 48), weight = 1f)
        )
        card.addView(row, Ui.lp(this, top = 18))
    }
}
