package com.example.mangaromaji

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
    private val white = IntArray(FrameProtocol.COLS * FrameProtocol.ROWS) { 255 }
    private val black = IntArray(FrameProtocol.COLS * FrameProtocol.ROWS) { 0 }
    @Test fun staticProducerNeedsOnlyOneFrameAndDoesNotRescanOnTimer() {
        val gate = PageGate()
        gate.observe(white, 100)
        assertNull(gate.ticket(449))
        val ticket = gate.ticket(450)!!
        assertTrue(gate.complete(ticket))
        assertNull(gate.ticket(100000))
        assertFalse(gate.observe(white, 100001))
    }
    @Test fun scrollingRejectsInFlightOcrAndSettlesOnFinalFrame() {
        val gate = PageGate()
        gate.observe(white, 0)
        val old = gate.ticket(350)!!
        gate.observe(black, 400)
        assertFalse(gate.complete(old))
        assertNull(gate.ticket(749))
        assertTrue(gate.complete(gate.ticket(750)!!))
    }
    @Test fun returningToSamePageStillRejectsAnOldGeneration() {
        val gate = PageGate()
        gate.observe(white, 0)
        val old = gate.ticket(350)!!
        gate.observe(black, 400); gate.observe(white, 500)
        assertFalse(gate.complete(old))
    }
    @Test fun anOnlineUpgradeCanOnlyUseThePageWhoseOfflineCaptionsCompleted() {
        val gate = PageGate()
        gate.observe(white,0)
        val ticket = gate.ticket(350)!!
        assertTrue(gate.complete(ticket))
        assertTrue(gate.current(ticket))
        gate.observe(black,400)
        assertFalse(gate.current(ticket))
        gate.observe(white,500)
        assertFalse(gate.current(ticket))
    }
    @Test fun pauseResizeAndRestartInvalidateWork() {
        val gate = PageGate()
        gate.observe(white, 0)
        val old = gate.ticket(350)!!
        gate.invalidate()
        assertFalse(gate.complete(old)); assertNull(gate.ticket(9999))
        gate.observe(white, 10000)
        assertTrue(gate.complete(gate.ticket(10350)!!))
    }
    @Test fun subthresholdMovementsAccumulateAgainstAnchor() {
        val gate = PageGate()
        gate.observe(white, 0)
        assertFalse(gate.observe(IntArray(white.size) { 240 }, 50))
        assertTrue(gate.observe(IntArray(white.size) { 225 }, 100))
    }
    @Test fun localizedPageChangesAreNotLostInWholeScreenAverage() {
        val next = white.copyOf()
        repeat(6) { next[32 * FrameProtocol.COLS + 32 + it] = 0 }
        assertTrue(FrameProtocol.changed(white, next))
        assertFalse(FrameProtocol.changed(white, white.copyOf().apply { this[0] = 0 }))
    }
    @Test fun ignoredButtonAndMarkerDoNotTriggerMotion() {
        val before = white.copyOf().apply { repeat(100) { this[it] = -1 } }
        val after = before.copyOf().apply { repeat(100) { this[it] = 0 } }
        assertFalse(FrameProtocol.changed(before, after))
    }
    private fun compose(source: Int, layer: Int): Int {
        val a = (layer ushr 24) / 255f * FrameProtocol.WINDOW_ALPHA
        fun channel(shift: Int) = ((((source ushr shift) and 255) * (1-a) + ((layer ushr shift) and 255) * a).toInt()) shl shift
        return (255 shl 24) or channel(16) or channel(8) or channel(0)
    }
    @Test fun translucentCaptionsDoNotHideSourceMotion() {
        val layer = 0xffffffff.toInt()
        for (source in listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xff777777.toInt())) {
            assertEquals(FrameProtocol.luminance(source).toDouble(), FrameProtocol.sourceLuminance(compose(source, layer), layer).toDouble(), 4.0)
        }
        assertTrue(kotlin.math.abs(FrameProtocol.sourceLuminance(compose(0xff000000.toInt(), layer), layer) -
            FrameProtocol.sourceLuminance(compose(0xffffffff.toInt(), layer), layer)) > 200)
    }
    @Test fun markerSurvivesWorstCaseBackgroundAndRejectsAnUnmarkedSurface() {
        for (revision in listOf(0, 1, 42, 32768, 65535)) {
            val result = CaptureMarker.decode { x, y ->
                val bit = y / CaptureMarker.CELL * 8 + x / (CaptureMarker.CELL * 2)
                val second = x / CaptureMarker.CELL % 2 == 1
                val overlay = CaptureMarker.color(revision, bit, second)
                compose(overlay xor 0x00ffffff, overlay)
            }
            assertEquals(revision, result)
        }
        assertNull(CaptureMarker.decode { _, _ -> 0xffffffff.toInt() })
    }
    @Test fun rgbaHandlesPaddingPixelStrideCropAndMissingFinalPadding() {
        val buffer = ByteBuffer.allocate(36)
        fun pixel(at: Int, r: Int, g: Int, b: Int) { buffer.put(at, r.toByte()); buffer.put(at+1, g.toByte()); buffer.put(at+2, b.toByte()) }
        pixel(0, 1, 2, 3); pixel(8, 4, 5, 6); pixel(24, 7, 8, 9); pixel(32, 10, 11, 12)
        assertArrayEquals(intArrayOf(0xff010203.toInt(),0xff040506.toInt(),0xff070809.toInt(),0xff0a0b0c.toInt()), RgbaPlane.copy(buffer,2,2,24,8))
        assertArrayEquals(intArrayOf(0xff0a0b0c.toInt()), RgbaPlane.copy(buffer,1,1,24,8,1,1))
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedPlaneIsAnExplicitCaptureError() {
        RgbaPlane.copy(ByteBuffer.allocate(3),1,1,4,4)
    }
    @Test fun bufferPositionIsRespectedWithoutMutatingIt() {
        val buffer = ByteBuffer.wrap(byteArrayOf(0,0,1,2,3,0)).apply { position(2) }
        assertEquals(0xff010203.toInt(), RgbaPlane.copy(buffer,1,1,4,4)[0])
        assertEquals(2,buffer.position())
    }
}
