package com.meshnet.offline

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** U6: envelope bytes <-> one byte-mode QR code. ISO-8859-1 maps each byte to one char, so nothing is lost either way. */
object Qr {
    fun encode(bytes: ByteArray, size: Int): BitMatrix = QRCodeWriter().encode(
        String(bytes, Charsets.ISO_8859_1), BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))

    /** Text as a QR code, e.g. the invite's WIFI: join code and guest URL. UTF-8 (with an ECI header some scanners mishandle) only when not ASCII. */
    fun text(s: String): BitMatrix = QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2) +
            (if (s.all { it.code < 128 }) emptyMap() else mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")))

    /** One pixel per module scaled to [side] without smoothing, so the modules stay sharp. */
    fun bitmap(m: BitMatrix, side: Int): Bitmap {
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return Bitmap.createScaledBitmap(bmp, side, side, false)
    }

    /** The decoder's raw byte segments when it gives them (exact), else the text read back as ISO-8859-1. */
    fun bytes(segments: List<ByteArray>?, text: String?): ByteArray? =
        segments?.takeIf { it.isNotEmpty() }?.reduce(ByteArray::plus) ?: text?.toByteArray(Charsets.ISO_8859_1)
}
