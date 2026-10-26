package com.example.mangaromaji

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
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

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(s: String, size: Float, bottom: Int = 0) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, 0, 0, bottom)
        }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; setOnClickListener { onClick() }
        }

        root.addView(text("Romaji Reader", 28f, pad / 2))
        root.addView(text(
            "A floating A button. Tap it once and the Japanese on your screen turns into romaji, right over the text.\n\n" +
            "Set up (do this BEFORE opening your game):\n" +
            "1. Allow overlay\n2. Add the Romaji tile to Quick Settings\n" +
            "3. Pull down Quick Settings and tap the Romaji tile, choose \"Entire screen\"\n" +
            "4. Open your game. Tap A to translate. Tap A again to clear (it also clears itself).\n\n" +
            "Turn it off with the tile again, or long-press the A.\n\n" +
            "Why before the game: Android shows a permission popup when it starts, and that popup pauses games.", 16f, pad))

        root.addView(button("1. Allow overlay") {
            if (Settings.canDrawOverlays(this)) toast("Overlay already allowed.")
            else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. Add Quick Settings tile") { addTile() })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, RomajiTile::class.java), "Romaji",
                Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor
            ) { }
        } else {
            toast("Pull down Quick Settings, tap the pencil/edit icon, and drag \"Romaji\" into your tiles.")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
