package com.cometbot

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.hypot

/**
 * Foreground service: screen capture + detector + tap dispatch.
 * The floating bubble is now owned by TapService (TYPE_ACCESSIBILITY_OVERLAY)
 * so it always shows over FLAG_SECURE banking apps.
 */
class BotService : Service() {

    private lateinit var wm: WindowManager
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

        // Ask TapService to show the bubble (TYPE_ACCESSIBILITY_OVERLAY)
        val svc = TapService.instance
        if (svc != null) {
            svc.showBubble(
                onTap = {
                    if (TapService.instance == null) {
                        android.widget.Toast.makeText(this,
                            "Enable Comet Bot in Accessibility settings first",
                            android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        active = !active
                        if (active) tracks.clear()
                        svc.updateBubble(active, taps)
                    }
                },
                onLongPress = { shutdown() }
            )
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("bot", "Comet Bot", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, BotService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
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
        scaleX = realW.toFloat() / capW
        scaleY = realH.toFloat() / capH

        thread = HandlerThread("cap").also { it.start() }
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay(
            "cometbot", capW, capH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!active) return@setOnImageAvailableListener
                val pl = img.planes[0]
                val bb = pl.buffer
                // Skip all-black frames (FLAG_SECURE screen — no point tapping)
                if (isBlackFrame(bb)) return@setOnImageAvailableListener
                if (buf.size != bb.remaining()) buf = ByteArray(bb.remaining())
                bb.get(buf)
                process(buf, pl.rowStride)
            } finally { img.close() }
        }, Handler(thread!!.looper))
    }

    private fun isBlackFrame(bb: java.nio.ByteBuffer): Boolean {
        val saved = bb.position()
        val step = maxOf(4, bb.remaining() / 200)
        var nonBlack = 0
        var pos = saved
        while (pos + 2 < bb.limit()) {
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
            val ty = c.hy - 0.08f * c.h + tr.vy.coerceIn(0f, 2500f) * 0.09f
            val safe = res.skulls.none { hypot(it.cx - c.hx, it.cy - ty) < 1.25f * res.skullR }
            if (!safe) continue
            tr.lastTap = now
            out.add(floatArrayOf(c.hx * scaleX, ty * scaleY))
        }
        if (out.isNotEmpty()) {
            val svc = TapService.instance
            if (svc != null && svc.tap(out)) {
                taps += out.size
                val t = taps
                ui.post { TapService.instance?.updateBubble(active, t) }
            }
        }
    }

    private fun shutdown() {
        active = false
        TapService.instance?.destroyBubble()
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }
}
