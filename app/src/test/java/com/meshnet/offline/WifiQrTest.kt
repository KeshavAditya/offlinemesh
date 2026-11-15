package com.meshnet.offline

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.WifiParsedResult
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals

class WifiQrTest {
    @Test fun escapesSpecialCharacters() {
        val b = '/'.plus(45)   // a backslash, spelled so no tool or editor collapses a doubled one
        assertEquals("WIFI:T:WPA;S:a$b;b$b,c$b:d$b\"e$b${b}f;P:p$b;$b${b}w;;", wifiQr("""a;b,c:d"e${b}f""", "p;${b}w"))
        assertEquals("WIFI:T:nopass;S:open;;", wifiQr("open", ""))
    }

    @Test fun zxingParsesItBackThroughAQrCode() {
        val ssid = """Mesh;Net,"x":\ é"""; val pass = """k;3,:"\Zq9-Lr2!vT8#wYp"""
        val m = Qr.text(wifiQr(ssid, pass, "SAE"))
        val px = IntArray(m.width * m.height) { if (m[it % m.width, it / m.width]) 0xFF000000.toInt() else -1 }
        val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, px))), mapOf(DecodeHintType.PURE_BARCODE to true))
        val w = ResultParser.parseResult(r) as WifiParsedResult
        assertEquals(ssid, w.ssid); assertEquals(pass, w.password); assertEquals("SAE", w.networkEncryption)
    }
}
