package mesh.ui

import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Other web pages on the device (CSRF, cross-site WebSocket, DNS rebinding) must not drive the node. */
class LocalOnlyRouteTest {
    private val port = ServerSocket(0).use { it.localPort }

    /** Raw HTTP so the test controls Host and Origin (java.net.http refuses to set Host). Returns the status line. */
    private fun raw(method: String, path: String, headers: Map<String, String>, body: String = ""): String =
        Socket("127.0.0.1", port).use { s ->
            val h = (mapOf("Host" to "127.0.0.1:$port", "Content-Length" to "${body.length}", "Connection" to "close") + headers)
                .filterValues { it.isNotEmpty() }.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            s.getOutputStream().write("$method $path HTTP/1.1\r\n$h\r\n$body".toByteArray())
            s.getInputStream().bufferedReader().readLine()
        }

    @Test fun onlyThisPageCanWrite() {
        val ran = ArrayList<Cmd>()
        val ui = UiServer(object : UiNode by FakeNode() { override fun exec(c: Cmd) { ran += c } }, port).apply { start() }
        try {
            val post = """{"t":"panic","confirm":"WIPE"}"""
            val om = mapOf("X-OM" to "1")
            fun code(m: String, p: String, h: Map<String, String>, b: String = "") = raw(m, p, h, b).split(' ')[1]
            assertEquals("403", code("POST", "/api/cmd", emptyMap(), post), "no-cors / form post: no X-OM")
            assertEquals("403", code("POST", "/api/cmd", om + ("Origin" to "http://evil.com"), post))
            assertEquals("403", code("POST", "/api/cmd", om + ("Origin" to "http://127.0.0.1:1"), post), "another local site")
            assertEquals("403", code("POST", "/api/cmd", om + ("Origin" to "null"), post), "file:// or sandboxed page")
            assertEquals("403", code("POST", "/api/cmd", om + ("Host" to "evil.com:$port"), post), "DNS rebinding")
            assertEquals("403", code("GET", "/api/snapshot", mapOf("Host" to "evil.com")), "rebinding read")
            assertEquals("403", code("POST", "/api/bundle", emptyMap(), "x"))
            assertEquals("403", code("POST", "/api/import", emptyMap(), "x"))
            assertTrue(code("OPTIONS", "/api/cmd", mapOf("Origin" to "http://evil.com")) != "200", "preflight is never granted")
            val ws = mapOf("Connection" to "Upgrade", "Upgrade" to "websocket", "Sec-WebSocket-Version" to "13", "Sec-WebSocket-Key" to "dGhlIHNhbXBsZSBub25jZQ==")
            assertEquals("403", code("GET", "/ws", ws + ("Origin" to "http://evil.com")), "cross-site WebSocket hijacking")
            assertEquals("403", code("GET", "/ws", ws + ("Origin" to "http://localhost:$port")), "Origin must match Host exactly")
            assertEquals(emptyList(), ran, "nothing above reached the node")

            // The page itself: same origin, X-OM set.
            assertEquals("101", code("GET", "/ws", ws + ("Origin" to "http://127.0.0.1:$port")))
            assertEquals("200", code("GET", "/", emptyMap()))
            assertEquals("200", code("POST", "/api/cmd", om + ("Origin" to "http://127.0.0.1:$port"), post))
            assertEquals("200", code("POST", "/api/cmd", om + ("Host" to "localhost:$port"), post), "curl / adb forward, no Origin")
            assertEquals(listOf<Cmd>(Cmd.Panic, Cmd.Panic), ran)
            val r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/snapshot")).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, r.statusCode())
        } finally { ui.stop() }
    }
}
