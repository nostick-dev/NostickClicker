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

    private class Pt(val id: Int, var x: Float, var y: Float)

    private val thread = HandlerThread("nostick-inject").also { it.start() }
    private val bg = Handler(thread.looper)

    // Main-thread side: which keys the app currently considers pressed.
    private val held = HashSet<Any>()

    // Injection-thread side.
    private val pts = LinkedHashMap<Any, Pt>()
    private var downTime = 0L
    private var tapCounter = 0

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

    override fun down(key: Any, x: Float, y: Float) {
        held.add(key)
        bg.post { doDown(key, x, y) }
    }

    override fun move(key: Any, x: Float, y: Float) {
        if (!held.contains(key)) return
        bg.post { doMove(key, x, y) }
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

    private fun doDown(key: Any, x: Float, y: Float) {
        if (pts.containsKey(key) || pts.size >= 10) return
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

    private fun doMove(key: Any, x: Float, y: Float) {
        val p = pts[key] ?: return
        if (p.x == x && p.y == y) return
        p.x = x
        p.y = y
        send(MotionEvent.ACTION_MOVE)
    }

    private fun doUp(key: Any) {
        if (!pts.containsKey(key)) return
        val index = pts.keys.indexOf(key)
        val action = if (pts.size == 1) {
            MotionEvent.ACTION_UP
        } else {
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        send(action)
        pts.remove(key)
    }

    private fun send(action: Int) {
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
        val now = SystemClock.uptimeMillis()
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
