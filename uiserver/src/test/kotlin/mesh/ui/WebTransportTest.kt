package mesh.ui

import mesh.core.LinkId
import mesh.core.MeshEvent
import mesh.core.TransportListener
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.util.concurrent.CompletionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebTransportTest {
    private val node = object : UiNode {
        override fun snapshot() = error("unused")
        override fun addListener(l: (MeshEvent) -> Unit) {}
        override fun exec(c: Cmd) {}
    }
    private val ups = LinkedBlockingQueue<Pair<LinkId, String>>()
    private val frames = LinkedBlockingQueue<Pair<LinkId, ByteArray>>()
    private val downs = LinkedBlockingQueue<LinkId>()
    private val listener = object : TransportListener {
        override fun onLinkUp(link: LinkId, description: String) { ups += link to description }
        override fun onFrame(link: LinkId, frame: ByteArray) { frames += link to frame }
        override fun onLinkDown(link: LinkId) { downs += link }
    }

    /** Client side: binary messages and the close code it saw. */
    private class Client : WebSocket.Listener {
        val got = LinkedBlockingQueue<ByteArray>()
        val closed = LinkedBlockingQueue<Int>()
        override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): java.util.concurrent.CompletionStage<*>? {
            got += ByteArray(data.remaining()).also { data.get(it) }; ws.request(1); return null
        }
        override fun onClose(ws: WebSocket, code: Int, reason: String): java.util.concurrent.CompletionStage<*>? { closed += code; return null }
    }

    private val http = HttpClient.newHttpClient()
    private fun connect(port: Int, pin: String, c: Client = Client()): Pair<WebSocket, Client> =
        http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:$port/mesh?pin=$pin"), c).join() to c
    private fun <T> LinkedBlockingQueue<T>.next(): T = poll(5, TimeUnit.SECONDS) ?: error("timed out")

    @Test fun framesBothWaysPinAndCap() {
        val port = ServerSocket(0).use { it.localPort }
        val guard = PinGuard(perIp = 100)   // the bypass probes below are wrong-PIN attempts too
        val web = WebTransport().apply { start(listener) }
        val server = GuestServer(node, port, guard, web).apply { start() }
        try {
            val e = runCatching { connect(port, "nope") }.exceptionOrNull()
            val h = assertIs<WebSocketHandshakeException>((e as CompletionException).cause)
            assertEquals(403, h.response.statusCode())
            for (path in listOf("/mesh", "/mesh/", "//mesh", "/%6Desh", "/mesh/?pin=")) {   // no PIN on any spelling of the route (302 = captive catch-all to /, still no upgrade)
                val r = runCatching { http.newWebSocketBuilder().buildAsync(URI("ws://127.0.0.1:$port$path"), Client()).join() }
                assertTrue(r.isFailure, "upgraded without PIN: $path")
                (r.exceptionOrNull()?.cause as? WebSocketHandshakeException)?.let { assertTrue(it.response.statusCode() in listOf(403, 404, 302), "$path ${it.response.statusCode()}") }
            }

            val (ws, c) = connect(port, guard.pin)
            val (id, desc) = ups.next()
            assertEquals("web 127.0.0.1", desc)
            ws.sendBinary(ByteBuffer.wrap(byteArrayOf(7, 1, 2, 3)), true).join()
            val (fid, f) = frames.next()
            assertEquals(id, fid); assertContentEquals(byteArrayOf(7, 1, 2, 3), f)
            assertTrue(web.send(id, byteArrayOf(9, 8)))
            assertContentEquals(byteArrayOf(9, 8), c.got.next())

            val more = (2..16).map { connect(port, guard.pin) }
            repeat(15) { ups.next() }
            val (_, extra) = connect(port, guard.pin)
            assertEquals(1013, extra.closed.next())

            web.close(id)   // core closing a link closes the socket and reports link down
            assertEquals(1000, c.closed.next())
            assertEquals(id, downs.next())
            more.forEach { it.first.abort() }
        } finally {
            server.stop()
        }
    }
}
