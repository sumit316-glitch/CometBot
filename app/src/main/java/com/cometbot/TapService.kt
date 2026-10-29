package com.cometbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/** Injects the taps. Must be enabled once in Settings > Accessibility. */
class TapService : AccessibilityService() {

    companion object {
        @Volatile var instance: TapService? = null
    }

    @Volatile private var busyUntil = 0L

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    /** Taps all points at (almost) the same moment. Returns false if a previous gesture is still running. */
    fun tap(points: List<FloatArray>): Boolean {
        if (points.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        if (now < busyUntil) return false
        val b = GestureDescription.Builder()
        for ((n, p) in points.take(9).withIndex()) {
            val path = Path().apply { moveTo(p[0], p[1]) }
            b.addStroke(GestureDescription.StrokeDescription(path, n * 8L, 20L))
        }
        busyUntil = now + 250 // safety timeout, normally cleared by callback
        val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { busyUntil = 0 }
            override fun onCancelled(g: GestureDescription?) { busyUntil = 0 }
        }, null)
        if (!ok) busyUntil = 0
        return ok
    }
}
