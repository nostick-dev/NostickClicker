/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 *
 * ClickerService.kt
 * ─────────────────
 * AccessibilityService that owns every floating window of the app:
 *
 *  • the floating menu (play / points / speed / layout editor / hide / close)
 *  • draggable click points used by the sequential auto-clicker
 *  • on-screen profile controls (buttons and joysticks) and their editor
 *
 * The service never reads screen content and never touches the network. It only
 * injects the gestures the user asked for through dispatchGesture().
 */
package com.nostick.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class ClickerService : AccessibilityService() {

    companion object {
        /** Non-null while the service is connected. */
        @Volatile
        var instance: ClickerService? = null
            private set

        private const val STEP_MS = 30L
        private const val MAX_POINTS = 20
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }

    // ── Infrastructure ───────────────────────────────────────────────────────
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    /** False after the user pressed Close: every overlay is removed until showAll(). */
    private var overlaysEnabled = true

    // ── Floating menu ────────────────────────────────────────────────────────
    private var menuView: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var playButton: ImageButton? = null
    private var speedLabel: TextView? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuMinimized = false

    // ── Click points + loop ──────────────────────────────────────────────────
    private class ClickPoint(val view: View, val params: WindowManager.LayoutParams)

    private val clickPoints = mutableListOf<ClickPoint>()
    private var isRunning = false
    private var intervalMs = Prefs.DEFAULT_INTERVAL_MS
    private var maxClicks = 0
    private var clickCount = 0
    private var loopIndex = 0
    private val tickRunnable = Runnable { tick() }

    // ── Panels / editor / profile controls ───────────────────────────────────
    private var panelRoot: View? = null
    private var editorBar: View? = null
    private var profileChip: TextView? = null
    private var editorMode = false
    private var activeProfile: ControlProfile? = null
    private val controlViews = mutableListOf<ControlView>()

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        vibrator = getVibrator()
        intervalMs = Prefs.getClickIntervalMs(this)
        maxClicks = Prefs.getMaxClicks(this)
        activeProfile = loadActiveProfile()
        refreshBackend()
        showAll()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    /** Required by AccessibilityService; NostickClicker does not process events. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopClicking()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation changes the screen size: rebuild everything that depends on it.
        handler.post { rebuildAfterRotation() }
    }

    private fun cleanup() {
        engine.cancelAll()
        shizukuInjector?.shutdown()
        shizukuInjector = null
        stopClicking()
        closePanel()
        removeEditorBar()
        editorMode = false
        removeBubble()
        removeMenu()
        clearPoints()
        clearControls()
        instance = null
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API (used by the activities)
    // ════════════════════════════════════════════════════════════════════════

    fun showAll() {
        overlaysEnabled = true
        menuMinimized = false
        removeBubble()
        showMenu()
        if (editorMode) menuView?.visibility = View.GONE
        rebuildControls()
    }

    fun hideAll() {
        stopClicking()
        closePanel()
        removeEditorBar()
        editorMode = false
        removeBubble()
        removeMenu()
        clearPoints()
        clearControls()
        overlaysEnabled = false
    }

    fun isShowing(): Boolean = overlaysEnabled

    fun activeProfileId(): String = activeProfile?.id ?: ""

    /** Switches the on-screen controls to the saved profile with [id]. */
    fun activateProfile(id: String) {
        val profile = ProfileStore.find(this, id) ?: return
        activeProfile = profile
        Prefs.setActiveProfileId(this, id)
        profileChip?.text = profile.name
        rebuildControls()
    }

    /** Re-reads the active profile from disk (after the app edited it). */
    fun reloadActiveProfile() {
        val id = activeProfile?.id ?: return
        val fresh = ProfileStore.find(this, id)
        if (fresh == null) {
            activeProfile = loadActiveProfile()
        } else {
            activeProfile = fresh
        }
        rebuildControls()
    }

    fun openEditor() {
        if (!overlaysEnabled) showAll()
        enterEditor()
    }

    private fun loadActiveProfile(): ControlProfile {
        val list = ProfileStore.load(this)
        if (list.isEmpty()) {
            val p = ControlProfile(UUID.randomUUID().toString(), getString(R.string.default_profile_name, 1), "")
            ProfileStore.upsert(this, p)
            Prefs.setActiveProfileId(this, p.id)
            return p
        }
        val id = Prefs.getActiveProfileId(this)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Overlay helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun screenSize(): Point {
        val p = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
        }
        return p
    }

    private fun isLandscape(): Boolean {
        val s = screenSize()
        return s.x > s.y
    }

    /**
     * FLAG_LAYOUT_IN_SCREEN makes x/y absolute screen coordinates.
     * FLAG_NOT_TOUCH_MODAL lets touches outside the window reach the app below.
     */
    private fun overlayParams(
        w: Int,
        h: Int,
        x: Int = 0,
        y: Int = 0,
        touchable: Boolean = true,
        focusable: Boolean = false
    ): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
            it.x = x
            it.y = y
            if (focusable) it.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
    }

    private fun addOverlay(view: View, params: WindowManager.LayoutParams): Boolean =
        try {
            wm.addView(view, params)
            true
        } catch (e: Exception) {
            false
        }

    private fun removeOverlay(view: View?) {
        if (view == null) return
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun updateOverlay(view: View, params: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun clampParams(view: View, p: WindowManager.LayoutParams) {
        val s = screenSize()
        val w = if (view.width > 0) view.width else if (p.width > 0) p.width else 0
        val h = if (view.height > 0) view.height else if (p.height > 0) p.height else 0
        p.x = p.x.coerceIn(0, (s.x - w).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (s.y - h).coerceAtLeast(0))
    }

    private fun clampToScreen(view: View, p: WindowManager.LayoutParams) {
        clampParams(view, p)
        updateOverlay(view, p)
    }

    private fun centerOf(view: View): FloatArray {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return floatArrayOf(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
    }

    /** Drag [handle] to move [target]. A touch without movement calls [onTap]. */
    private fun attachDrag(
        handle: View,
        target: View,
        params: WindowManager.LayoutParams,
        onTap: (() -> Unit)? = null
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragged = true
                    if (dragged) {
                        params.x = (startX + dx).roundToInt()
                        params.y = (startY + dy).roundToInt()
                        clampParams(target, params)
                        updateOverlay(target, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap?.invoke()
                }
            }
            true
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun haptic() {
        try {
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(25L)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun getVibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Exception) {
        null
    }

    // ════════════════════════════════════════════════════════════════════════
    // Gestures
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Multi-touch engine. Android only allows ONE gesture at a time (a new
     * dispatchGesture() cancels the previous one), so every finger the app wants
     * down at the same moment is merged into a single gesture with one stroke per
     * finger. The gesture is re-sent in short continued segments for as long as any
     * finger is held, which gives real simultaneous holds, joysticks and taps.
     */
    private inner class TouchEngine : TouchInjector {
        private inner class Finger(val key: Any, var x: Float, var y: Float, var ending: Boolean) {
            var stroke: GestureDescription.StrokeDescription? = null
            var lastX = x
            var lastY = y
        }

        private val fingers = LinkedHashMap<Any, Finger>()
        private var busy = false
        private var tapCounter = 0L

        /** Puts a finger down at (x, y) and keeps it there until [up]. */
        override fun down(key: Any, x: Float, y: Float) {
            fingers[key] = Finger(key, x, y, false)
            pump()
        }

        /** Slides a held finger to a new position. */
        override fun move(key: Any, x: Float, y: Float) {
            val f = fingers[key] ?: return
            f.x = x
            f.y = y
        }

        override fun up(key: Any) {
            val f = fingers[key] ?: return
            f.ending = true
            pump()
        }

        override fun isDown(key: Any): Boolean = fingers[key]?.ending == false

        /** Quick tap: down and up inside one segment. */
        override fun tap(x: Float, y: Float) {
            // A tap still waiting for its gesture at the same spot would only fire together
            // with this one (two fingers at once): drop the extra one to keep a steady rhythm.
            if (fingers.values.any { it.ending && it.stroke == null && abs(it.x - x) < 2f && abs(it.y - y) < 2f }) return
            tapCounter++
            fingers["tap#$tapCounter"] = Finger("tap#$tapCounter", x, y, true)
            pump()
        }

        override fun cancelAll() {
            fingers.clear()
        }

        private fun pump() {
            if (!busy) step()
        }

        private fun step() {
            if (fingers.isEmpty()) return
            val batch = fingers.values.take(GestureDescription.getMaxStrokeCount())
            val builder = GestureDescription.Builder()
            val ended = mutableListOf<Any>()
            var added = 0
            for (f in batch) {
                val path = Path().apply {
                    moveTo(f.lastX, f.lastY)
                    if (f.x != f.lastX || f.y != f.lastY) lineTo(f.x, f.y)
                }
                val keepDown = !f.ending
                val prev = f.stroke
                val s = try {
                    if (prev == null) {
                        GestureDescription.StrokeDescription(path, 0L, STEP_MS, keepDown)
                    } else {
                        prev.continueStroke(path, 0L, STEP_MS, keepDown)
                    }
                } catch (e: Exception) {
                    ended.add(f.key)
                    continue
                }
                f.stroke = s
                f.lastX = f.x
                f.lastY = f.y
                builder.addStroke(s)
                added++
                if (f.ending) ended.add(f.key)
            }
            if (added == 0) {
                ended.forEach { fingers.remove(it) }
                return
            }
            busy = true
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    busy = false
                    ended.forEach { fingers.remove(it) }
                    if (fingers.isNotEmpty()) step()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    busy = false
                    ended.forEach { fingers.remove(it) }
                    // Something (another touch, the system) interrupted us: fingers that are
                    // still held start a fresh stroke from their last position and carry on.
                    fingers.values.forEach { it.stroke = null }
                    if (fingers.isNotEmpty()) handler.postDelayed({ if (!busy) step() }, 40L)
                }
            }
            val ok = try {
                dispatchGesture(builder.build(), callback, null)
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                busy = false
                fingers.clear()
            }
        }
    }

    private val engine = TouchEngine()
    private var shizukuInjector: ShizukuInjector? = null

    /** Shizuku when the user enabled it and it works, otherwise the Accessibility engine. */
    private fun touch(): TouchInjector = shizukuInjector ?: engine

    /** Called at start-up and whenever the user toggles Shizuku mode in the app. */
    fun refreshBackend() {
        shizukuInjector?.shutdown()
        shizukuInjector = null
        if (Prefs.isShizukuEnabled(this) && ShizukuInput.hasPermission()) {
            val injector = ShizukuInjector()
            if (injector.prepare()) {
                shizukuInjector = injector
            } else {
                injector.shutdown()
                toast(R.string.toast_shizuku_failed)
            }
        }
    }

    private fun tapAt(x: Float, y: Float) {
        touch().tap(x, y)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Floating menu
    // ════════════════════════════════════════════════════════════════════════

    private fun menuBackground(running: Boolean) =
        Ui.rounded(
            this, Ui.OVERLAY_BG, 26,
            if (running) Ui.ACCENT else Ui.STROKE,
            if (running) 2 else 1
        )

    private fun showMenu() {
        if (menuView != null || menuMinimized || !overlaysEnabled) return
        val params = menuParams ?: overlayParams(WRAP, WRAP, dp(12), dp(110))
        menuParams = params
        val view = buildMenu(params)
        if (addOverlay(view, params)) {
            menuView = view
            updateMenuState()
            view.post { menuView?.let { clampToScreen(it, params) } }
        }
    }

    private fun removeMenu() {
        removeOverlay(menuView)
        menuView = null
        playButton = null
        speedLabel = null
    }

    private fun buildMenu(params: WindowManager.LayoutParams): LinearLayout {
        val horizontal = isLandscape()
        val root = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = menuBackground(false)
        }

        fun slot(size: Int) = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        }

        fun divider(): View = View(this).apply { setBackgroundColor(Ui.STROKE) }
        fun dividerLp() =
            if (horizontal) LinearLayout.LayoutParams(dp(1), dp(24)).apply { setMargins(dp(4), 0, dp(4), 0) }
            else LinearLayout.LayoutParams(dp(24), dp(1)).apply { setMargins(0, dp(4), 0, dp(4)) }

        // Drag handle
        val handle = FrameLayout(this)
        val grip = View(this).apply { background = Ui.rounded(this@ClickerService, Ui.STROKE, 2) }
        handle.addView(
            grip,
            FrameLayout.LayoutParams(
                if (horizontal) dp(4) else dp(26),
                if (horizontal) dp(26) else dp(4),
                Gravity.CENTER
            )
        )
        root.addView(
            handle,
            LinearLayout.LayoutParams(
                if (horizontal) dp(18) else dp(44),
                if (horizontal) dp(44) else dp(18)
            )
        )
        attachDrag(handle, root, params)

        // Play / pause
        val play = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = Ui.ripple(this@ClickerService, Ui.gradient(this@ClickerService, circle = true), circle = true)
            contentDescription = getString(R.string.btn_play_pause)
            setOnClickListener {
                haptic()
                toggleClicking()
            }
        }
        playButton = play
        root.addView(play, slot(46))

        val label = Ui.text(this, "", 10f, Ui.MUTED, true).apply { gravity = Gravity.CENTER }
        speedLabel = label
        root.addView(
            label,
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                if (horizontal) marginStart = dp(4) else topMargin = dp(2)
            }
        )

        root.addView(divider(), dividerLp())

        fun icon(res: Int, desc: Int, tint: Int = Ui.TEXT, onClick: () -> Unit) {
            val b = Ui.iconButton(this, res, tint, 40) {
                haptic()
                onClick()
            }
            b.contentDescription = getString(desc)
            root.addView(b, slot(40))
        }

        icon(R.drawable.ic_add, R.string.btn_add) { addClickPoint() }
        icon(R.drawable.ic_remove, R.string.btn_remove) { removeLastClickPoint() }
        icon(R.drawable.ic_speed, R.string.btn_speed) { showSpeedPanel() }
        icon(R.drawable.ic_edit, R.string.btn_editor) { enterEditor() }
        root.addView(divider(), dividerLp())
        icon(R.drawable.ic_minimize, R.string.btn_minimize) { minimizeMenu() }
        icon(R.drawable.ic_close, R.string.btn_close, Ui.DANGER) { closeAll() }
        return root
    }

    private fun updateMenuState() {
        playButton?.setImageResource(if (isRunning) R.drawable.ic_pause else R.drawable.ic_play)
        speedLabel?.text = getString(R.string.ms_fmt, intervalMs)
        menuView?.background = menuBackground(isRunning)
    }

    private fun minimizeMenu() {
        closePanel()
        menuMinimized = true
        removeMenu()
        showBubble()
    }

    private fun showBubble() {
        if (bubbleView != null || !overlaysEnabled) return
        val size = dp(46)
        val params = bubbleParams ?: overlayParams(size, size, menuParams?.x ?: dp(12), menuParams?.y ?: dp(110))
        bubbleParams = params
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_cursor)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = Ui.ripple(this@ClickerService, Ui.gradient(this@ClickerService, circle = true), circle = true)
            alpha = 0.92f
            contentDescription = getString(R.string.btn_restore_menu)
        }
        attachDrag(view, view, params) { restoreMenu() }
        if (addOverlay(view, params)) {
            bubbleView = view
            view.post { bubbleView?.let { clampToScreen(it, params) } }
        }
    }

    private fun removeBubble() {
        removeOverlay(bubbleView)
        bubbleView = null
    }

    private fun restoreMenu() {
        val bp = bubbleParams
        val mp = menuParams
        if (bp != null && mp != null) {
            mp.x = bp.x
            mp.y = bp.y
        }
        menuMinimized = false
        removeBubble()
        showMenu()
    }

    private fun closeAll() {
        hideAll()
        toast(R.string.toast_closed)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Click points + loop
    // ════════════════════════════════════════════════════════════════════════

    fun addClickPoint() {
        if (clickPoints.size >= MAX_POINTS) {
            toast(R.string.toast_max_points)
            return
        }
        val index = clickPoints.size + 1
        val view = LayoutInflater.from(this).inflate(R.layout.layout_click_point, null)
        view.findViewById<TextView>(R.id.tvIndex).text = index.toString()
        val s = screenSize()
        val size = dp(52)
        val offset = ((index - 1) % 6) * dp(22)
        val params = overlayParams(
            size, size,
            s.x / 2 - size / 2 + offset,
            s.y / 2 - size / 2 + offset,
            touchable = !isRunning
        )
        attachDrag(view, view, params)
        if (addOverlay(view, params)) {
            clickPoints.add(ClickPoint(view, params))
            toast(R.string.toast_point_added)
        }
    }

    fun removeLastClickPoint() {
        if (clickPoints.isEmpty()) return
        // removeAt(lastIndex) instead of removeLast(): the latter crashes on Android < 15.
        val point = clickPoints.removeAt(clickPoints.lastIndex)
        removeOverlay(point.view)
        if (clickPoints.isEmpty() && isRunning) stopClicking()
        toast(R.string.toast_point_removed)
    }

    private fun clearPoints() {
        clickPoints.forEach { removeOverlay(it.view) }
        clickPoints.clear()
    }

    private fun setPointsTouchable(touchable: Boolean) {
        clickPoints.forEach { point ->
            // A touchable overlay would swallow the injected tap instead of the app below.
            point.params.flags = if (touchable) {
                point.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                point.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            point.view.alpha = if (touchable) 1f else 0.55f
            updateOverlay(point.view, point.params)
        }
    }

    private fun toggleClicking() {
        if (isRunning) stopClicking() else startClicking()
    }

    private fun startClicking() {
        if (isRunning) return
        if (clickPoints.isEmpty()) {
            toast(R.string.toast_no_points)
            return
        }
        isRunning = true
        loopIndex = 0
        clickCount = 0
        setPointsTouchable(false)
        updateMenuState()
        handler.postDelayed(tickRunnable, 200L)
    }

    private fun stopClicking() {
        val wasRunning = isRunning
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        if (wasRunning) setPointsTouchable(true)
        updateMenuState()
    }

    private fun tick() {
        if (!isRunning) return
        if (clickPoints.isEmpty()) {
            stopClicking()
            return
        }
        loopIndex %= clickPoints.size
        val point = clickPoints[loopIndex]
        loopIndex = (loopIndex + 1) % clickPoints.size
        val c = centerOf(point.view)
        tapAt(c[0], c[1])
        pulse(point.view)
        clickCount++
        if (maxClicks > 0 && clickCount >= maxClicks) {
            stopClicking()
            Toast.makeText(this, getString(R.string.toast_finished, clickCount), Toast.LENGTH_SHORT).show()
            return
        }
        handler.postDelayed(tickRunnable, intervalMs)
    }

    private fun pulse(v: View) {
        v.animate().cancel()
        v.scaleX = 0.8f
        v.scaleY = 0.8f
        v.animate().scaleX(1f).scaleY(1f).setDuration(min(intervalMs, 140L)).start()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Panels
    // ════════════════════════════════════════════════════════════════════════

    private fun showPanel(content: View, focusable: Boolean) {
        closePanel()
        val s = screenSize()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { closePanel() }
        }
        val scroll = ScrollView(this).apply { addView(content) }
        scrim.addView(
            scroll,
            FrameLayout.LayoutParams(min(s.x - dp(32), dp(360)), WRAP, Gravity.CENTER)
        )
        val params = overlayParams(MATCH, MATCH, 0, 0, focusable = focusable)
        if (focusable) {
            scrim.isFocusableInTouchMode = true
            scrim.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    closePanel()
                    true
                } else {
                    false
                }
            }
        }
        if (addOverlay(scrim, params)) {
            panelRoot = scrim
            if (focusable) scrim.requestFocus()
        }
    }

    private fun closePanel() {
        removeOverlay(panelRoot)
        panelRoot = null
    }

    private fun cpsText(ms: Long): String {
        val cps = 1000f / ms
        val shown = if (cps >= 10f) cps.roundToInt().toString() else String.format(Locale.US, "%.1f", cps)
        return getString(R.string.cps_fmt, shown)
    }

    private fun showSpeedPanel() {
        var ms = intervalMs
        var limit = maxClicks

        val card = Ui.card(this).apply { isClickable = true }
        card.addView(Ui.text(this, getString(R.string.settings_title), 18f, Ui.TEXT, true))
        val valueView = Ui.text(this, "", 34f, Ui.ACCENT, true)
        val subView = Ui.text(this, "", 13f, Ui.MUTED)
        card.addView(valueView, Ui.lp(this, top = 10))
        card.addView(subView, Ui.lp(this, bottom = 6))

        val seek = SeekBar(this)
        Ui.styleSeekBar(seek)
        seek.max = ((Prefs.MAX_INTERVAL_MS - Prefs.MIN_INTERVAL_MS) / 10L).toInt()
        seek.progress = ((ms - Prefs.MIN_INTERVAL_MS) / 10L).toInt()
        card.addView(seek, Ui.lp(this, bottom = 8))

        val presets = listOf(40L, 100L, 250L, 500L, 1000L)
        val presetChips = mutableListOf<Pair<Long, TextView>>()
        val limits = listOf(0, 50, 100, 500, 1000)
        val limitChips = mutableListOf<Pair<Int, TextView>>()

        fun refresh() {
            valueView.text = getString(R.string.ms_fmt, ms)
            subView.text = cpsText(ms)
            presetChips.forEach { (v, tv) -> Ui.styleChip(tv, v == ms) }
            limitChips.forEach { (v, tv) -> Ui.styleChip(tv, v == limit) }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                ms = Prefs.MIN_INTERVAL_MS + progress * 10L
                refresh()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        val presetRow = LinearLayout(this)
        presets.forEach { v ->
            val chip = Ui.chip(this, v.toString(), v == ms, 4) {
                ms = v
                seek.progress = ((v - Prefs.MIN_INTERVAL_MS) / 10L).toInt()
                refresh()
            }
            presetChips.add(v to chip)
            presetRow.addView(chip, Ui.lp(this, w = 0, weight = 1f, end = 6))
        }
        card.addView(presetRow)

        card.addView(Ui.text(this, getString(R.string.stop_after), 13f, Ui.MUTED), Ui.lp(this, top = 14, bottom = 6))
        val limitRow = LinearLayout(this)
        limits.forEach { v ->
            val chip = Ui.chip(this, if (v == 0) "∞" else v.toString(), v == limit, 4) {
                limit = v
                refresh()
            }
            limitChips.add(v to chip)
            limitRow.addView(chip, Ui.lp(this, w = 0, weight = 1f, end = 6))
        }
        card.addView(limitRow)

        val actions = LinearLayout(this)
        actions.addView(
            Ui.button(this, getString(android.R.string.cancel), Ui.SECONDARY) { closePanel() },
            Ui.lp(this, w = 0, h = dp(48), weight = 1f, end = 8)
        )
        actions.addView(
            Ui.button(this, getString(R.string.settings_ok), Ui.PRIMARY) {
                intervalMs = ms
                maxClicks = limit
                Prefs.setClickIntervalMs(this, ms)
                Prefs.setMaxClicks(this, limit)
                updateMenuState()
                closePanel()
            },
            Ui.lp(this, w = 0, h = dp(48), weight = 1f)
        )
        card.addView(actions, Ui.lp(this, top = 18))

        refresh()
        showPanel(card, false)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Layout editor
    // ════════════════════════════════════════════════════════════════════════

    private fun enterEditor() {
        if (editorMode) return
        stopClicking()
        closePanel()
        editorMode = true
        menuView?.visibility = View.GONE
        bubbleView?.visibility = View.GONE
        showEditorBar()
        rebuildControls()
    }

    private fun exitEditor(save: Boolean) {
        if (!editorMode) return
        if (save) {
            saveActiveProfile()
            toast(R.string.profile_saved)
        } else {
            val id = activeProfile?.id
            if (id != null) activeProfile = ProfileStore.find(this, id) ?: activeProfile
        }
        editorMode = false
        closePanel()
        removeEditorBar()
        menuView?.visibility = View.VISIBLE
        bubbleView?.visibility = View.VISIBLE
        rebuildControls()
    }

    private fun saveActiveProfile() {
        val profile = activeProfile ?: return
        ProfileStore.upsert(this, profile)
        Prefs.setActiveProfileId(this, profile.id)
    }

    private fun showEditorBar() {
        if (editorBar != null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        fun add(v: View) {
            row.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        }

        add(Ui.chip(this, getString(R.string.ed_add_button)) { addEditorControl(ControlWidget.BUTTON) })
        add(Ui.chip(this, getString(R.string.ed_add_move)) { addEditorControl(ControlWidget.MOVE_JOYSTICK) })
        add(Ui.chip(this, getString(R.string.ed_add_look)) { addEditorControl(ControlWidget.CAMERA_JOYSTICK) })
        val chip = Ui.chip(this, "▾ " + (activeProfile?.name ?: "")) { showProfilePicker() }
        profileChip = chip
        add(chip)
        add(Ui.chip(this, getString(R.string.ed_done), true) { exitEditor(true) })
        add(Ui.chip(this, getString(R.string.ed_discard)) { exitEditor(false) })

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            background = Ui.rounded(this@ClickerService, Ui.OVERLAY_BG, 24, Ui.STROKE, 1)
        }
        val params = overlayParams(WRAP, WRAP, 0, dp(10))
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        if (addOverlay(scroll, params)) editorBar = scroll
    }

    private fun removeEditorBar() {
        removeOverlay(editorBar)
        editorBar = null
        profileChip = null
    }

    private fun showProfilePicker() {
        val card = Ui.card(this).apply { isClickable = true }
        card.addView(Ui.text(this, getString(R.string.profiles_title), 18f, Ui.TEXT, true))
        ProfileStore.load(this).forEach { p ->
            val row = Ui.chip(this, p.name, p.id == activeProfile?.id, 14) {
                switchProfile(p.id)
                closePanel()
            }
            card.addView(row, Ui.lp(this, top = 8))
        }
        card.addView(
            Ui.button(this, getString(R.string.ed_new_profile), Ui.SECONDARY) {
                createProfile()
                closePanel()
            },
            Ui.lp(this, h = dp(46), top = 14)
        )
        showPanel(card, false)
    }

    private fun switchProfile(id: String) {
        saveActiveProfile()
        val next = ProfileStore.find(this, id) ?: return
        activeProfile = next
        Prefs.setActiveProfileId(this, id)
        profileChip?.text = "▾ " + next.name
        rebuildControls()
    }

    private fun createProfile() {
        saveActiveProfile()
        val count = ProfileStore.load(this).size + 1
        val p = ControlProfile(UUID.randomUUID().toString(), getString(R.string.default_profile_name, count), "")
        ProfileStore.upsert(this, p)
        activeProfile = p
        Prefs.setActiveProfileId(this, p.id)
        profileChip?.text = "▾ " + p.name
        rebuildControls()
    }

    private fun addEditorControl(widget: ControlWidget) {
        val profile = activeProfile ?: return
        val n = profile.controls.size
        val isButton = widget == ControlWidget.BUTTON
        val label = when (widget) {
            ControlWidget.BUTTON -> getString(R.string.widget_button)
            ControlWidget.MOVE_JOYSTICK -> getString(R.string.widget_move)
            ControlWidget.CAMERA_JOYSTICK -> getString(R.string.widget_look)
        }
        val offset = (n % 6) * 0.04f
        val width = when (widget) {
            ControlWidget.BUTTON -> 0.16f
            ControlWidget.MOVE_JOYSTICK -> 0.32f
            ControlWidget.CAMERA_JOYSTICK -> 0.5f
        }
        val event = ControlEvent(
            id = System.nanoTime(),
            label = label,
            type = ControlEventType.SCREEN,
            action = ControlAction.CLICK,
            widget = widget,
            x = 0.35f + offset,
            y = 0.30f + offset,
            width = width,
            height = width * 0.75f,
            sensitivity = 1f
        )
        profile.controls.add(event)
        rebuildControls()
    }

    private fun actionName(a: ControlAction): String = getString(
        when (a) {
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

    private fun actionDesc(a: ControlAction): Int = when (a) {
        ControlAction.CLICK -> R.string.action_desc_click
        ControlAction.HOLD -> R.string.action_desc_hold
        ControlAction.SPAM -> R.string.action_desc_spam
        ControlAction.TOGGLE -> R.string.action_desc_toggle
        ControlAction.HOLD_TOGGLE -> R.string.action_desc_hold_toggle
        ControlAction.BACK -> R.string.action_desc_back
        ControlAction.HOME -> R.string.action_desc_home
        ControlAction.RECENTS -> R.string.action_desc_recents
    }

    private fun showControlPanel(cv: ControlView) {
        val ev = cv.event
        val isButton = ev.widget == ControlWidget.BUTTON
        var action = ev.action

        val card = Ui.card(this).apply { isClickable = true }
        card.addView(Ui.text(this, getString(R.string.control_properties), 18f, Ui.TEXT, true))

        val labelInput = Ui.input(this, getString(R.string.control_label), ev.label)
        val intervalInput = Ui.input(this, getString(R.string.control_interval), ev.intervalMs.toString(), true)

        val chipMap = mutableMapOf<ControlAction, TextView>()
        val descView = Ui.text(this, "", 12f, Ui.ACCENT)
        fun refreshChips() {
            chipMap.forEach { (a, tv) -> Ui.styleChip(tv, a == action) }
            descView.text = getString(actionDesc(action))
        }

        fun chipRow(actions: List<ControlAction>): LinearLayout {
            val row = LinearLayout(this)
            actions.forEach { a ->
                val c = Ui.chip(this, actionName(a), a == action, 4) {
                    action = a
                    refreshChips()
                }
                chipMap[a] = c
                row.addView(c, Ui.lp(this, w = 0, weight = 1f, end = 6))
            }
            return row
        }

        if (isButton) {
            card.addView(Ui.text(this, getString(R.string.control_label), 13f, Ui.MUTED), Ui.lp(this, top = 12, bottom = 6))
            card.addView(labelInput)
            card.addView(Ui.text(this, getString(R.string.control_action), 13f, Ui.MUTED), Ui.lp(this, top = 12, bottom = 6))
            card.addView(chipRow(listOf(ControlAction.CLICK, ControlAction.HOLD, ControlAction.SPAM)))
            card.addView(chipRow(listOf(ControlAction.HOLD_TOGGLE, ControlAction.TOGGLE)), Ui.lp(this, top = 6))
            card.addView(descView, Ui.lp(this, top = 8))
            card.addView(Ui.text(this, getString(R.string.control_system), 13f, Ui.MUTED), Ui.lp(this, top = 12, bottom = 6))
            card.addView(chipRow(listOf(ControlAction.BACK, ControlAction.HOME, ControlAction.RECENTS)))
            refreshChips()
            card.addView(Ui.text(this, getString(R.string.control_interval), 13f, Ui.MUTED), Ui.lp(this, top = 12, bottom = 6))
            card.addView(intervalInput)
        }

        val sizeLabel = Ui.text(this, "", 13f, Ui.MUTED)
        val sizeSeek = SeekBar(this)
        Ui.styleSeekBar(sizeSeek)
        val minSize = if (ev.widget == ControlWidget.CAMERA_JOYSTICK) 80 else 48
        val maxSize = if (ev.widget == ControlWidget.CAMERA_JOYSTICK) 320 else 200
        sizeSeek.max = maxSize - minSize
        sizeSeek.progress = (ev.width * 360f).roundToInt().coerceIn(minSize, maxSize) - minSize
        fun refreshSize() {
            sizeLabel.text = getString(R.string.control_size_fmt, sizeSeek.progress + minSize)
        }
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = refreshSize()
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        refreshSize()
        card.addView(sizeLabel, Ui.lp(this, top = 12, bottom = 4))
        card.addView(sizeSeek)

        val sensLabel = Ui.text(this, "", 13f, Ui.MUTED)
        val sensSeek = SeekBar(this)
        Ui.styleSeekBar(sensSeek)
        sensSeek.max = 55
        sensSeek.progress = ((ev.sensitivity - 0.5f) * 10f).roundToInt().coerceIn(0, 55)
        fun refreshSens() {
            sensLabel.text = getString(R.string.control_sensitivity_fmt, String.format(Locale.US, "%.1f", 0.5f + sensSeek.progress / 10f))
        }
        sensSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = refreshSens()
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        refreshSens()
        if (!isButton) {
            card.addView(sensLabel, Ui.lp(this, top = 12, bottom = 4))
            card.addView(sensSeek)
            card.addView(
                Ui.text(
                    this,
                    getString(if (ev.widget == ControlWidget.MOVE_JOYSTICK) R.string.hint_move else R.string.hint_look),
                    12f, Ui.ACCENT
                ),
                Ui.lp(this, top = 12)
            )
        }

        card.addView(
            Ui.text(this, getString(R.string.control_target_hint), 12f, Ui.MUTED),
            Ui.lp(this, top = 14)
        )

        val actions = LinearLayout(this)
        actions.addView(
            Ui.button(this, getString(android.R.string.cancel), Ui.SECONDARY) { closePanel() },
            Ui.lp(this, w = 0, h = dp(48), weight = 1f, end = 8)
        )
        actions.addView(
            Ui.button(this, getString(R.string.settings_ok), Ui.PRIMARY) {
                if (isButton) {
                    ev.label = labelInput.text.toString().trim().ifBlank { ev.label }
                    ev.action = action
                    ev.intervalMs = intervalInput.text.toString().toLongOrNull()?.coerceIn(40L, 10000L) ?: ev.intervalMs
                } else {
                    ev.sensitivity = 0.5f + sensSeek.progress / 10f
                }
                ev.width = (sizeSeek.progress + minSize) / 360f
                ev.height = ev.width * 0.75f
                closePanel()
                rebuildControls()
            },
            Ui.lp(this, w = 0, h = dp(48), weight = 1f)
        )
        card.addView(actions, Ui.lp(this, top = 16))
        card.addView(
            Ui.button(this, getString(R.string.control_delete), Ui.DANGER_KIND) {
                activeProfile?.controls?.remove(ev)
                closePanel()
                rebuildControls()
            },
            Ui.lp(this, h = dp(44), top = 8)
        )

        showPanel(card, true)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Profile controls (buttons + joysticks)
    // ════════════════════════════════════════════════════════════════════════

    private inner class ControlView(
        val event: ControlEvent,
        val view: View,
        val params: WindowManager.LayoutParams
    ) {
        var knob: View? = null
        var lockIcon: View? = null
        var marker: View? = null
        var markerParams: WindowManager.LayoutParams? = null
        var active = false
        var suspended: List<ControlView> = emptyList()
        val releaseRunnable = Runnable { resumeBlockers(this@ControlView) }

        // Move stick: latched ("locked") walking.
        var locked = false
        var outside = false
        var ignoreTouch = false

        // Look pad: the game finger copies the movement of the thumb (like a touch screen).
        var camKey: Any = Any()
        var camStartX = 0f
        var camStartY = 0f
        var camPosX = 0f
        var camPosY = 0f
        var lastRawX = 0f
        var lastRawY = 0f

        val repeat = object : Runnable {
            override fun run() {
                if (!active) return
                val t = targetPx(event)
                if (t != null) tapAt(t[0], t[1])
                handler.postDelayed(this, event.intervalMs.coerceIn(40L, 10000L))
            }
        }

        fun stopAll() {
            active = false
            locked = false
            handler.removeCallbacks(repeat)
            touch().up(this)
            touch().up(camKey)
            resumeBlockers(this)
        }
    }

    /** Screen position the control acts on, or null when no target was set yet. */
    private fun targetPx(ev: ControlEvent): FloatArray? {
        if (ev.targetX < 0f || ev.targetY < 0f) {
            toast(R.string.toast_no_target)
            return null
        }
        val s = screenSize()
        return floatArrayOf(ev.targetX * s.x, ev.targetY * s.y)
    }

    private fun clearControls() {
        controlViews.forEach { cv ->
            cv.stopAll()
            removeOverlay(cv.view)
            removeOverlay(cv.marker)
        }
        controlViews.clear()
        suspendCount.clear()
    }

    private fun rebuildControls() {
        clearControls()
        if (!overlaysEnabled) return
        val profile = activeProfile ?: return
        if (!profile.screenEventsEnabled && !editorMode) return
        val screen = screenSize()
        var index = 0
        profile.controls.forEach { ev ->
            if (ev.type != ControlEventType.SCREEN) return@forEach
            if (!ev.enabled && !editorMode) return@forEach
            index++
            addControlView(ev, index, screen)
        }
    }

    private fun controlBackground(active: Boolean) =
        Ui.rounded(
            this,
            if (active) 0xEE059669.toInt() else 0xCC7C3AED.toInt(),
            16,
            if (editorMode) Color.WHITE else 0x66FFFFFF,
            1
        )

    private fun addControlView(ev: ControlEvent, index: Int, screen: Point) {
        val isButton = ev.widget == ControlWidget.BUTTON
        val isCamera = ev.widget == ControlWidget.CAMERA_JOYSTICK
        val sizeDp = (ev.width * 360f).coerceIn(if (isCamera) 80f else 48f, if (isCamera) 320f else 200f)
        val wPx = dp((if (isCamera) sizeDp * 1.3f else sizeDp).roundToInt())
        val hPx = when {
            isButton -> dp((sizeDp * 0.75f).coerceAtLeast(44f).roundToInt())
            isCamera -> dp(sizeDp.roundToInt())
            else -> wPx
        }

        var knob: View? = null
        var lock: View? = null
        val view: View = if (isButton) {
            Ui.text(this, if (editorMode) "$index · ${ev.label}" else ev.label, 12f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(dp(4), dp(2), dp(4), dp(2))
                background = controlBackground(false)
            }
        } else if (isCamera) {
            // Look pad: a touch area. A small dot follows the thumb while it is down.
            val frame = FrameLayout(this)
            frame.background = Ui.rounded(this, 0x44000000, 18, 0x88FFFFFF.toInt(), 2)
            val dot = View(this)
            dot.background = Ui.oval(0xFFF2F2F2.toInt(), 0xFFB5B5B5.toInt(), dp(1))
            dot.visibility = if (editorMode) View.VISIBLE else View.GONE
            frame.addView(dot, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            val tag = (if (editorMode) "$index " else "") + getString(R.string.widget_look)
            frame.addView(
                Ui.text(this, tag, 10f, if (editorMode) Ui.MUTED else 0x88FFFFFF.toInt(), true),
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(6) }
            )
            knob = dot
            frame
        } else {
            // Classic joystick: dark translucent base, neutral white thumb.
            val frame = FrameLayout(this)
            frame.background = Ui.oval(0x55000000, 0x99FFFFFF.toInt(), dp(2))
            val knobSize = (wPx * 0.42f).roundToInt()
            val k = View(this)
            k.background = Ui.oval(0xFFF2F2F2.toInt(), 0xFFB5B5B5.toInt(), dp(2))
            frame.addView(k, FrameLayout.LayoutParams(knobSize, knobSize, Gravity.CENTER))
            if (editorMode) {
                val tag = "$index " +
                    (if (ev.widget == ControlWidget.MOVE_JOYSTICK) getString(R.string.widget_move) else getString(R.string.widget_look))
                frame.addView(
                    Ui.text(this, tag, 9f, Ui.MUTED, true),
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(6) }
                )
            }
            if (ev.widget == ControlWidget.MOVE_JOYSTICK && !editorMode) {
                val lockView = Ui.text(this, "🔒", 14f, Color.WHITE, false)
                lockView.visibility = View.GONE
                frame.addView(
                    lockView,
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(4) }
                )
                lock = lockView
            }
            knob = k
            frame
        }

        val params = overlayParams(wPx, hPx)
        params.x = (ev.x * screen.x).roundToInt().coerceIn(0, (screen.x - wPx).coerceAtLeast(0))
        params.y = (ev.y * screen.y).roundToInt().coerceIn(0, (screen.y - hPx).coerceAtLeast(0))

        val cv = ControlView(ev, view, params)
        cv.knob = knob
        cv.lockIcon = lock
        if (!addOverlay(view, params)) return
        controlViews.add(cv)

        if (editorMode) {
            addMarker(cv, index, screen, wPx)
            attachEditTouch(cv)
        } else {
            attachRunTouch(cv)
        }
    }

    private fun addMarker(cv: ControlView, index: Int, screen: Point, controlWidthPx: Int) {
        val ev = cv.event
        val size = dp(36)
        val marker = Ui.text(this, index.toString(), 13f, Color.BLACK, true).apply {
            gravity = Gravity.CENTER
            background = Ui.oval(Ui.OK, Color.WHITE, dp(2))
        }
        // Controls without a target get one placed beside the control, never on top of it.
        if (ev.targetX < 0f || ev.targetY < 0f) {
            val gap = dp(28)
            var cx = cv.params.x + cv.params.width + gap + size / 2f
            if (cx > screen.x - size) cx = cv.params.x - gap - size / 2f
            val cy = cv.params.y + cv.params.height / 2f
            ev.targetX = (cx / screen.x).coerceIn(0.02f, 0.98f)
            ev.targetY = (cy / screen.y).coerceIn(0.02f, 0.98f)
        }
        val mp = overlayParams(size, size)
        mp.x = (ev.targetX * screen.x - size / 2f).roundToInt().coerceIn(0, (screen.x - size).coerceAtLeast(0))
        mp.y = (ev.targetY * screen.y - size / 2f).roundToInt().coerceIn(0, (screen.y - size).coerceAtLeast(0))
        if (!addOverlay(marker, mp)) return
        cv.marker = marker
        cv.markerParams = mp

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        marker.setOnTouchListener { _, m ->
            when (m.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = m.rawX
                    downY = m.rawY
                    startX = mp.x
                    startY = mp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    mp.x = (startX + m.rawX - downX).roundToInt()
                    mp.y = (startY + m.rawY - downY).roundToInt()
                    clampParams(marker, mp)
                    updateOverlay(marker, mp)
                }
                MotionEvent.ACTION_UP -> {
                    val s = screenSize()
                    ev.targetX = (mp.x + size / 2f) / s.x
                    ev.targetY = (mp.y + size / 2f) / s.y
                    val longLived = ev.widget != ControlWidget.BUTTON ||
                        ev.action == ControlAction.HOLD || ev.action == ControlAction.TOGGLE ||
                        ev.action == ControlAction.SPAM || ev.action == ControlAction.HOLD_TOGGLE
                    if (longLived && targetInsideSelf(cv)) toast(R.string.toast_target_on_self)
                }
            }
            true
        }
    }

    /** Editor mode: drag the control, tap it to open its properties. */
    private fun attachEditTouch(cv: ControlView) {
        val p = cv.params
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        cv.view.setOnTouchListener { _, m ->
            when (m.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = m.rawX
                    downY = m.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = m.rawX - downX
                    val dy = m.rawY - downY
                    if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) moved = true
                    if (moved) {
                        p.x = (startX + dx).roundToInt()
                        p.y = (startY + dy).roundToInt()
                        clampParams(cv.view, p)
                        updateOverlay(cv.view, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val s = screenSize()
                    cv.event.x = p.x / s.x.toFloat()
                    cv.event.y = p.y / s.y.toFloat()
                    if (!moved) showControlPanel(cv)
                }
            }
            true
        }
    }

    /** Normal mode: the control triggers its action on the target point. */
    private fun attachRunTouch(cv: ControlView) {
        val ev = cv.event
        cv.view.setOnTouchListener { v, m ->
            when (m.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.7f
                    if (cv.locked) {
                        // A tap on a locked stick releases it.
                        unlockMove(cv)
                        cv.ignoreTouch = true
                    } else {
                        cv.ignoreTouch = false
                        onControlDown(cv, m)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!cv.ignoreTouch && ev.widget != ControlWidget.BUTTON) joystickMove(cv, m)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    if (cv.ignoreTouch) cv.ignoreTouch = false else onControlUp(cv)
                }
            }
            true
        }
    }

    // ── Pass-through: a target placed on top of another control ignores that control ──

    private val suspendCount = HashMap<ControlView, Int>()

    private fun contains(c: ControlView, tx: Float, ty: Float): Boolean =
        tx >= c.params.x && tx <= c.params.x + c.params.width &&
            ty >= c.params.y && ty <= c.params.y + c.params.height

    private fun targetInsideSelf(cv: ControlView): Boolean {
        val ev = cv.event
        if (ev.targetX < 0f || ev.targetY < 0f) return false
        val s = screenSize()
        return contains(cv, ev.targetX * s.x, ev.targetY * s.y)
    }

    private fun blockersOf(cv: ControlView): List<ControlView> {
        val ev = cv.event
        if (ev.targetX < 0f || ev.targetY < 0f) return emptyList()
        val s = screenSize()
        val tx = ev.targetX * s.x
        val ty = ev.targetY * s.y
        return controlViews.filter { it !== cv && contains(it, tx, ty) }
    }

    private fun suspendControl(c: ControlView) {
        val n = (suspendCount[c] ?: 0) + 1
        suspendCount[c] = n
        if (n == 1) {
            c.params.flags = c.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            updateOverlay(c.view, c.params)
        }
    }

    private fun resumeControl(c: ControlView) {
        val n = (suspendCount[c] ?: 0) - 1
        if (n <= 0) {
            suspendCount.remove(c)
            c.params.flags = c.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            updateOverlay(c.view, c.params)
        } else {
            suspendCount[c] = n
        }
    }

    /** Controls sitting on [cv]'s target stop catching touches while [cv] is acting. */
    private fun suspendBlockers(cv: ControlView) {
        handler.removeCallbacks(cv.releaseRunnable)
        if (cv.suspended.isNotEmpty()) return
        val list = blockersOf(cv)
        cv.suspended = list
        list.forEach { suspendControl(it) }
    }

    private fun resumeBlockers(cv: ControlView) {
        handler.removeCallbacks(cv.releaseRunnable)
        val list = cv.suspended
        cv.suspended = emptyList()
        list.forEach { resumeControl(it) }
    }

    private fun resetKnob(cv: ControlView) {
        cv.knob?.let {
            it.translationX = 0f
            it.translationY = 0f
        }
        cv.lockIcon?.visibility = View.GONE
        cv.outside = false
    }

    private fun unlockMove(cv: ControlView) {
        cv.locked = false
        touch().up(cv)
        resumeBlockers(cv)
        resetKnob(cv)
    }

    private fun onControlDown(cv: ControlView, m: MotionEvent) {
        val ev = cv.event
        val isButton = ev.widget == ControlWidget.BUTTON
        val longLived = !isButton || ev.action == ControlAction.HOLD ||
            ev.action == ControlAction.TOGGLE || ev.action == ControlAction.SPAM ||
            ev.action == ControlAction.HOLD_TOGGLE
        val isSystem = isButton && (ev.action == ControlAction.BACK ||
            ev.action == ControlAction.HOME || ev.action == ControlAction.RECENTS)
        if (longLived && !isSystem && targetInsideSelf(cv)) {
            toast(R.string.toast_target_on_self)
            return
        }
        if (!isButton) {
            val t = targetPx(ev) ?: return
            suspendBlockers(cv)
            if (ev.widget == ControlWidget.CAMERA_JOYSTICK) {
                startCamera(cv, t, m)
            } else {
                touch().down(cv, t[0], t[1])
                joystickMove(cv, m)
            }
            return
        }
        when (ev.action) {
            ControlAction.CLICK -> {
                val t = targetPx(ev)
                if (t != null) {
                    suspendBlockers(cv)
                    tapAt(t[0], t[1])
                    handler.postDelayed(cv.releaseRunnable, 160L)
                }
            }
            ControlAction.SPAM -> {
                if (targetPx(ev) != null) {
                    suspendBlockers(cv)
                    cv.active = true
                    handler.removeCallbacks(cv.repeat)
                    handler.post(cv.repeat)
                }
            }
            ControlAction.TOGGLE -> {
                // Auto spam: press once to start tapping, press again to stop.
                if (cv.active) {
                    cv.active = false
                    handler.removeCallbacks(cv.repeat)
                    resumeBlockers(cv)
                } else if (targetPx(ev) != null) {
                    suspendBlockers(cv)
                    cv.active = true
                    handler.removeCallbacks(cv.repeat)
                    handler.post(cv.repeat)
                }
                cv.view.background = controlBackground(cv.active)
            }
            ControlAction.HOLD -> {
                val t = targetPx(ev)
                if (t != null) {
                    suspendBlockers(cv)
                    touch().down(cv, t[0], t[1])
                }
            }
            ControlAction.HOLD_TOGGLE -> {
                // Hold lock: press once to keep the target pressed, press again to release.
                if (cv.active) {
                    cv.active = false
                    touch().up(cv)
                    resumeBlockers(cv)
                } else {
                    val t = targetPx(ev)
                    if (t != null) {
                        suspendBlockers(cv)
                        cv.active = true
                        touch().down(cv, t[0], t[1])
                    }
                }
                cv.view.background = controlBackground(cv.active)
            }
            ControlAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ControlAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ControlAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    private fun onControlUp(cv: ControlView) {
        val ev = cv.event
        when (ev.widget) {
            ControlWidget.MOVE_JOYSTICK -> {
                if (cv.outside && touch().isDown(cv)) {
                    // Released outside the base circle: latch. The character keeps walking
                    // by itself until the stick is tapped again.
                    cv.locked = true
                    return
                }
                touch().up(cv)
                resumeBlockers(cv)
                resetKnob(cv)
            }
            ControlWidget.CAMERA_JOYSTICK -> {
                touch().up(cv.camKey)
                resumeBlockers(cv)
                resetKnob(cv)
                cv.knob?.visibility = View.GONE
            }
            ControlWidget.BUTTON -> when (ev.action) {
                ControlAction.SPAM -> {
                    cv.active = false
                    handler.removeCallbacks(cv.repeat)
                    resumeBlockers(cv)
                }
                ControlAction.HOLD -> {
                    touch().up(cv)
                    resumeBlockers(cv)
                }
                else -> Unit
            }
        }
    }

    /**
     * Look pad. It behaves like touching the game screen directly: the game finger starts at
     * the control's target and copies every movement of the thumb on the pad (scaled by the
     * sensitivity), with no delay-based drift and no dead zone. When the game finger runs
     * out of room it is lifted and put back further behind, so the camera never stops.
     */
    private fun startCamera(cv: ControlView, t: FloatArray, m: MotionEvent) {
        cv.camStartX = t[0]
        cv.camStartY = t[1]
        cv.camPosX = t[0]
        cv.camPosY = t[1]
        cv.lastRawX = m.rawX
        cv.lastRawY = m.rawY
        cv.camKey = Any()
        touch().down(cv.camKey, t[0], t[1])
        cv.knob?.visibility = View.VISIBLE
        moveLookDot(cv, m)
    }

    private fun moveLookDot(cv: ControlView, m: MotionEvent) {
        val c = centerOf(cv.view)
        val r = dp(9).toFloat()
        val maxX = (cv.view.width / 2f - r).coerceAtLeast(0f)
        val maxY = (cv.view.height / 2f - r).coerceAtLeast(0f)
        cv.knob?.let {
            it.translationX = (m.rawX - c[0]).coerceIn(-maxX, maxX)
            it.translationY = (m.rawY - c[1]).coerceIn(-maxY, maxY)
        }
    }

    private fun lookMove(cv: ControlView, m: MotionEvent) {
        if (!touch().isDown(cv.camKey)) return
        val gain = cv.event.sensitivity
        val nx = cv.camPosX + (m.rawX - cv.lastRawX) * gain
        val ny = cv.camPosY + (m.rawY - cv.lastRawY) * gain
        cv.lastRawX = m.rawX
        cv.lastRawY = m.rawY
        moveLookDot(cv, m)

        val s = screenSize()
        val reach = min(s.x, s.y) * 0.35f
        val margin = dp(8).toFloat()
        val ox = nx - cv.camStartX
        val oy = ny - cv.camStartY
        val off = hypot(ox, oy)
        val outOfRoom = off > reach || nx < margin || nx > s.x - margin ||
            ny < margin || ny > s.y - margin
        if (outOfRoom) {
            // Lift the game finger and put it back on the opposite side of the start point,
            // which gives the movement direction 1.5x more room before the next reset.
            val ux = if (off > 0f) ox / off else 0f
            val uy = if (off > 0f) oy / off else 0f
            val wx = (cv.camStartX - ux * reach * 0.5f).coerceIn(margin * 2, s.x - margin * 2)
            val wy = (cv.camStartY - uy * reach * 0.5f).coerceIn(margin * 2, s.y - margin * 2)
            touch().up(cv.camKey)
            cv.camKey = Any()
            cv.camPosX = wx
            cv.camPosY = wy
            touch().down(cv.camKey, wx, wy)
        } else {
            cv.camPosX = nx
            cv.camPosY = ny
            touch().move(cv.camKey, nx, ny)
        }
    }

    private fun joystickMove(cv: ControlView, m: MotionEvent) {
        val ev = cv.event
        if (ev.widget == ControlWidget.CAMERA_JOYSTICK) {
            lookMove(cv, m)
            return
        }
        if (!touch().isDown(cv)) return
        val radius = min(cv.view.width, cv.view.height) / 2f
        if (radius <= 0f) return
        val travel = radius * 0.58f // the thumb radius is 42% of the base radius
        val c = centerOf(cv.view)
        var dx = m.rawX - c[0]
        var dy = m.rawY - c[1]
        val dist = hypot(dx, dy)
        if (dist > travel && dist > 0f) {
            dx = dx / dist * travel
            dy = dy / dist * travel
        }
        cv.knob?.let {
            it.translationX = dx
            it.translationY = dy
        }
        // Leaving the base circle arms the lock (released there = latched).
        val outside = dist > radius
        if (outside != cv.outside) {
            cv.outside = outside
            cv.lockIcon?.visibility = if (outside) View.VISIBLE else View.GONE
            if (outside) haptic()
        }
        val t = targetPx(ev) ?: return
        val s = screenSize()
        val gain = ev.sensitivity * radius / travel
        touch().move(
            cv,
            (t[0] + dx * gain).coerceIn(0f, s.x.toFloat()),
            (t[1] + dy * gain).coerceIn(0f, s.y.toFloat())
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // Rotation
    // ════════════════════════════════════════════════════════════════════════

    private fun rebuildAfterRotation() {
        if (!overlaysEnabled || instance == null) return
        closePanel()
        if (menuView != null) {
            removeMenu()
            showMenu()
        }
        bubbleView?.let { v -> bubbleParams?.let { clampToScreen(v, it) } }
        clickPoints.forEach { clampToScreen(it.view, it.params) }
        if (editorMode) {
            removeEditorBar()
            showEditorBar()
        }
        rebuildControls()
    }
}
