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

    @Test fun pinGuard() {
        var rotated: String? = null
        val g = PinGuard(perIp = 3, global = 5) { rotated = it }
        val p0 = g.pin
        assertTrue(g.check("ip1", p0))
        repeat(3) { assertFalse(g.check("ip1", "bad")) }
        assertFalse(g.check("ip1", p0))                    // ip1 locked out, even with the right PIN
        assertTrue(g.check("ip2", p0))
        assertFalse(g.check("ip2", "bad")); assertNull(rotated)
        assertFalse(g.check("ip3", "bad"))                 // 5th global failure rotates
        assertEquals(g.pin, rotated)
        assertTrue(g.check("ip1", g.pin))                  // rotation clears lockouts
    }

    @Test fun bodyValidation() {
        assertEquals("[guest Ann] yo", guestPostBody("""{"name":" Ann ","text":"yo"}"""))
        for (bad in listOf("""{"name":"","text":"x"}""", """{"name":"a","text":" "}""", """{"name":"a[b","text":"x"}""",
            """{"name":"a","text":"${"x".repeat(1000)}"}""", """{"name":"aaaaaaaaaaaaaaaaaaaaa","text":"x"}""", "[]"))
            assertFailsWith<IllegalArgumentException> { guestPostBody(bad) }
    }
}
