package com.meshnet.offline

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class QrTest {
    @Test fun roundTripsEveryByteValue() {
        // 500 bytes = C.QR_MAX_BYTES; covers all 256 values, including the ones a UTF-8 or Shift_JIS guess would mangle
        val data = ByteArray(256) { it.toByte() } + Random(1).nextBytes(244)
        val m = Qr.encode(data, 600)
        val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else -1 }
        val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, px))),
            mapOf(DecodeHintType.PURE_BARCODE to true))
        @Suppress("UNCHECKED_CAST")
        val segs = r.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>?
        assertContentEquals(data, Qr.bytes(segs, r.text))   // the scanner's path (byte segments)
        assertContentEquals(data, Qr.bytes(null, r.text))   // the fallback path (text as ISO-8859-1)
    }
}
