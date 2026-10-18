package com.example.mangaromaji

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure protocol code: tested without Android or the OCR engine. */
object FrameProtocol {
    const val COLS = 64
    const val ROWS = 112
    const val WINDOW_ALPHA = 0.78f
    fun x(index: Int, width: Int) = ((index % COLS + 0.5f) * width / COLS).toInt().coerceIn(0, width - 1)
    fun y(index: Int, height: Int) = ((index / COLS + 0.5f) * height / ROWS).toInt().coerceIn(0, height - 1)
    fun luminance(pixel: Int) = (((pixel ushr 16) and 255) * 3 + ((pixel ushr 8) and 255) * 6 + (pixel and 255)) / 10

    // Recover the source under our translucent cards, instead of masking it out.
    // This allows even scrolling confined to a caption-covered region to be detected.
    fun sourceLuminance(captured: Int, overlay: Int): Int {
        val alpha = (overlay ushr 24) / 255f * WINDOW_ALPHA
        return ((luminance(captured) - alpha * luminance(overlay)) / (1f - alpha)).roundToInt().coerceIn(0, 255)
    }

    fun changed(before: IntArray?, after: IntArray): Boolean {
        if (before == null || before.size != after.size) return true
        var count = 0
        var valid = 0
        val tiles = IntArray(COLS / 8 * ROWS / 8)
        for (i in after.indices) {
            if (after[i] < 0 || before[i] < 0) continue
            valid++
            if (abs(before[i] - after[i]) > 22) {
                count++
                if (after.size == COLS * ROWS) {
                    val tile = (i / COLS / 8) * (COLS / 8) + i % COLS / 8
                    if (++tiles[tile] >= 6) return true
                }
            }
        }
        return count >= maxOf(3, valid / 150)
    }
}

class PageGate(private val settleMs: Long = 350) {
    var generation = 0L; private set
    private var anchor: IntArray? = null
    private var changedAt = 0L
    private var dirty = true
    fun invalidate() { generation++; anchor = null; dirty = true }
    fun observe(signature: IntArray, now: Long): Boolean {
        if (!FrameProtocol.changed(anchor, signature)) return false
        generation++; anchor = signature.copyOf(); changedAt = now; dirty = true
        return true
    }
    fun ticket(now: Long): Long? = if (dirty && anchor != null && now - changedAt >= settleMs) generation else null
    fun complete(ticket: Long): Boolean {
        if (ticket != generation || !dirty) return false
        dirty = false
        return true
    }
    fun current(ticket: Long) = generation == ticket
}

object RgbaPlane {
    /** Bulk-copy tight RGBA rows, skipping padding without reading past the final pixel. */
    fun pack(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
             left: Int, top: Int, output: ByteBuffer) {
        require(width > 0 && height > 0 && pixelStride >= 4 && left >= 0 && top >= 0)
        require(rowStride >= (left + width - 1L) * pixelStride + 4)
        val start = buffer.position()
        val end = start.toLong() + (top + height - 1L) * rowStride + (left + width - 1L) * pixelStride + 4
        require(end <= buffer.limit() && width.toLong()*height*4 <= output.capacity()) { "Truncated RGBA frame" }
        val input = buffer.duplicate()
        output.clear()
        for(y in 0 until height) {
            val at = start + (top+y)*rowStride + left*pixelStride
            if(pixelStride == 4) {
                input.limit(buffer.limit()); input.position(at); input.limit(at+width*4)
                output.put(input)
            } else {
                for(x in 0 until width) repeat(4) { output.put(buffer.get(at+x*pixelStride+it)) }
            }
        }
        output.flip()
    }

    /** Never read trailing row padding: vendors may omit it from the buffer limit. */
    fun copy(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
             left: Int = 0, top: Int = 0, output: IntArray = IntArray(width * height)): IntArray {
        require(width > 0 && height > 0 && pixelStride >= 4 && left >= 0 && top >= 0)
        require(rowStride >= (left + width - 1) * pixelStride + 4)
        val start = buffer.position()
        val end = start.toLong() + (top + height - 1L) * rowStride + (left + width - 1L) * pixelStride + 4
        require(end <= buffer.limit() && output.size >= width * height) { "Truncated RGBA plane" }
        var dest = 0
        for (y in 0 until height) {
            var offset = start + (top + y) * rowStride + left * pixelStride
            repeat(width) {
                val r = buffer.get(offset).toInt() and 255
                val g = buffer.get(offset + 1).toInt() and 255
                val b = buffer.get(offset + 2).toInt() and 255
                output[dest++] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                offset += pixelStride
            }
        }
        return output
    }
}

object CaptureMarker {
    const val CELL = 4
    const val WIDTH = 64
    const val HEIGHT = 8
    fun color(revision: Int, bit: Int, second: Boolean): Int {
        val one = ((revision ushr bit) and 1) == 1
        return if (one xor second) 0xffff00ff.toInt() else 0xff00ff00.toInt()
    }
    fun decode(read: (Int, Int) -> Int): Int? {
        var revision = 0
        for (bit in 0 until 16) {
            val x = bit % 8 * CELL * 2
            val y = bit / 8 * CELL + CELL / 2
            val a = read(x + CELL / 2, y)
            val b = read(x + CELL + CELL / 2, y)
            fun contrast(p: Int) = ((p ushr 16) and 255) - ((p ushr 8) and 255)
            val delta = contrast(a) - contrast(b)
            if (abs(delta) < 140) return null
            if (delta > 0) revision = revision or (1 shl bit)
        }
        return revision
    }
}
