/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 *
 * MainActivity.kt
 * ───────────────
 * Entry point and setup screen:
 *  1. Prominent disclosure (first launch only, required by Google Play).
 *  2. Two permission cards: Accessibility service + "Display over other apps".
 *  3. "Start clicker" shows the floating menu and sends the app to the background.
 *  4. onResume() refreshes the status so cards update after returning from Settings.
 */
package com.nostick.clicker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var iconAccessibility: ImageView
    private lateinit var iconOverlay: ImageView
    private lateinit var tvSummary: TextView
    private lateinit var tvShizukuStatus: TextView
    private val shizukuListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshStatus() } }
    private lateinit var btnStart: Button
    private var disclosure: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ui.setupScreen(this, findViewById(R.id.mainRoot), 20)
        bindViews()
        if (!Prefs.isDisclosureAccepted(this)) showDisclosureDialog()
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
        disclosure?.dismiss()
        disclosure = null
        super.onDestroy()
    }

    private fun bindViews() {
        tvAccessStatus = findViewById(R.id.tvAccessibilityStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        iconAccessibility = findViewById(R.id.iconAccessibility)
        iconOverlay = findViewById(R.id.iconOverlay)
        tvSummary = findViewById(R.id.tvSummary)
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        findViewById<View>(R.id.cardShizuku).setOnClickListener { onShizukuClick() }
        btnStart = findViewById(R.id.btnLaunch)

        findViewById<View>(R.id.cardAccessibility).setOnClickListener { openAccessibilitySettings() }
        findViewById<View>(R.id.cardOverlay).setOnClickListener { openOverlaySettings() }
        btnStart.setOnClickListener { startClicker() }
        findViewById<View>(R.id.btnControlEditor).setOnClickListener {
            startActivity(Intent(this, ControlDashboardActivity::class.java))
        }
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.version_fmt, versionName())
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    // ── Disclosure ───────────────────────────────────────────────────────────

    private fun showDisclosureDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_disclosure, null)
        val dialog = Ui.dialog(this, view, cancelable = false)
        view.findViewById<Button>(R.id.btnAccept).setOnClickListener {
            Prefs.setDisclosureAccepted(this)
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnExit).setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }
        disclosure = dialog
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        if (ClickerService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, ClickerService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(this)

    private fun refreshStatus() {
        val accessOk = isAccessibilityEnabled()
        val overlayOk = isOverlayGranted()
        applyStatus(tvAccessStatus, iconAccessibility, accessOk)
        applyStatus(tvOverlayStatus, iconOverlay, overlayOk)

        refreshShizukuStatus()

        val ready = accessOk && overlayOk
        btnStart.isEnabled = ready
        btnStart.alpha = if (ready) 1f else 0.4f
        tvSummary.text = getString(if (ready) R.string.setup_complete else R.string.setup_incomplete)
        tvSummary.setTextColor(if (ready) Ui.OK else Ui.MUTED)
    }

    private fun refreshShizukuStatus() {
        val (text, color) = when {
            !ShizukuInput.isRunning() -> R.string.shizuku_status_off to Ui.MUTED
            !ShizukuInput.hasPermission() -> R.string.shizuku_status_allow to Ui.ACCENT
            Prefs.isShizukuEnabled(this) -> R.string.shizuku_status_on to Ui.OK
            else -> R.string.shizuku_status_disabled to Ui.MUTED
        }
        tvShizukuStatus.text = getString(text)
        tvShizukuStatus.setTextColor(color)
    }

    /** Optional: not running -> open Shizuku; not allowed -> ask; otherwise toggle. */
    private fun onShizukuClick() {
        if (!ShizukuInput.isRunning()) {
            val launch = packageManager.getLaunchIntentForPackage(ShizukuInput.PACKAGE)
            if (launch != null) startActivity(launch)
            else Toast.makeText(this, R.string.toast_shizuku_missing, Toast.LENGTH_LONG).show()
            return
        }
        if (!ShizukuInput.hasPermission()) {
            ShizukuInput.requestPermission()
            return
        }
        Prefs.setShizukuEnabled(this, !Prefs.isShizukuEnabled(this))
        ClickerService.instance?.refreshBackend()
        refreshStatus()
    }

    private fun applyStatus(label: TextView, icon: ImageView, ok: Boolean) {
        if (ok) {
            label.text = getString(R.string.status_enabled)
            label.setTextColor(Ui.OK)
            icon.setColorFilter(Ui.OK)
        } else {
            label.text = getString(R.string.status_tap_to_enable)
            label.setTextColor(Ui.DANGER)
            icon.setColorFilter(Ui.ACCENT)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ── Start ────────────────────────────────────────────────────────────────

    private fun startClicker() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, R.string.toast_enable_accessibility, Toast.LENGTH_SHORT).show()
            openAccessibilitySettings()
            return
        }
        if (!isOverlayGranted()) {
            Toast.makeText(this, R.string.toast_enable_overlay, Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }
        val service = ClickerService.instance
        if (service == null) {
            // Enabled in Settings but not connected yet (just turned on, or killed by the system).
            Toast.makeText(this, R.string.toast_service_not_connected, Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        service.showAll()
        moveTaskToBack(true)
    }
}
