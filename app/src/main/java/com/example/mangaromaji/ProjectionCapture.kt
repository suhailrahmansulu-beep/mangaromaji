package com.example.mangaromaji

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer

/** One projection, one VirtualDisplay. Only this thread ever acquires/closes Images. */
class ProjectionCapture(
    private val projection: MediaProjection,
    private val density: Int,
    private val deliver: (Bitmap) -> Unit,
    private val error: (String, Exception) -> Unit
) {
    private val thread = HandlerThread("MangaRomajiPreview-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var scheduled = false
    private var lastCopy = 0L
    private var packed = ByteBuffer.allocateDirect(0)
    @Volatile var received = 0L; private set
    @Volatile var copied = 0L; private set
    @Volatile var lastFrameAt = 0L; private set
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set

    fun start(w: Int, h: Int) = handler.post {
        if (closed.get()) return@post
        try {
            installReader(w, h)
            display = projection.createVirtualDisplay("MangaRomajiPreview", w, h, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        } catch (e: Exception) { error("CAPTURE_START_FAILED", e) }
    }

    fun resize(w: Int, h: Int) = handler.post {
        if (closed.get() || w <= 0 || h <= 0 || (width == w && height == h)) return@post
        try {
            // Android 14 consent cannot create a second VirtualDisplay.
            val old = reader
            old?.setOnImageAvailableListener(null, null)
            installReader(w, h)
            display?.resize(w, h, density)
            display?.surface = reader!!.surface
            old?.close()
        } catch (e: Exception) { error("CAPTURE_RESIZE_FAILED", e) }
    }

    private fun installReader(w: Int, h: Int) {
        handler.removeCallbacks(consume)
        scheduled = false
        width = w; height = h
        packed = ByteBuffer.allocateDirect(w * h * 4)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also { input ->
            input.setOnImageAvailableListener({
                if (closed.get() || input !== reader || scheduled) return@setOnImageAvailableListener
                scheduled = true
                // Coalesce callbacks, including the trailing frame of a scroll. Never
                // drain a frame and then depend on a static producer emitting another.
                handler.postDelayed(consume, (100 - (SystemClock.elapsedRealtime() - lastCopy)).coerceAtLeast(0))
            }, handler)
        }
    }

    private val consume = object : Runnable {
        override fun run() {
            scheduled = false
            if (closed.get()) return
            try {
                val image = reader?.acquireLatestImage() ?: return // queue empty is normal
                val bitmap = image.use {
                    received++; lastFrameAt = SystemClock.elapsedRealtime()
                    val crop = it.cropRect
                    val plane = it.planes.first()
                    RgbaPlane.pack(plane.buffer, crop.width(), crop.height(), plane.rowStride,
                        plane.pixelStride, crop.left, crop.top, packed)
                    Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888).also { bitmap ->
                        try { bitmap.copyPixelsFromBuffer(packed) }
                        catch(e: Exception) { bitmap.recycle(); throw e }
                    }
                }
                lastCopy = SystemClock.elapsedRealtime(); copied++
                if (closed.get()) bitmap.recycle() else deliver(bitmap)
            } catch (e: Exception) { error("FRAME_DECODE_FAILED", e) }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            handler.removeCallbacksAndMessages(null)
            reader?.setOnImageAvailableListener(null, null)
            display?.release(); display = null
            reader?.close(); reader = null
            packed = ByteBuffer.allocateDirect(0)
            thread.quitSafely()
        }
    }
}
