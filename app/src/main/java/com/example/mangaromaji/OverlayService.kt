package com.example.mangaromaji

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var button: TextView? = null
    private var layer: BubbleLayer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var on = false
    private var W = 0
    private var H = 0
    private var density = 1f

    @Volatile private var romanizer: Romanizer? = null
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val code = i?.getIntExtra("code", 0) ?: 0
        val data: Intent? = i?.getParcelableExtra("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
        }

        val dm = DisplayMetrics()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        W = dm.widthPixels; H = dm.heightPixels; density = dm.density

        // Android 14+: create the virtual display exactly once per projection.
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        vdisplay = projection!!.createVirtualDisplay(
            "MangaRomaji", W, H, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )

        addLayer()
        addButton()
        // Load the dictionary in the background (a few seconds on first start).
        scope.launch { romanizer = Romanizer() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("mr", "Manga Romaji", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "mr")
            .setContentTitle("Manga Romaji is running")
            .setContentText("Long-press the floating button to quit")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ---------- overlay windows ----------

    private fun addLayer() {
        val v = BubbleLayer(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(v, lp)
        layer = v
    }

    private fun addButton() {
        val size = (52 * density).toInt()
        val b = TextView(this).apply {
            text = "A"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.9f
            background = circle(Color.GRAY)
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0; lp.y = (H * 0.3f).toInt()

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var downT = 0L
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    downT = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 * density || abs(dy) > 12 * density) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(b, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (System.currentTimeMillis() - downT > 700) stopSelf() else toggle()
                }
            }
            true
        }
        wm.addView(b, lp)
        button = b
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun toggle() {
        on = !on
        button?.background = circle(if (on) Color.rgb(216, 50, 42) else Color.GRAY)
        if (on) startLoop() else { loop?.cancel(); layer?.clear() }
    }

    // ---------- capture loop ----------

    private fun startLoop() {
        loop = scope.launch {
            var last: Bitmap? = null
            var sig: IntArray? = null
            var baseline: IntArray? = null
            var shown = false
            var stable = 0

            while (isActive) {
                delay(300)
                val fresh = grab()
                if (fresh != null) last = fresh
                val cur = last ?: continue
                val s = signature(cur)

                if (shown) {
                    // Overlay is up. Wait for the page to move.
                    if (baseline == null) { if (fresh != null) baseline = s; continue }
                    if (diff(s, baseline) > MOVE_THRESHOLD) {
                        withContext(Dispatchers.Main) { layer?.clear() }
                        shown = false; stable = 0; sig = s
                    }
                    continue
                }

                // Page is moving or just started: wait until it holds still.
                stable = if (sig != null && diff(s, sig) < STILL_THRESHOLD) stable + 1 else 0
                sig = s
                if (stable >= 2 && romanizer != null) {
                    val bubbles = try { recognize(cur) } catch (e: Exception) { emptyList() }
                    withContext(Dispatchers.Main) { layer?.setBubbles(bubbles) }
                    shown = true; baseline = null
                }
            }
        }
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val p = img.planes[0]
            val rowPadding = p.rowStride - p.pixelStride * W
            val full = Bitmap.createBitmap(W + rowPadding / p.pixelStride, H, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            return if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, W, H)
        } finally { img.close() }
    }

    private fun signature(b: Bitmap): IntArray {
        val n = 48
        return IntArray(n * n) { k ->
            val px = b.getPixel((k % n) * (b.width - 1) / (n - 1), (k / n) * (b.height - 1) / (n - 1))
            (Color.red(px) + Color.green(px) + Color.blue(px)) / 3
        }
    }

    private fun diff(a: IntArray, b: IntArray): Float {
        var t = 0L
        for (i in a.indices) t += abs(a[i] - b[i])
        return t.toFloat() / a.size
    }

    // ---------- OCR + romaji ----------

    private suspend fun recognize(bmp: Bitmap): List<BubbleLayer.Bubble> {
        val rom = romanizer ?: return emptyList()
        val res = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val out = ArrayList<BubbleLayer.Bubble>()
        for (block in res.textBlocks) {
            val box = block.boundingBox ?: continue
            val lines = block.lines
            val vertical = lines.isNotEmpty() && lines.all {
                val r = it.boundingBox; r != null && r.height() > r.width() * 1.3f
            }
            // Vertical Japanese reads right-to-left by column.
            val ordered = if (vertical) lines.sortedByDescending { it.boundingBox!!.centerX() } else lines
            val jp = ordered.joinToString("\n") { it.text }.replace(GAP, "")
            if (!JP.containsMatchIn(jp)) continue            // ignore Latin text (incl. our own overlay)
            val roma = rom.convert(jp)
            if (roma.isBlank()) continue

            val r = RectF(box)
            if (vertical) {                                   // tall narrow column: give romaji room to breathe
                val target = max(r.width(), min(r.height() * 0.8f, W * 0.45f))
                val cx = r.centerX(); r.left = cx - target / 2; r.right = cx + target / 2
            }
            r.inset(-6f, -6f)
            r.left = max(0f, r.left); r.top = max(0f, r.top)
            r.right = min(W.toFloat(), r.right); r.bottom = min(H.toFloat(), r.bottom)
            out.add(BubbleLayer.Bubble(r, roma))
        }
        return out
    }

    override fun onDestroy() {
        loop?.cancel(); scope.cancel()
        button?.let { runCatching { wm.removeView(it) } }
        layer?.let { runCatching { wm.removeView(it) } }
        vdisplay?.release(); reader?.close()
        projection?.stop()
        runCatching { recognizer.close() }
        super.onDestroy()
    }

    companion object {
        private const val MOVE_THRESHOLD = 7f   // avg brightness change that counts as "page moved"
        private const val STILL_THRESHOLD = 2f  // below this = page is holding still
        private val JP = Regex("[\u3040-\u30ff\u3400-\u9fff]")
        private val GAP = Regex("(?<=[^\\x00-\\x7F])\\s+(?=[^\\x00-\\x7F])")
    }
}
