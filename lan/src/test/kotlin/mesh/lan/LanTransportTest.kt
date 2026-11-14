package mesh.lan

import mesh.core.C
import mesh.core.LinkId
import mesh.core.TransportListener
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanTransportTest {
    /** Records callbacks as strings / frames in arrival order. */
    private class Rec : TransportListener {
        val ups = LinkedBlockingQueue<LinkId>()
        val downs = LinkedBlockingQueue<LinkId>()
        val frames = LinkedBlockingQueue<Pair<LinkId, ByteArray>>()
        override fun onLinkUp(link: LinkId, description: String) { ups.add(link) }
        override fun onFrame(link: LinkId, frame: ByteArray) { frames.add(link to frame) }
        override fun onLinkDown(link: LinkId) { downs.add(link) }
    }

    private val started = mutableListOf<LanTransport>()
    @AfterTest fun tearDown() = started.forEach { it.stop() }

    private fun <T> LinkedBlockingQueue<T>.next(): T = assertNotNull(poll(5, TimeUnit.SECONDS), "timed out")

    @Test fun staticPeerFramesBothWaysThenLinkDown() {
        val rb = Rec(); val b = LanTransport(0).also { started += it }; b.start(rb)
        val ra = Rec(); val a = LanTransport(0, listOf("127.0.0.1:${b.port}")).also { started += it }; a.start(ra)
        val la = ra.ups.next(); val lb = rb.ups.next()

        assertTrue(a.send(la, byteArrayOf(1, 2, 3)))
        rb.frames.next().let { assertEquals(lb, it.first); assertContentEquals(byteArrayOf(1, 2, 3), it.second) }
        val big = ByteArray(C.MAX_FRAME) { it.toByte() }
        assertTrue(b.send(lb, big))
        ra.frames.next().let { assertEquals(la, it.first); assertContentEquals(big, it.second) }

        b.close(lb)
        assertEquals(lb, rb.downs.next())
        assertEquals(la, ra.downs.next())
        assertTrue(!b.send(lb, byteArrayOf(1)))
        // static peer redials after backoff
        val la2 = ra.ups.next()
        assertTrue(la2 != la)
        rb.ups.next()
    }

    @Test fun oversizedLengthClosesLink() {
        val rb = Rec(); val b = LanTransport(0).also { started += it }; b.start(rb)
        Socket("127.0.0.1", b.port).use { s ->
            val link = rb.ups.next()
            DataOutputStream(s.getOutputStream()).apply { writeInt(C.MAX_FRAME + 1); flush() }
            assertEquals(link, rb.downs.next())
            assertEquals(-1, s.getInputStream().read())   // transport closed its side
        }
        assertTrue(rb.frames.isEmpty())
    }
}
