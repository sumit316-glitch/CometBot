package com.cometbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlin.math.hypot

/**
 * Accessibility service: injects slashes AND hosts the floating bubble.
 * Uses TYPE_ACCESSIBILITY_OVERLAY so the bubble stays visible over secure banking apps.
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile var instance: TapService? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busyUntil = 0L

    // Bubble state
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private lateinit var wm: WindowManager

    // Callbacks set by BotService
    var onBubbleTap: (() -> Unit)? = null
    var onBubbleLongPress: (() -> Unit)? = null

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        ui.post { destroyBubble() }
        return super.onUnbind(intent)
    }

    // ── Gesture injection (Real Slashes) ──────────────────────────────────────

    /**
     * Slashes across the target comets.
     * Slicing games require actual motion (ACTION_MOVE) to register blade cuts.
     */
    fun slash(points: List<FloatArray>): Boolean {
        if (points.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        if (now < busyUntil) return false

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val slashDx = 45f * density
        val slashDy = 18f * density

        val b = GestureDescription.Builder()
        for ((n, p) in points.take(4).withIndex()) {
            val x = p[0]
            val y = p[1]
            // Diagonal slash path cutting directly across the comet
            val path = Path().apply {
                moveTo(x - slashDx, y + slashDy)
                lineTo(x + slashDx, y - slashDy)
            }
            b.addStroke(GestureDescription.StrokeDescription(path, n * 10L, 35L))
        }

        busyUntil = now + 70L // Short fallback timeout
        val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { busyUntil = 0L }
            override fun onCancelled(g: GestureDescription?) { busyUntil = 0L }
        }, null)
        if (!ok) busyUntil = 0L
        return ok
    }

    /** Backward-compatible alias */
    fun tap(points: List<FloatArray>): Boolean = slash(points)

    // ── Bubble management (called on main thread) ─────────────────────────────

    fun showBubble(onTap: () -> Unit, onLongPress: () -> Unit) {
        ui.post {
            if (bubble != null) return@post
            onBubbleTap = onTap
            onBubbleLongPress = onLongPress

            val density = resources.displayMetrics.density
            val size = (62 * density).toInt()

            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            }

            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = resources.displayMetrics.widthPixels - size
                y = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            }

            var dx = 0f; var dy = 0f; var sx = 0; var sy = 0
            var moved = false; var downT = 0L

            tv.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dx = e.rawX; dy = e.rawY
                        sx = lp.x; sy = lp.y
                        moved = false; downT = e.eventTime
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (hypot(e.rawX - dx, e.rawY - dy) > 12) moved = true
                        if (moved) {
                            lp.x = sx + (e.rawX - dx).toInt()
                            lp.y = sy + (e.rawY - dy).toInt()
                            try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) {
                        if (e.eventTime - downT > 800) onBubbleLongPress?.invoke()
                        else onBubbleTap?.invoke()
                    }
                }
                true
            }

            try {
                wm.addView(tv, lp)
                bubble = tv
                bubbleLp = lp
            } catch (e: Exception) {
                e.printStackTrace()
            }
            applyBubbleState(false, 0)
        }
    }

    fun updateBubble(active: Boolean, taps: Int) {
        ui.post { applyBubbleState(active, taps) }
    }

    fun destroyBubble() {
        ui.post {
            try { bubble?.let { wm.removeView(it) } } catch (_: Exception) {}
            bubble = null
            bubbleLp = null
            onBubbleTap = null
            onBubbleLongPress = null
        }
    }

    private fun applyBubbleState(active: Boolean, taps: Int) {
        val b = bubble ?: return
        b.text = if (active) "ON\n$taps" else "OFF"
        (b.background as GradientDrawable).setColor(
            if (active) 0xCC2E7D32.toInt() else 0xCC455A64.toInt()
        )
    }
}
