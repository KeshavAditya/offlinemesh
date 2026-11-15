package com.meshnet.offline

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrChunksTest {
    private val data = Random(7).nextBytes(5_000)

    @Test fun outOfOrderWithDuplicates() {
        val frames = QrChunks.split(data)
        assertEquals(9, frames.size)
        val a = QrChunks.Assembler()
        for (f in (frames.reversed() + frames.shuffled(Random(1))).dropLast(3)) { assertTrue(a.add(f)) }
        assertEquals(9, a.have)
        assertContentEquals(data, a.result())
    }

    @Test fun incompleteAndJunk() {
        val a = QrChunks.Assembler()
        assertFalse(a.add("hello")); assertFalse(a.add("OMQ1:abc:1/2:AAAA")); assertFalse(a.add("OMQ1:0123456789abcdef:3/2:AAAA"))
        assertTrue(a.add(QrChunks.split(data)[0])); assertNull(a.result())
    }

    @Test fun wrongHashRejected() {
        val frames = QrChunks.split(data).toMutableList()
        val other = QrChunks.split(Random(8).nextBytes(5_000))
        // Same header (hash, i/n) but another payload's chunk: complete, yet the bytes do not hash to the header.
        frames[4] = frames[4].substringBeforeLast(':') + ":" + other[4].substringAfterLast(':')
        val a = QrChunks.Assembler()
        frames.forEach { a.add(it) }
        assertFailsWith<IllegalStateException> { a.result() }
        assertEquals(0, a.total)   // starts over
    }

    @Test fun anotherSequenceStartsOver() {
        val a = QrChunks.Assembler()
        a.add(QrChunks.split(Random(9).nextBytes(2_000))[0])
        QrChunks.split(data).forEach { a.add(it) }
        assertContentEquals(data, a.result())
    }

    @Test fun framesDecodeThroughZxing() {
        val a = QrChunks.Assembler()
        for (f in QrChunks.split(data)) {
            val m = Qr.text(f, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L)   // as QrActivity draws a frame
            val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else -1 }
            val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, px))), mapOf(DecodeHintType.PURE_BARCODE to true))
            assertTrue(a.add(r.text))
        }
        assertContentEquals(data, a.result())
    }
}
