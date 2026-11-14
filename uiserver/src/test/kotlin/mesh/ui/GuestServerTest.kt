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
        val s = GuestServer(node, port, pin = "1234").apply { start() }
        try {
            assertEquals(200, req(port, "/").statusCode())
            assertEquals(403, req(port, "/api/feed?pin=9999").statusCode())
            Thread.sleep(1_100)  // wrong PIN locks this IP out for 1 s
            val feed = req(port, "/api/feed?pin=1234")
            assertEquals(200, feed.statusCode())
            assertTrue("hello" in feed.body() && "secret dm" !in feed.body(), feed.body())
            assertEquals(403, req(port, "/api/guest-post?pin=0000", """{"name":"Zoe","text":"hi"}""").statusCode())
            Thread.sleep(1_100)
            assertEquals(400, req(port, "/api/guest-post?pin=1234", """{"name":"Z]","text":"hi"}""").statusCode())
            assertEquals(200, req(port, "/api/guest-post?pin=1234", """{"name":"Zoe","text":"hi"}""").statusCode())
            assertEquals(429, req(port, "/api/guest-post?pin=1234", """{"name":"Zoe","text":"again"}""").statusCode())
            assertEquals(listOf("[guest Zoe] hi"), posts)
        } finally { s.stop() }
    }

    @Test fun bodyValidation() {
        assertEquals("[guest Ann] yo", guestPostBody("""{"name":" Ann ","text":"yo"}"""))
        for (bad in listOf("""{"name":"","text":"x"}""", """{"name":"a","text":" "}""", """{"name":"a[b","text":"x"}""",
            """{"name":"a","text":"${"x".repeat(1000)}"}""", """{"name":"aaaaaaaaaaaaaaaaaaaaa","text":"x"}""", "[]"))
            assertFailsWith<IllegalArgumentException> { guestPostBody(bad) }
    }
}
