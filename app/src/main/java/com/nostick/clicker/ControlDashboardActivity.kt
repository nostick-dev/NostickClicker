/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 */
package com.nostick.clicker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

/** Profiles screen: pick the active layout, create new ones, open the editor. */
class ControlDashboardActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(root)
        Ui.setupScreen(this, root, 20)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun activeId(): String =
        ClickerService.instance?.activeProfileId()?.takeIf { it.isNotEmpty() }
            ?: Prefs.getActiveProfileId(this)

    private fun render() {
        content.removeAllViews()
        content.addView(Ui.header(this, getString(R.string.dashboard_title)) { finish() })

        // ── Service card ────────────────────────────────────────────────────
        val service = ClickerService.instance
        val card = Ui.card(this)
        val connected = service != null
        val showing = service?.isShowing() == true
        card.addView(
            Ui.text(
                this,
                getString(
                    when {
                        !connected -> R.string.service_not_connected
                        showing -> R.string.service_active
                        else -> R.string.service_hidden
                    }
                ),
                16f,
                when {
                    !connected -> Ui.DANGER
                    showing -> Ui.OK
                    else -> Ui.MUTED
                },
                true
            )
        )
        card.addView(
            Ui.text(this, getString(R.string.dashboard_help), 13f, Ui.MUTED),
            Ui.lp(this, top = 6)
        )
        val row = LinearLayout(this)
        row.addView(
            Ui.button(this, getString(R.string.btn_show_controls), Ui.PRIMARY) {
                val s = ClickerService.instance
                if (s == null) {
                    Toast.makeText(this, R.string.toast_service_not_connected, Toast.LENGTH_LONG).show()
                } else {
                    s.showAll()
                    moveTaskToBack(true)
                }
            },
            Ui.lp(this, w = 0, h = Ui.dp(this, 48), weight = 1f, end = 8)
        )
        row.addView(
            Ui.button(this, getString(R.string.btn_hide_controls), Ui.SECONDARY) {
                ClickerService.instance?.hideAll()
                render()
            },
            Ui.lp(this, w = 0, h = Ui.dp(this, 48), weight = 1f)
        )
        card.addView(row, Ui.lp(this, top = 14))
        content.addView(card, Ui.lp(this, top = 18))

        // ── Profiles ────────────────────────────────────────────────────────
        content.addView(
            Ui.text(this, getString(R.string.profiles_title), 13f, Ui.MUTED, true),
            Ui.lp(this, top = 24, bottom = 8)
        )
        val profiles = ProfileStore.load(this)
        val active = activeId()
        profiles.forEach { p -> content.addView(profileRow(p, p.id == active), Ui.lp(this, bottom = 10)) }
        if (profiles.isEmpty()) {
            content.addView(
                Ui.text(this, getString(R.string.no_profiles), 14f, Ui.MUTED),
                Ui.lp(this, bottom = 10)
            )
        }
        content.addView(
            Ui.button(this, getString(R.string.btn_new_profile), Ui.PRIMARY) { createProfile(profiles.size + 1) },
            Ui.lp(this, h = Ui.dp(this, 52), top = 6)
        )
    }

    private fun profileRow(p: ControlProfile, isActive: Boolean): View {
        val card = Ui.card(this, 16)
        if (isActive) card.background = Ui.rounded(this, Ui.SURFACE, 22, Ui.ACCENT, 2)
        card.setOnClickListener { openEditor(p.id) }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(Ui.text(this, p.name, 17f, Ui.TEXT, true))
        names.addView(
            Ui.text(this, getString(R.string.profile_controls_fmt, p.controls.size), 13f, Ui.MUTED),
            Ui.lp(this, top = 2)
        )
        top.addView(names, Ui.lp(this, w = 0, weight = 1f))
        if (isActive) {
            top.addView(Ui.chip(this, getString(R.string.profile_active), true))
        } else {
            top.addView(Ui.chip(this, getString(R.string.profile_use)) {
                Prefs.setActiveProfileId(this, p.id)
                ClickerService.instance?.activateProfile(p.id)
                render()
            })
        }
        card.addView(top)
        return card
    }

    private fun createProfile(number: Int) {
        val p = ControlProfile(UUID.randomUUID().toString(), getString(R.string.default_profile_name, number), "")
        ProfileStore.upsert(this, p)
        openEditor(p.id)
    }

    private fun openEditor(id: String) {
        startActivity(Intent(this, ControlEditorActivity::class.java).putExtra(ControlEditorActivity.EXTRA_ID, id))
    }
}
