package mesh.ui

import mesh.core.DebugState
import mesh.core.MeshEvent
import mesh.core.Msg
import mesh.core.MsgStatus
import mesh.core.SelfInfo
import mesh.core.Snapshot
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestServerTest {
    private val posts = mutableListOf<String>()
    private val node = object : UiNode {
        val dm = Msg("m2", "bbbbbbbbbbbbbbbb", "aaaaaaaaaaaaaaaa", "secret dm", 2, 0, false, MsgStatus.RECEIVED, 1, null)
        override fun snapshot() = Snapshot(SelfInfo("aaaaaaaaaaaaaaaa", "B"), emptyList(), emptyList(),
            listOf(Msg("m1", "aaaaaaaaaaaaaaaa", null, "hello", 1, 0, true, MsgStatus.SENT, 0, null)),
            mapOf("bbbbbbbbbbbbbbbb" to listOf(dm)), emptyList(), DebugState(false, false))
        override fun addListener(l: (MeshEvent) -> Unit) {}
        override fun exec(c: Cmd) { posts += (c as Cmd.Post).text }
    }
    private val http = HttpClient.newHttpClient()

    private fun req(port: Int, path: String, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(body))
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun pinFeedPostAndRateLimit() {
        val port = ServerSocket(0).use { it.localPort }
        val s = GuestServer(node, port, PinGuard().also { pin = it.pin }).apply { start() }
        try {
            assertEquals(200, req(port, "/").statusCode())
            assertEquals(403, req(port, "/api/feed?pin=x").statusCode())
            val feed = req(port, "/api/feed?pin=$pin")
            assertEquals(200, feed.statusCode())
            assertTrue("hello" in feed.body() && "secret dm" !in feed.body(), feed.body())
            assertEquals(403, req(port, "/api/guest-post?pin=y", """{"name":"Zoe","text":"hi"}""").statusCode())
            assertEquals(400, req(port, "/api/guest-post?pin=$pin", """{"name":"Z]","text":"hi"}""").statusCode())
            assertEquals(200, req(port, "/api/guest-post?pin=$pin", """{"name":"Zoe","text":"hi"}""").statusCode())
            assertEquals(429, req(port, "/api/guest-post?pin=$pin", """{"name":"Zoe","text":"again"}""").statusCode())
            assertEquals(413, req(port, "/api/guest-post?pin=$pin", "x".repeat(5000)).statusCode())
            assertEquals(listOf("[guest Zoe] hi"), posts)
        } finally { s.stop() }
    }

    private var pin = ""

    @Test fun webClientAssetsAndApk() {
        val apk = kotlin.io.path.createTempFile(suffix = ".apk").toFile().apply { writeText("abc"); deleteOnExit() }
        val port = ServerSocket(0).use { it.localPort }
        val s = GuestServer(node, port, PinGuard().also { pin = it.pin }, WebTransport(), apk).apply { start() }
        try {
            val page = req(port, "/").body()
            assertTrue("/webbackend.js" in page && "OM_BACKEND" in page, "full client at /")
            // SHA-256("abc")
            assertTrue("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" in page, "APK hash in page")
            assertTrue("/api/feed" in req(port, "/lite").body(), "read-only page at /lite")
            for (f in listOf("webbackend.js", "meshnode.js", "meshcore.js", "noble.js")) {
                val r = req(port, "/$f")
                assertEquals(200, r.statusCode(), f)
                assertTrue(r.headers().firstValue("Content-Type").get().startsWith("text/javascript"), f)
            }
            val a = req(port, "/app.apk")
            assertEquals("abc", a.body())
            assertEquals("application/vnd.android.package-archive", a.headers().firstValue("Content-Type").get())
            assertTrue("OfflineMesh.apk" in a.headers().firstValue("Content-Disposition").get())
            assertEquals(200, req(port, "/api/qr?text=x").statusCode())
            assertEquals(403, req(port, "/api/feed").statusCode())   // mesh data stays behind the PIN
        } finally { s.stop() }
        val port2 = ServerSocket(0).use { it.localPort }
        val s2 = GuestServer(node, port2, PinGuard(), WebTransport()).apply { start() }
        try {
            assertEquals(404, req(port2, "/app.apk").statusCode())
            assertTrue("window.OM_APK = null" in req(port2, "/").body())
        } finally { s2.stop() }
    }

    @Test fun pinGuard() {
        var rotated: String? = null
        var t = 0L
        val g = PinGuard(perIp = 3, global = 5, maxRotations = 2, lockMs = 60_000, clock = { t }) { rotated = it }
        val p0 = g.pin
        assertTrue(g.check("ip1", p0))
        repeat(3) { assertFalse(g.check("ip1", "bad")) }
        assertFalse(g.check("ip1", p0))                    // ip1 locked out, even with the right PIN
        assertTrue(g.check("ip2", p0))
        assertFalse(g.check("ip2", "bad")); assertNull(rotated)
        assertFalse(g.check("ip3", "bad"))                 // 5th global failure rotates
        assertEquals(g.pin, rotated)
        assertFalse(g.check("ip1", g.pin))                 // rotation does NOT clear lockouts
        t = 61_000
        assertTrue(g.check("ip1", g.pin))                  // lockout window expired
        repeat(5) { g.check("ip${it + 10}", "bad") }       // second rotation hits maxRotations
        assertEquals(2, g.rotations)
        assertFalse(g.check("ip99", g.pin))                // guest access off until a new guard
    }

    @Test fun bodyValidation() {
        assertEquals("[guest Ann] yo", guestPostBody("""{"name":" Ann ","text":"yo"}"""))
        for (bad in listOf("""{"name":"","text":"x"}""", """{"name":"a","text":" "}""", """{"name":"a[b","text":"x"}""",
            """{"name":"a","text":"${"x".repeat(1000)}"}""", """{"name":"aaaaaaaaaaaaaaaaaaaaa","text":"x"}""", "[]"))
            assertFailsWith<IllegalArgumentException> { guestPostBody(bad) }
    }
}
