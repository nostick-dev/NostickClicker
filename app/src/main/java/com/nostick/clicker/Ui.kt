/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 */
package com.nostick.clicker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/**
 * Small design system shared by every screen and overlay so the whole app has
 * one consistent look: dark navy surfaces, violet accent, rounded corners.
 */
object Ui {

    // ── Palette ─────────────────────────────────────────────────────────────
    val BG = 0xFF0B0B12.toInt()
    val SURFACE = 0xFF14141F.toInt()
    val SURFACE_HI = 0xFF1D1D2C.toInt()
    val STROKE = 0xFF2C2C40.toInt()
    val TEXT = 0xFFF3F3FA.toInt()
    val MUTED = 0xFF9A9AB5.toInt()
    val ACCENT = 0xFF8B5CF6.toInt()
    val ACCENT_DARK = 0xFF6D28D9.toInt()
    val ACCENT_SOFT = 0x338B5CF6
    val OK = 0xFF34D399.toInt()
    val DANGER = 0xFFF87171.toInt()
    val OVERLAY_BG = 0xEE0F0F18.toInt()

    const val PRIMARY = 0
    const val SECONDARY = 1
    const val DANGER_KIND = 2

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── Metrics ─────────────────────────────────────────────────────────────
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    // ── Drawables ───────────────────────────────────────────────────────────
    fun rounded(ctx: Context, fill: Int, radiusDp: Int = 14, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (stroke != 0) setStroke(dp(ctx, strokeDp), stroke)
        }

    fun oval(fill: Int, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != 0 && strokePx > 0) setStroke(strokePx, stroke)
        }

    fun gradient(ctx: Context, radiusDp: Int = 14, circle: Boolean = false): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT_DARK)).apply {
            if (circle) shape = GradientDrawable.OVAL else cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun ripple(ctx: Context, content: Drawable?, radiusDp: Int = 14, circle: Boolean = false): Drawable {
        val mask = GradientDrawable().apply {
            if (circle) {
                shape = GradientDrawable.OVAL
            } else {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, radiusDp).toFloat()
            }
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)
    }

    // ── Layout params ───────────────────────────────────────────────────────
    fun lp(
        ctx: Context,
        w: Int = MATCH,
        h: Int = WRAP,
        weight: Float = 0f,
        top: Int = 0,
        bottom: Int = 0,
        start: Int = 0,
        end: Int = 0
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = dp(ctx, top)
            bottomMargin = dp(ctx, bottom)
            marginStart = dp(ctx, start)
            marginEnd = dp(ctx, end)
        }

    // ── Widgets ─────────────────────────────────────────────────────────────
    fun text(ctx: Context, s: CharSequence, sizeSp: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD)
            else Typeface.create("sans-serif", Typeface.NORMAL)
        }

    fun button(ctx: Context, label: CharSequence, kind: Int = PRIMARY, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            setAllCaps(false)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
            when (kind) {
                PRIMARY -> {
                    setTextColor(Color.WHITE)
                    background = ripple(ctx, gradient(ctx, 14), 14)
                }
                DANGER_KIND -> {
                    setTextColor(DANGER)
                    background = ripple(ctx, rounded(ctx, 0x00000000, 14, 0x66F87171, 1), 14)
                }
                else -> {
                    setTextColor(TEXT)
                    background = ripple(ctx, rounded(ctx, SURFACE_HI, 14, STROKE, 1), 14)
                }
            }
            setOnClickListener { onClick() }
        }

    fun styleChip(tv: TextView, selected: Boolean) {
        val ctx = tv.context
        if (selected) {
            tv.setTextColor(Color.WHITE)
            tv.background = rounded(ctx, ACCENT, 20)
        } else {
            tv.setTextColor(0xFFC9C9DD.toInt())
            tv.background = rounded(ctx, SURFACE_HI, 20, STROKE, 1)
        }
    }

    fun chip(
        ctx: Context,
        label: CharSequence,
        selected: Boolean = false,
        hPadDp: Int = 14,
        onClick: (() -> Unit)? = null
    ): TextView =
        TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(ctx, hPadDp), dp(ctx, 9), dp(ctx, hPadDp), dp(ctx, 9))
            styleChip(this, selected)
            if (onClick != null) setOnClickListener { onClick() }
        }

    fun card(ctx: Context, paddingDp: Int = 18): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, paddingDp), dp(ctx, paddingDp), dp(ctx, paddingDp), dp(ctx, paddingDp))
            background = rounded(ctx, SURFACE, 22, STROKE, 1)
        }

    fun input(ctx: Context, hint: CharSequence, value: String = "", number: Boolean = false): EditText =
        EditText(ctx).apply {
            setHint(hint)
            setText(value)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            background = rounded(ctx, SURFACE_HI, 12, STROKE, 1)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
        }

    fun styleSeekBar(bar: SeekBar) {
        bar.progressTintList = ColorStateList.valueOf(ACCENT)
        bar.thumbTintList = ColorStateList.valueOf(ACCENT)
        bar.progressBackgroundTintList = ColorStateList.valueOf(STROKE)
    }

    fun iconButton(ctx: Context, res: Int, tint: Int = TEXT, sizeDp: Int = 44, onClick: () -> Unit): ImageButton =
        ImageButton(ctx).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            background = ripple(ctx, null, circle = true)
            minimumWidth = dp(ctx, sizeDp)
            minimumHeight = dp(ctx, sizeDp)
            setOnClickListener { onClick() }
        }

    // ── Screen helpers ──────────────────────────────────────────────────────

    /** Edge-to-edge window with system-bar padding applied to [root]. */
    fun setupScreen(activity: AppCompatActivity, root: View, padDp: Int = 20) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val pad = dp(activity, padDp)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun header(activity: AppCompatActivity, title: String, onBack: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(activity, R.drawable.ic_back) { onBack() })
            addView(text(activity, title, 22f, TEXT, true), lp(activity, start = 8))
        }

    /** Shows a rounded, transparent-window dialog around [content]. */
    fun dialog(ctx: Context, content: View, cancelable: Boolean = true): AlertDialog {
        val d = AlertDialog.Builder(ctx, R.style.Theme_NostickClicker_Dialog)
            .setView(content)
            .setCancelable(cancelable)
            .create()
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        val width = (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(dp(ctx, 400))
        d.window?.setLayout(width, WRAP)
        return d
    }
}
