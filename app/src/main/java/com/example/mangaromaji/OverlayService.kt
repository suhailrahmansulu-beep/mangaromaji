package com.example.mangaromaji

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
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

/**
 * One floating "A" button. Tap it: grab ONE frame, read the Japanese on it, paint romaji
 * over the text. Tap again (or wait) and it disappears.
 *
 * Built so it never disturbs a game:
 *  - nothing here opens a screen or takes focus; the game stays in front the whole time
 *  - the romaji layer is touch-through
 *  - screen mirroring is switched OFF between taps (no background cost)
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    @Volatile private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var button: TextView? = null
    private var layer: BubbleLayer? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var W = 0
    @Volatile private var H = 0
    private var dpi = 0
    private var density = 1f
    @Volatile private var busy = false
    private var shown = false

    @Volatile private var romanizer: Romanizer? = null
    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val hideRunnable = Runnable { clearBubbles() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) onScreenChanged() }
    }

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
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
            }, main)
        }

        readMetrics()
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        // Android 14+: create the virtual display exactly once. Start with NO surface = mirroring off.
        vdisplay = projection!!.createVirtualDisplay(
            "RomajiReader", W, H, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, null
        )
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, main)

        addLayer()
        addButton()
        instance = this
        running = true
        TileService.requestListeningState(this, ComponentName(this, RomajiTile::class.java))

        // Load the dictionary now, so the first tap in-game is instant.
        scope.launch { romanizer = Romanizer() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("rr", "Romaji Reader", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "rr")
            .setContentTitle("Romaji Reader is ready")
            .setContentText("Tap the floating A to translate the screen")
            .setSmallIcon(R.drawable.ic_tile)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ---------- screen size / rotation ----------

    private fun readMetrics(): Boolean {
        val dm = DisplayMetrics()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        val changed = dm.widthPixels != W || dm.heightPixels != H
        W = dm.widthPixels; H = dm.heightPixels; dpi = dm.densityDpi; density = dm.density
        return changed
    }

    private fun onScreenChanged() {
        if (!readMetrics()) return
        clearBubbles()
        val old = reader
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        vdisplay?.resize(W, H, dpi)
        old?.close()
    }

    // ---------- overlay windows ----------

    private fun addLayer() {
        val v = BubbleLayer(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // never focusable, never touchable: the game keeps focus and keeps getting touches
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(v, lp)
        layer = v
    }

    private fun addButton() {
        val size = (46 * density).toInt()
        val b = TextView(this).apply {
            text = "A"; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; alpha = 0.85f
            background = circle(Color.argb(255, 70, 74, 84))
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0; lp.y = (H * 0.35f).toInt()

        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0; var moved = false; var downT = 0L
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y; moved = false
                    downT = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 * density || abs(dy) > 12 * density) moved = true
                    if (moved) { lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt(); wm.updateViewLayout(b, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (System.currentTimeMillis() - downT > 700) stopSelf() else onTap()
                }
            }
            true
        }
        wm.addView(b, lp)
        button = b
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun setState(color: Int, label: String) {
        button?.text = label
        button?.background = circle(color)
    }

    // ---------- one tap = one translation ----------

    private fun onTap() {
        if (busy) return
        if (shown) { clearBubbles(); return }
        if (romanizer == null) { setState(Color.rgb(230, 150, 30), "…"); main.postDelayed({ if (!shown) setState(IDLE, "A") }, 1500); return }
        busy = true
        setState(Color.rgb(230, 150, 30), "…")
        scope.launch {
            var bubbles: List<BubbleLayer.Bubble> = emptyList()
            try {
                val bmp = captureFrame()
                if (bmp != null) bubbles = recognize(bmp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    vdisplay?.surface = null                 // mirroring off again
                    busy = false
                    if (bubbles.isNotEmpty()) {
                        layer?.setBubbles(bubbles); shown = true
                        setState(Color.rgb(216, 50, 42), "A")
                        main.removeCallbacks(hideRunnable)
                        main.postDelayed(hideRunnable, SHOW_MS)
                    } else {
                        setState(IDLE, "A")
                    }
                }
            }
        }
    }

    private fun clearBubbles() {
        main.removeCallbacks(hideRunnable)
        layer?.clear(); shown = false
        setState(IDLE, "A")
    }

    /** Switch mirroring on just long enough to get one fresh frame. */
    private suspend fun captureFrame(): Bitmap? {
        val rd = reader ?: return null
        rd.acquireLatestImage()?.close()
        withContext(Dispatchers.Main) { vdisplay?.surface = rd.surface }
        var bmp: Bitmap? = null
        var tries = 0
        while (bmp == null && tries < 50) {
            delay(40)
            val img = rd.acquireLatestImage()
            if (img != null) { bmp = toBitmap(img); img.close() }
            tries++
        }
        withContext(Dispatchers.Main) { vdisplay?.surface = null }
        return bmp
    }

    private fun toBitmap(img: Image): Bitmap {
        val p = img.planes[0]
        val pad = p.rowStride - p.pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(p.buffer)
        return if (pad == 0) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
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
            val ordered = if (vertical) lines.sortedByDescending { it.boundingBox!!.centerX() }
                          else lines.sortedBy { it.boundingBox?.top ?: 0 }
            val jp = ordered.joinToString(" ") { it.text }.replace(GAP, "")
            if (!JP.containsMatchIn(jp)) continue
            val roma = rom.convert(jp)
            if (roma.isBlank()) continue

            val r = RectF(box)
            if (vertical) {
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
        scope.cancel()
        main.removeCallbacks(hideRunnable)
        if (::wm.isInitialized) {
            button?.let { runCatching { wm.removeView(it) } }
            layer?.let { runCatching { wm.removeView(it) } }
            runCatching { (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener) }
        }
        vdisplay?.release(); reader?.close()
        projection?.stop()
        runCatching { recognizer.close() }
        instance = null
        running = false
        TileService.requestListeningState(this, ComponentName(this, RomajiTile::class.java))
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.mangaromaji.STOP"
        @Volatile var running = false
        @Volatile var instance: OverlayService? = null
        private val IDLE = Color.argb(255, 70, 74, 84)
        private const val SHOW_MS = 12000L        // romaji auto-hides after this
        private val JP = Regex("[\u3040-\u30ff\u3400-\u9fff]")
        private val GAP = Regex("(?<=[^\\x00-\\x7F])\\s+(?=[^\\x00-\\x7F])")
    }
}
