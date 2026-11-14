package mesh.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrTest {
    /** Paints the SVG's unit squares into pixels (4 px per module) and decodes them with zxing's reader. */
    private fun decode(svg: String): String {
        val n = Regex("""viewBox="0 0 (\d+) """).find(svg)!!.groupValues[1].toInt()
        val s = 4; val w = n * s
        val px = IntArray(w * w) { 0xFFFFFF }
        for (m in Regex("""M(\d+) (\d+)h1""").findAll(svg)) {
            val (x, y) = m.destructured
            for (dy in 0 until s) for (dx in 0 until s) px[(y.toInt() * s + dy) * w + x.toInt() * s + dx] = 0
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, px)))).text
    }

    @Test fun svgRoundTrips() {
        for (t in listOf("omesh:a3f29c01d4e5b6f7", "http://192.168.43.1:8090/?pin=0420")) assertEquals(t, decode(qrSvg(t)))
    }

    @Test fun endpointServesSvgAndRejectsBadText() {
        val port = ServerSocket(0).use { it.localPort }
        val ui = UiServer(FakeNode(), port).apply { start() }
        try {
            val http = HttpClient.newHttpClient()
            fun get(q: String) = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/qr$q")).build(), HttpResponse.BodyHandlers.ofString())
            val ok = get("?text=" + URLEncoder.encode("omesh:a3f29c01d4e5b6f7", "UTF-8"))
            assertEquals(200, ok.statusCode())
            assertTrue(ok.headers().firstValue("content-type").get().startsWith("image/svg+xml"))
            assertEquals("omesh:a3f29c01d4e5b6f7", decode(ok.body()))
            assertEquals(400, get("").statusCode())
            assertEquals(400, get("?text=").statusCode())
            assertEquals(400, get("?text=" + "x".repeat(201)).statusCode())
            val g = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/guest")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals("null", g.body())
        } finally { ui.stop() }
    }

    @Test fun guestEndpointShowsPinAndUrls() {
        val port = ServerSocket(0).use { it.localPort }
        val gs = GuestServer(FakeNode(), 8090)  // not started: only pin and urls() are read
        val ui = UiServer(FakeNode(), port) { gs }.apply { start() }
        try {
            val body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/guest")).build(),
                HttpResponse.BodyHandlers.ofString()).body()
            assertTrue(body.startsWith("""{"pin":"${gs.pin}","urls":["""), body)
        } finally { ui.stop() }
    }
}
