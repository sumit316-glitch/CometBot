package com.cometbot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        }
        fun tv(t: String, sz: Float = 15f) = TextView(this).apply {
            text = t; textSize = sz; setPadding(0, pad / 2, 0, pad / 2)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
        }

        root.addView(tv("Comet Bot", 26f))
        root.addView(tv("Taps the orange comets (pink tail, white head) automatically. Never taps skull meteors."))

        // Step 1: Accessibility (now the ONLY permission needed — no overlay needed)
        root.addView(tv("Step 1 – Enable 'Comet Bot' under Accessibility (performs taps AND shows the floating button)"))
        root.addView(btn("1. Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // Step 2: Start
        root.addView(tv("Step 2 – Start. Then open your game and tap the floating bubble: ON = playing, OFF = paused, long-press = quit."))
        root.addView(btn("2. Start floating button") { start() })

        status = tv("")
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val ac = TapService.instance != null
        status.text = "Accessibility service: ${if (ac) "✓ ON" else "✗ off — do Step 1 first"}"
    }

    private fun start() {
        if (TapService.instance == null) {
            toast("Do step 1 first: enable Comet Bot in Accessibility settings")
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), 42)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42 && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, BotService::class.java)
                    .putExtra("code", res)
                    .putExtra("data", data)
            )
            toast("Floating button started. Open your game now.")
            moveTaskToBack(true)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
