package mesh.core

import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Stretch 2: a photo DM is one kind-1 DM frame, end-to-end encrypted like text.
class PhotoDmTest {
    @Test fun photoDmCrossesRelayIntactAndRelayCannotRead() {
        val net = SimNetwork()
        val (a, b, c) = listOf("A", "B", "C").map { net.node(it) }
        net.link("A", "B"); net.link("B", "C")
        net.runFor(3_000)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + Random(7).nextBytes(C.MAX_DM_PLAINTEXT - 4)
        a.node.sendDmImage(c.id, jpeg)
        net.runFor(10_000)

        val got = c.added().single()
        assertContentEquals(jpeg, Base64.getDecoder().decode(got.img))
        assertEquals(a.id, got.from); assertEquals(2, got.hops)
        assertEquals(MsgStatus.DELIVERED, a.events.filterIsInstance<MeshEvent.StatusChanged>().last().status)
        assertTrue(b.added().isEmpty(), "relay must not emit MessageAdded")
        val dm = net.sent.mapNotNull { it.env }.first { it.type == EnvType.DM }
        assertEquals(C.MAX_DM_PADDED + 28, dm.body.size)   // U7: 3 + 39,999 pads to 40,960
        assertNull(Crypto.open(Crypto.dmKey(b.node.id.private, a.node.id.publicDer), dm.srcId + dm.dst, dm.body))
    }

    @Test fun oversizeOrNonJpegIsNotSent() {
        val net = SimNetwork()
        val (a, c) = listOf("A", "C").map { net.node(it) }
        net.link("A", "C")
        net.runFor(3_000)
        a.node.sendDmImage(c.id, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(C.MAX_DM_PLAINTEXT - 3))
        a.node.sendDmImage(c.id, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        net.runFor(5_000)
        assertTrue(net.sent.mapNotNull { it.env }.none { it.type == EnvType.DM })
    }
}
