package mesh.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// U7: DM length padding, plus the U10 re-ACK on a failover resend.
class PaddingTest {
    @Test fun shortAndLongDmHaveEqualBodies() {
        val net = SimNetwork()
        val (a, c) = listOf("A", "C").map { net.node(it) }
        net.link("A", "C")
        net.runFor(3_000)
        a.node.sendDm(c.id, "x")
        a.node.sendDm(c.id, "y".repeat(50))
        net.runFor(5_000)
        val sizes = net.sent.mapNotNull { it.env }.filter { it.type == EnvType.DM }.map { it.body.size }
        assertEquals(listOf(12 + 64 + 16, 12 + 64 + 16), sizes)
        assertEquals(listOf("x", "y".repeat(50)), c.added().map { it.text })
    }

    @Test fun padRoundTripsEveryKindAndPicksTheBucket() {
        for (k in 0..4) {
            val data = if (k == 4) ByteArray(0) else Crypto.randomBytes(10 + k * 300)
            val padded = Crypto.padDm(byteArrayOf(k.toByte()) + data)!!
            assertContentEquals(byteArrayOf(k.toByte()) + data, Crypto.unpadDm(padded), "kind $k")
        }
        fun bucket(dataLen: Int) = Crypto.padDm(ByteArray(1 + dataLen))?.size
        assertEquals(64, bucket(0)); assertEquals(64, bucket(61)); assertEquals(128, bucket(62))
        assertEquals(4096, bucket(4093)); assertEquals(8192, bucket(4094)); assertEquals(40_960, bucket(39_999))
        assertNull(bucket(40_958))
        assertNull(Crypto.unpadDm(ByteArray(64).also { it[2] = 62 }), "dataLen past the end")
    }

    @Test fun dataLenTooLargeIsMalformedWithoutPenalty() {
        val net = SimNetwork()
        val (a, c) = listOf("A", "C").map { net.node(it) }
        net.link("A", "C")
        net.runFor(3_000)
        val f = a.node.id; val t = c.node.id
        val bad = ByteArray(64).also { it[2] = 100 }   // kind 0, dataLen 100 in 64 bytes
        val body = Crypto.seal(Crypto.dmKey(f.private, t.publicDer), f.nodeId + t.nodeId, bad)
        val ep = Math.floorDiv(net.nowMs(), C.EPOCH_SECONDS * 1000L)
        net.inject("A", "C", Codec.encodeFrame(Frame.Env(Envelope.create(f, EnvType.DM, t.nodeId, 1_000, ep, 0, C.TTL_DM, body))))
        net.runFor(2_000)
        assertEquals(1, c.security().count { it.kind == SecKind.MALFORMED })
        assertTrue(c.added().isEmpty())
        assertTrue(net.sent.none { it.env?.type == EnvType.ACK })
        assertTrue(c.security().none { it.kind == SecKind.LINK_CLOSED })
    }

    @Test fun failoverResendIsReAckedOnTheBackupPath() {
        val net = SimNetwork()
        for (n in listOf("A", "B", "C", "D")) net.node(n)
        net.link("A", "B"); net.link("B", "D"); net.link("A", "C"); net.link("C", "D")
        net.runFor(3_000)
        val (a, b, c, d) = listOf("A", "B", "C", "D").map { net.nodes.getValue(it) }
        val via = a.node.snapshot().contacts.single { it.id == d.id }.via
        val (bad, good) = if (via == b.id) b to c else c to b
        bad.node.setDebug(DebugFlag.DROP_ACK, true)   // relays the DM, drops the ACK

        a.node.sendDm(d.id, "ack me twice")
        net.runFor(8_000)
        assertEquals(1, d.added().size)
        val mine = a.node.snapshot().dms.getValue(d.id).single()
        assertEquals(MsgStatus.DELIVERED, mine.status); assertEquals(good.id, mine.rerouteVia)
        val acks = net.sent.filter { it.env?.type == EnvType.ACK && it.from == "D" }
        assertEquals(listOf(bad.name, good.name), acks.map { it.to })
        assertEquals(1, acks.map { Crypto.hex(it.env!!.msgId) }.distinct().size, "same ACK bytes")
    }
}
