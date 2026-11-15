package com.meshnet.offline

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

    /** The decoder's raw byte segments when it gives them (exact), else the text read back as ISO-8859-1. */
    fun bytes(segments: List<ByteArray>?, text: String?): ByteArray? =
        segments?.takeIf { it.isNotEmpty() }?.reduce(ByteArray::plus) ?: text?.toByteArray(Charsets.ISO_8859_1)
}
