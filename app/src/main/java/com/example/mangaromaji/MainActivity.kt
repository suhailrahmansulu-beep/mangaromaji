package com.example.mangaromaji

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra("code", r.resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                finish()
            } else {
                Toast.makeText(this, "Screen capture was not allowed.", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askProjection() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Manga Romaji"
            textSize = 28f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Tap Start, allow the overlay, then pick \"Entire screen\" when Android asks.\n\n" +
                "Open your manga. Tap the floating button to turn it on. " +
                "Scroll as usual: when the page stops, Japanese turns into romaji.\n\n" +
                "Long-press the button to quit."
            textSize = 16f
            setPadding(0, pad, 0, pad)
        })
        root.addView(Button(this).apply {
            text = "Start"
            setOnClickListener { start() }
        })
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\", then tap Start again.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else askProjection()
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
