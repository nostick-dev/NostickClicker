/*
 * NostickClicker — Auto Clicker
 * Copyright (c) 2026 Nostick. All rights reserved.
 *
 * Proprietary software. Copying, modification, distribution, sale or any other use
 * without the written permission of Nostick is prohibited. See LICENSE.
 */
package com.nostick.clicker

import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/** Small helpers around the optional Shizuku integration. */
object ShizukuInput {
    const val REQUEST_CODE = 4242
    const val PACKAGE = "moe.shizuku.privileged.api"

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }
}

/**
 * Injects real MotionEvents through Shizuku (shell privileges), so every finger is a
 * genuine pointer of one continuous touch stream. No gesture is ever cancelled by the
 * user's own touches, which makes holds, joysticks and multi-button play smooth.
 */
class ShizukuInjector : TouchInjector {

    private companion object {
        const val MIN_MOVE_GAP_MS = 6L
    }

    private class Pt(val id: Int, var x: Float, var y: Float)

    private val thread = HandlerThread("nostick-inject", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val bg = Handler(thread.looper)

    // Main-thread side: which keys the app currently considers pressed.
    private val held = HashSet<Any>()

    // Injection-thread side.
    private val pts = LinkedHashMap<Any, Pt>()
    private var downTime = 0L
    private var lastEventTime = 0L
    private var tapCounter = 0

    // Moves are merged: at most one ACTION_MOVE every few ms carrying the latest position of
    // EVERY finger. Two sticks used at once then produce one steady event stream instead of
    // two interleaved floods, which is what made fast camera turns stutter.
    private var dirty = false
    private var pendingTime = 0L
    private var lastMoveSent = 0L
    private val flushRunnable = Runnable { flushMove() }

    private var service: Any? = null
    private var inject: Method? = null
    private var injectArgs = 2

    /** Connects to the system input service through Shizuku. */
    fun prepare(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("L")
            }
            val binder = SystemServiceHelper.getSystemService("input") ?: return false
            val wrapped = ShizukuBinderWrapper(binder)
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val svc = asInterface.invoke(null, wrapped) ?: return false
            service = svc
            val intType = Int::class.javaPrimitiveType
            inject = try {
                injectArgs = 2
                svc.javaClass.getMethod("injectInputEvent", InputEvent::class.java, intType)
            } catch (e: NoSuchMethodException) {
                injectArgs = 3
                svc.javaClass.getMethod("injectInputEventToTarget", InputEvent::class.java, intType, intType)
            }
            return true
        } catch (e: Throwable) {
            return false
        }
    }

    fun shutdown() {
        cancelAll()
        bg.post { thread.quitSafely() }
    }

    // ── TouchInjector ───────────────────────────────────────────────────────

    override fun down(key: Any, x: Float, y: Float, restoreFromStart: Boolean) {
        held.add(key)
        bg.post { doDown(key, x, y) }
    }

    override fun move(key: Any, x: Float, y: Float, timeMs: Long) {
        if (!held.contains(key)) return
        bg.post { doMove(key, x, y, timeMs) }
    }

    override fun up(key: Any) {
        if (!held.remove(key)) return
        bg.post { doUp(key) }
    }

    override fun isDown(key: Any): Boolean = held.contains(key)

    override fun tap(x: Float, y: Float) {
        val key = "tap#" + (++tapCounter)
        bg.post { doDown(key, x, y) }
        bg.postDelayed({ doUp(key) }, 20L)
    }

    override fun cancelAll() {
        held.clear()
        bg.post {
            val keys = pts.keys.toList()
            keys.forEach { doUp(it) }
        }
    }

    // ── Injection thread ────────────────────────────────────────────────────

    private fun flushPending() {
        if (dirty) {
            bg.removeCallbacks(flushRunnable)
            flushMove()
        }
    }

    private fun flushMove() {
        dirty = false
        if (pts.isEmpty()) return
        send(MotionEvent.ACTION_MOVE, pendingTime)
        lastMoveSent = SystemClock.uptimeMillis()
    }

    private fun doDown(key: Any, x: Float, y: Float) {
        if (pts.containsKey(key) || pts.size >= 10) return
        flushPending()
        val first = pts.isEmpty()
        if (first) downTime = SystemClock.uptimeMillis()
        val used = pts.values.map { it.id }.toSet()
        val id = (0..9).first { it !in used }
        pts[key] = Pt(id, x, y)
        val action = if (first) {
            MotionEvent.ACTION_DOWN
        } else {
            MotionEvent.ACTION_POINTER_DOWN or ((pts.size - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        send(action)
    }

    private fun doMove(key: Any, x: Float, y: Float, timeMs: Long) {
        val p = pts[key] ?: return
        if (p.x == x && p.y == y) return
        p.x = x
        p.y = y
        if (timeMs > pendingTime) pendingTime = timeMs
        if (!dirty) {
            dirty = true
            val wait = (MIN_MOVE_GAP_MS - (SystemClock.uptimeMillis() - lastMoveSent)).coerceAtLeast(0L)
            bg.postDelayed(flushRunnable, wait)
        }
    }

    private fun doUp(key: Any) {
        if (!pts.containsKey(key)) return
        flushPending()
        val index = pts.keys.indexOf(key)
        val action = if (pts.size == 1) {
            MotionEvent.ACTION_UP
        } else {
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        send(action)
        pts.remove(key)
    }

    private fun send(action: Int, timeMs: Long = 0L) {
        val method = inject ?: return
        val svc = service ?: return
        val list = pts.values.toList()
        val n = list.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().also {
                it.id = list[i].id
                it.toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().also {
                it.x = list[i].x
                it.y = list[i].y
                it.pressure = 1f
                it.size = 1f
            }
        }
        // Keep the cadence of the real touch (its own timestamp) when we have it; never go
        // backwards and never before the gesture's down time.
        val uptime = SystemClock.uptimeMillis()
        var now = if (timeMs in 1..uptime) timeMs else uptime
        if (now < lastEventTime) now = lastEventTime
        if (now < downTime) now = downTime
        lastEventTime = now
        val event = MotionEvent.obtain(
            downTime, now, action, n, props, coords,
            0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            if (injectArgs == 2) method.invoke(svc, event, 0) else method.invoke(svc, event, 0, -1)
        } catch (_: Throwable) {
        } finally {
            event.recycle()
        }
    }
}
