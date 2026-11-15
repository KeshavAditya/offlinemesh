package mesh.ui

import mesh.core.DebugState
import mesh.core.MeshEvent
import mesh.core.SelfInfo
import mesh.core.Snapshot
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// research/ADVANCES_2.md #8: Safari may try HTTPS first; a TLS ClientHello on the plain guest port must fail at once.
class GuestTlsTest {
    private val node = object : UiNode {
        override fun snapshot() = Snapshot(SelfInfo("aaaaaaaaaaaaaaaa", "B"), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), DebugState(false, false))
        override fun addListener(l: (MeshEvent) -> Unit) {}
        override fun exec(c: Cmd) {}
    }
    private val port = ServerSocket(0).use { it.localPort }
    private val server = GuestServer(node, port).apply { start() }

    @AfterTest fun stop() = server.stop()

    // TLS 1.2 record header + start of a ClientHello (no CR/LF anywhere, like a real hello).
    private val hello = byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, 0xfc.toByte(), 0x03, 0x03) + ByteArray(64) { 0x11 }

    @Test fun tlsHelloIsClosedAtOnceAndHttpStillWorks() {
        repeat(2) {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 5_000
                s.getOutputStream().apply { write(hello); flush() }
                val t0 = System.nanoTime()
                val r = try { s.getInputStream().read() } catch (e: java.io.IOException) { -1 }
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertEquals(-1, r, "server answered a TLS hello")
                assertTrue(ms < 300, "TLS hello took $ms ms to close")
            }
        }
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().apply { write("GET /lite HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            assertTrue(s.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.1 200"))
        }
    }

    private fun getLite(): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().apply { write("GET /lite HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
        s.getInputStream().readBytes().decodeToString()
    }

    // Security review: one guest opening many sockets must not exhaust threads; extras close at once, slots come back on close.
    @Test fun perIpConnectionCapClosesExtrasAndFreesSlots() {
        val held = List(MAX_CONNS_PER_IP) { Socket("127.0.0.1", port) }
        repeat(4) {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 2_000
                val t0 = System.nanoTime()
                val r = try { s.getInputStream().read() } catch (e: java.io.IOException) { -1 }
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertEquals(-1, r)
                assertTrue(ms < 1_000, "over-cap socket took $ms ms to close")
            }
        }
        held.forEach { it.close() }
        val deadline = System.currentTimeMillis() + 3_000
        var reply = ""
        while (System.currentTimeMillis() < deadline) {
            reply = try { getLite() } catch (e: java.io.IOException) { "" }
            if (reply.startsWith("HTTP/1.1 200")) break
            Thread.sleep(50)
        }
        assertTrue(reply.startsWith("HTTP/1.1 200"), "slots not released after close")
    }
}
