package com.cometbot

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.TextView
import kotlin.math.hypot

/**
 * Foreground service: floating bubble + screen capture + detector + tap dispatch.
 * Bubble: tap = start/pause, drag = move, long-press = quit.
 *
 * Fixes:
 *  1. FLAG_NOT_TOUCH_MODAL added so banking app's FLAG_SECURE doesn't eat our bubble touch.
 *  2. Bubble re-added on WindowManager if it was removed by a secure window transition.
 *  3. Screen-capture stops (active=false) when no frames arrive from a FLAG_SECURE window,
 *     but the bubble stays visible so you can still tap ON/OFF.
 */
class BotService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val ui = Handler(Looper.getMainLooper())

    private val detector = Detector()
    @Volatile private var active = false
    @Volatile private var taps = 0
    private var buf = ByteArray(0)
    private var capW = 480
    private var capH = 1040
    private var scaleX = 1f
    private var scaleY = 1f

    // tracks if bubble was forcibly removed by system (secure window, etc.)
    private var bubbleAttached = false

    // watchdog: re-attach bubble if it was removed by the system
    private val bubbleWatchdog = object : Runnable {
        override fun run() {
            reattachBubbleIfNeeded()
            ui.postDelayed(this, 1000)
        }
    }

    private class Track(var x: Float, var y: Float, var vy: Float, var t: Long, var lastTap: Long)
    private val tracks = ArrayList<Track>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { shutdown(); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundNotif()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ui.post { shutdown() } }
        }, ui)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startCapture()
        showBubble()
        ui.postDelayed(bubbleWatchdog, 1000)
        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "Comet Bot", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, BotService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "bot")
            .setContentTitle("Comet Bot is running")
            .setContentText("Tap the floating bubble to start / pause")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Quit", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun startCapture() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        val realW = dm.widthPixels; val realH = dm.heightPixels
        capW = 480
        capH = (480f * realH / realW).toInt() and 0x7FFFFFFE
        scaleX = realW.toFloat() / capW; scaleY = realH.toFloat() / capH
        thread = HandlerThread("cap").also { it.start() }
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay("cometbot", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!active) return@setOnImageAvailableListener
                val pl = img.planes[0]
                val bb = pl.buffer
                // A FLAG_SECURE screen gives a black/empty frame. Detect it: all bytes ~0.
                // If so, skip processing (tapping would be pointless, positions are wrong).
                val isSecure = isBlackFrame(bb)
                if (isSecure) return@setOnImageAvailableListener
                if (buf.size != bb.remaining()) buf = ByteArray(bb.remaining())
                bb.get(buf)
                process(buf, pl.rowStride)
            } finally { img.close() }
        }, Handler(thread!!.looper))
    }

    /** Heuristic: a FLAG_SECURE frame is all-black (or very nearly). Sample 200 pixels. */
    private fun isBlackFrame(bb: java.nio.ByteBuffer): Boolean {
        val saved = bb.position()
        val step = maxOf(1, bb.remaining() / 200)
        var nonBlack = 0
        var pos = saved
        while (pos < bb.limit()) {
            val r = bb.get(pos).toInt() and 0xFF
            val g = bb.get(pos + 1).toInt() and 0xFF
            val b = bb.get(pos + 2).toInt() and 0xFF
            if (r + g + b > 30) { nonBlack++; if (nonBlack > 10) break }
            pos += step
        }
        bb.position(saved)
        return nonBlack <= 10
    }

    private fun process(px: ByteArray, stride: Int) {
        val now = SystemClock.uptimeMillis()
        val res = detector.detect(px, stride, capW, capH)
        // drop stale tracks
        tracks.removeAll { now - it.t > 300 }
        val out = ArrayList<FloatArray>()
        for (c in res.comets) {
            var tr = tracks.minByOrNull { hypot(it.x - c.hx, it.y - c.hy) }
            if (tr != null && hypot(tr.x - c.hx, tr.y - c.hy) > 0.25f * capW) tr = null
            if (tr == null) {
                tr = Track(c.hx, c.hy, 0f, now, 0L); tracks.add(tr)
            } else {
                val dt = (now - tr.t) / 1000f
                if (dt > 0.005f) {
                    val v = (c.hy - tr.y) / dt
                    tr.vy = if (tr.vy == 0f) v else 0.5f * tr.vy + 0.5f * v
                }
                tr.x = c.hx; tr.y = c.hy; tr.t = now
            }
            if (now - tr.lastTap < 220) continue
            // aim slightly up the head, plus lead for capture+gesture latency
            val ty = c.hy - 0.08f * c.h + tr.vy.coerceIn(0f, 2500f) * 0.09f
            // never tap near a skull
            val safe = res.skulls.none { hypot(it.cx - c.hx, it.cy - ty) < 1.25f * res.skullR }
            if (!safe) continue
            tr.lastTap = now
            out.add(floatArrayOf(c.hx * scaleX, ty * scaleY))
        }
        if (out.isNotEmpty()) {
            val svc = TapService.instance
            if (svc != null && svc.tap(out)) { taps += out.size; ui.post { refreshBubble() } }
        }
    }

    // ---------------- floating bubble ----------------
    private fun refreshBubble() {
        val b = bubble ?: return
        b.text = if (active) "ON\n$taps" else "OFF"
        (b.background as GradientDrawable).setColor(if (active) 0xCC2E7D32.toInt() else 0xCC455A64.toInt())
    }

    private fun makeLp(size: Int): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_TOUCH_MODAL: lets touches outside the bubble pass through to the app
            // FLAG_NOT_FOCUSABLE:   bubble never steals keyboard focus
            // FLAG_LAYOUT_NO_LIMITS: can be dragged to screen edges
            // FLAG_LAYOUT_IN_SCREEN: always in the real screen rect, not behind nav bars
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size
            y = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        }
        return lp
    }

    private fun showBubble() {
        val size = (62 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 13f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        bubble = tv
        val lp = makeLp(size)
        bubbleLp = lp
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false; var downT = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moved = false; downT = e.eventTime }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.rawX - dx, e.rawY - dy) > 12) moved = true
                    if (moved) {
                        lp.x = sx + (e.rawX - dx).toInt()
                        lp.y = sy + (e.rawY - dy).toInt()
                        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (e.eventTime - downT > 800) shutdown()
                    else {
                        if (TapService.instance == null) {
                            android.widget.Toast.makeText(this, "Enable Comet Bot in Accessibility settings first", android.widget.Toast.LENGTH_LONG).show()
                        } else { active = !active; if (active) tracks.clear(); refreshBubble() }
                    }
                }
            }
            true
        }
        try {
            wm.addView(tv, lp)
            bubbleAttached = true
        } catch (e: Exception) {
            bubbleAttached = false
        }
        refreshBubble()
    }

    /**
     * Some banking apps use FLAG_SECURE which can cause the system to forcibly remove
     * TYPE_APPLICATION_OVERLAY windows. Re-attach the bubble if it got detached.
     */
    private fun reattachBubbleIfNeeded() {
        val tv = bubble ?: return
        val lp = bubbleLp ?: return
        if (!bubbleAttached) {
            try {
                wm.addView(tv, lp)
                bubbleAttached = true
            } catch (_: Exception) {}
            return
        }
        // Check if view is still attached (has a parent)
        if (tv.windowToken == null) {
            bubbleAttached = false
            try {
                wm.removeViewImmediate(tv)
            } catch (_: Exception) {}
            try {
                wm.addView(tv, lp)
                bubbleAttached = true
            } catch (_: Exception) {}
        }
    }

    private fun shutdown() {
        active = false
        ui.removeCallbacks(bubbleWatchdog)
        try { bubble?.let { wm.removeView(it) } } catch (_: Exception) {}
        bubble = null
        bubbleAttached = false
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { if (bubble != null) shutdown(); super.onDestroy() }
}
