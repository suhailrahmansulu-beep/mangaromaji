package com.example.mangaromaji

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

/** Samples our actual drawing (including antialiasing) to undo it for motion sensing. */
class OverlaySample(view: BubbleLayer, frameWidth: Int, frameHeight: Int, private val ignored: List<Rect>) {
    val revision = view.revision
    private val location = IntArray(2).also { view.getLocationOnScreen(it) }
    private val markerX = location[0] + view.markerX
    private val markerY = location[1] + view.markerY
    private val marker = Rect(markerX - 2, markerY - 2,
        markerX + CaptureMarker.WIDTH + 2, markerY + CaptureMarker.HEIGHT + 2)
    private val drawing: IntArray
    init {
        val layer = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(layer))
            drawing = IntArray(FrameProtocol.COLS * FrameProtocol.ROWS) { i ->
                val x = FrameProtocol.x(i, frameWidth) - location[0]
                val y = FrameProtocol.y(i, frameHeight) - location[1]
                if (x in 0 until layer.width && y in 0 until layer.height) layer.getPixel(x, y) else 0
            }
        } finally { layer.recycle() }
    }
    fun matches(bitmap: Bitmap): Boolean {
        if (markerX < 0 || markerY < 0 || markerX + CaptureMarker.WIDTH > bitmap.width ||
            markerY + CaptureMarker.HEIGHT > bitmap.height) return false
        return CaptureMarker.decode { x, y -> bitmap.getPixel(markerX + x, markerY + y) } == revision
    }
    fun signature(bitmap: Bitmap): IntArray = IntArray(drawing.size) { i ->
        val x = FrameProtocol.x(i, bitmap.width)
        val y = FrameProtocol.y(i, bitmap.height)
        if (marker.contains(x, y) || ignored.any { it.contains(x, y) }) -1
        else FrameProtocol.sourceLuminance(bitmap.getPixel(x, y), drawing[i])
    }
}
