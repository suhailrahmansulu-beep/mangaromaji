package com.example.mangaromaji

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : ComponentActivity() {

    private val BG = Color.parseColor("#0E1117")
    private val CARD = Color.parseColor("#171C26")
    private val LINE = Color.parseColor("#262D3B")
    private val TEXT = Color.parseColor("#EEF1F6")
    private val MUTED = Color.parseColor("#8C96A8")
    private val ACCENT = Color.parseColor("#E8453C")
    private val OK = Color.parseColor("#3DBE7A")

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var jaReady: Boolean? = null

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleBars()
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(content)
        })
    }

    @Suppress("DEPRECATION")
    private fun styleBars() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.postDelayed({ render() }, 1200)   // pick up the service finishing its start-up
        RemoteModelManager.getInstance()
            .isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build())
            .addOnSuccessListener { if (jaReady != it) { jaReady = it; render() } }
    }

    override fun onPause() { super.onPause(); handler.removeCallbacksAndMessages(null) }

    // ---------------- screen ----------------

    private fun render() {
        content.removeAllViews()

        // header
        content.addView(tv("Romaji Reader", 30f, TEXT, bold = true))
        content.addView(tv("Tap once. Read any Japanese on your screen.", 15f, MUTED).also { pad(it, bottom = 18) })

        // status
        val running = OverlayService.running
        content.addView(card {
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(View(this).apply { background = oval(if (running) OK else MUTED) }, LinearLayout.LayoutParams(dp(12), dp(12)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            col.addView(tv(if (running) "Reader is on" else "Reader is off", 18f, TEXT, bold = true))
            col.addView(tv(if (running) "Tap the floating A to translate." else "Start it BEFORE you open your game.", 13f, MUTED))
            top.addView(col)
            it.addView(top)
            it.addView(button(if (running) "Stop reader" else "Start reader", primary = !running) { toggleReader(running) }
                .also { b -> pad(b, top = 14) })
        })

        // setup
        content.addView(card {
            it.addView(tv("Setup", 12f, MUTED, bold = true).also { t -> pad(t, bottom = 2) })
            val overlayOk = Settings.canDrawOverlays(this)
            it.addView(row("Display over other apps", "Lets the A button float above your game",
                if (overlayOk) status("Allowed", true) else button("Allow", small = true) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }))
            if (Build.VERSION.SDK_INT >= 33) {
                val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                it.addView(row("Notifications", "Shows the Stop button while it runs",
                    if (ok) status("Allowed", true) else button("Allow", small = true) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }))
            }
            it.addView(row("Quick Settings tile", "Start and stop from the pull-down panel",
                button("Add", small = true) { addTile() }))
        })

        // battery
        content.addView(card {
            val pm = getSystemService(PowerManager::class.java)
            val free = pm.isIgnoringBatteryOptimizations(packageName)
            val sw = Switch(this).apply {
                isChecked = free
                setOnClickListener { isChecked = free; openBatterySettings() }
            }
            it.addView(tv("Stay alive in games", 12f, MUTED, bold = true).also { t -> pad(t, bottom = 2) })
            it.addView(row("Don't optimize battery", if (free) "Good. Android won't close it to save power." else
                "On: Android may close the reader while you play.", sw))
            it.addView(tv("In the list that opens, find Romaji Reader and choose Don't optimize (or Unrestricted). " +
                "On Realme/Oppo also allow Auto-launch and Background activity in App info.", 12f, MUTED).also { t -> pad(t, top = 4) })
            it.addView(button("Open app settings", small = true) { openAppSettings() }.also { b -> pad(b, top = 10) })
        })

        // display
        content.addView(card {
            it.addView(tv("Display", 12f, MUTED, bold = true).also { t -> pad(t, bottom = 8) })
            val mode = Prefs.mode(this)
            it.addView(segmented(listOf("Romaji", "Romaji + English", "English"), mode) { i ->
                Prefs.setMode(this, i); render()
            })
            if (mode != 0) {
                if (jaReady == true) {
                    it.addView(tv("English is ready (works offline).", 12f, OK).also { t -> pad(t, top = 8) })
                } else {
                    it.addView(tv("English needs a one-time download (about 30 MB). Translations are machine-made and can be rough.", 12f, MUTED)
                        .also { t -> pad(t, top = 8) })
                    it.addView(button("Download English", small = true) { downloadModel() }.also { b -> pad(b, top = 8) })
                }
            }
            val mac = Switch(this).apply {
                isChecked = Prefs.macrons(this@MainActivity)
                setOnCheckedChangeListener { _, v -> Prefs.setMacrons(this@MainActivity, v); render() }
            }
            it.addView(row("Long vowel marks", "Example: " + if (Prefs.macrons(this)) "Tōkyō" else "Tokyo", mac).also { r -> pad(r, top = 6) })
            it.addView(tv("Hide romaji after", 15f, TEXT).also { t -> pad(t, top = 6, bottom = 8) })
            val opts = listOf("6s" to 6, "12s" to 12, "20s" to 20, "Never" to 0)
            val sel = opts.indexOfFirst { o -> o.second == Prefs.hideSec(this) }.coerceAtLeast(0)
            it.addView(segmented(opts.map { o -> o.first }, sel) { i -> Prefs.setHideSec(this, opts[i].second); render() })
        })

        // how to
        content.addView(card {
            it.addView(tv("How to use", 12f, MUTED, bold = true).also { t -> pad(t, bottom = 6) })
            it.addView(tv("1. Allow the permissions above.\n2. Tap Start reader and choose \"Entire screen\".\n" +
                "3. Open your game or app. Tap the floating A.\n4. Tap A again to clear. Long-press A to quit.\n\n" +
                "Everything is read on your phone. Your screen is never uploaded.", 14f, TEXT))
        })

        // about
        content.addView(card {
            it.addView(tv("About", 12f, MUTED, bold = true).also { t -> pad(t, bottom = 6) })
            val stop = Prefs.lastStop(this)
            if (stop.isNotEmpty()) it.addView(tv("Last stop: $stop", 13f, TEXT).also { t -> pad(t, bottom = 8) })
            it.addView(tv("Japanese readings: Kuromoji + IPAdic dictionary (Apache License 2.0). " +
                "Text recognition and translation: Google ML Kit, running on your device.", 12f, MUTED))
        })
    }

    // ---------------- actions ----------------

    private fun toggleReader(running: Boolean) {
        if (running) { OverlayService.instance?.stopBy("Stopped from the app"); handler.postDelayed({ render() }, 400); return }
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\" first.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startActivity(Intent(this, CaptureActivity::class.java))
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, RomajiTile::class.java), "Romaji",
                Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor
            ) { }
        } else toast("Pull down Quick Settings, tap the edit icon, and drag \"Romaji\" in.")
    }

    private fun openBatterySettings() {
        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (e: Exception) { openAppSettings() }
    }

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun downloadModel() {
        toast("Downloading English. Keep Wi-Fi on.")
        val t = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH).build()
        )
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { jaReady = true; toast("English is ready."); t.close(); render() }
            .addOnFailureListener { toast("Download failed. Check your connection."); t.close() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------------- tiny UI kit ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun pad(v: View, top: Int = 0, bottom: Int = 0) {
        v.setPadding(v.paddingLeft, v.paddingTop + dp(top), v.paddingRight, v.paddingBottom + dp(bottom))
    }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(build: (LinearLayout) -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        build(c)
        c.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(12) }
        return c
    }

    private fun row(title: String, sub: String?, trailing: View?): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, TEXT))
        if (sub != null) col.addView(tv(sub, 12f, MUTED))
        r.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { r.addView(it) }
        return r
    }

    private fun status(s: String, good: Boolean) = tv(s, 13f, if (good) OK else ACCENT, bold = true)

    private fun button(label: String, primary: Boolean = false, small: Boolean = false, onClick: () -> Unit) =
        TextView(this).apply {
            text = label; textSize = if (small) 13f else 16f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else TEXT)
            background = round(if (primary) ACCENT else LINE, if (small) 10 else 14)
            val h = if (small) 8 else 14; val w = if (small) 14 else 16
            setPadding(dp(w), dp(h), dp(w), dp(h))
            setOnClickListener { onClick() }
        }

    private fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(LINE, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        options.forEachIndexed { i, s ->
            val t = TextView(this).apply {
                text = s; textSize = 13f; gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (i == selected) Color.WHITE else MUTED)
                if (i == selected) background = round(ACCENT, 10)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { onPick(i) }
            }
            row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }
}
