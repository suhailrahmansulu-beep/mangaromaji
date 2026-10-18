package com.example.mangaromaji

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class PreviewTest {
    @Test fun tightRowsSkipPaddingAndDoNotRequirePaddingAfterLastPixel() {
        val source=ByteBuffer.wrap(byteArrayOf(9,9,1,2,3,4,5,6,7,8,99,99,11,12,13,14,15,16,17,18)).apply {position(2)}
        val out=ByteBuffer.allocateDirect(16)
        RgbaPlane.pack(source,2,2,10,4,0,0,out)
        val result=ByteArray(16);out.get(result)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8,11,12,13,14,15,16,17,18),result)
        assertEquals(2,source.position());assertEquals(20,source.limit())
    }
    @Test fun croppedPixelStridePackingReusesOutputWithoutOldTrailingBytes() {
        val source=ByteBuffer.allocate(32)
        for(i in 0 until 32) source.put(i,i.toByte())
        val out=ByteBuffer.allocateDirect(32)
        RgbaPlane.pack(source,2,1,32,8,1,0,out)
        assertEquals(8,out.remaining())
        val result=ByteArray(8);out.get(result)
        assertArrayEquals(byteArrayOf(8,9,10,11,16,17,18,19),result)
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedRowsFailExplicitly() {
        RgbaPlane.pack(ByteBuffer.allocate(7),2,1,8,4,0,0,ByteBuffer.allocate(8))
    }
    @Test fun previewSettlesAt250MsAndNeverAcceptsAnOlderPage() {
        val gate=PageGate(250)
        gate.observe(intArrayOf(255,255,255),0)
        assertNull(gate.ticket(249));val first=gate.ticket(250)!!
        gate.observe(intArrayOf(0,0,0),260)
        assertFalse(gate.complete(first));assertNull(gate.ticket(509))
        assertTrue(gate.complete(gate.ticket(510)!!))
    }
    @Test fun dictionaryReadingRetainsParticlesAndInflection() {
        assertEquals("Gakkō e ikimasu.",Romanizer().convert("学校へ行きます。"))
    }
    @Test fun smallKanaAndGeminationRetainTheirPronunciation() {
        assertEquals("Kyasshu",Romanizer().convert("キャッシュ"))
    }
}
