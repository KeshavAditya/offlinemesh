package mesh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Section 10.2 (#1 to #4 and #8; the rest come with the 5.12 hooks).
class SimTest {
    private fun line(net: SimNetwork, bcUp: Boolean = true) {
        net.node("A"); net.node("B"); net.node("C")
        net.link("A", "B"); net.link("B", "C", up = bcUp)
        net.runFor(3_000)
    }

    @Test fun t1_linePostReachesCOnceWithTwoHops() {
        val net = SimNetwork()
        line(net)
        net.nodes.getValue("A").node.post("hello mesh")
        net.runFor(15_000)
        val got = net.nodes.getValue("C").added().filter { it.text == "hello mesh" }
        assertEquals(1, got.size)
        assertEquals(2, got[0].hops)
        assertEquals(MsgStatus.RECEIVED, got[0].status)
        assertTrue(net.nodes.values.all { it.security().isEmpty() }, "honest line logged security events")
    }

    @Test fun t2_lineDmDeliveredTwoHopsAndRelayCannotRead() {
        val net = SimNetwork()
        line(net)
        val (a, b, c) = listOf("A", "B", "C").map { net.nodes.getValue(it) }
        a.node.sendDm(c.id, "secret")
        net.runFor(5_000)

        val got = c.added().single()
        assertEquals("secret", got.text); assertEquals(a.id, got.from); assertEquals(2, got.hops)
        val st = a.events.filterIsInstance<MeshEvent.StatusChanged>().last()
        assertEquals(MsgStatus.DELIVERED, st.status); assertEquals(2, st.hops); assertEquals(b.id, st.via)
        assertTrue(b.added().isEmpty(), "relay must not emit MessageAdded")

        val dm = net.sent.mapNotNull { it.env }.first { it.type == EnvType.DM }
        assertNull(Crypto.open(Crypto.dmKey(b.node.id.private, a.node.id.publicDer), dm.srcId + dm.dst, dm.body))
        assertNull(Crypto.open(Crypto.dmKey(b.node.id.private, c.node.id.publicDer), dm.srcId + dm.dst, dm.body))
    }

    @Test fun t3_diamondDmIsUnicast() {
        val net = SimNetwork()
        for (n in listOf("A", "B", "C", "D", "E")) net.node(n)
        net.link("A", "B"); net.link("B", "D"); net.link("A", "C"); net.link("C", "D"); net.link("B", "E")
        net.runFor(3_000)
        val d = net.nodes.getValue("D")
        net.nodes.getValue("A").node.sendDm(d.id, "just for D")
        net.runFor(40_000)   // spans two sync rounds: the DM must not leak through sync either
        assertEquals("just for D", d.added().single().text)
        val dmFrames = net.sent.filter { it.env?.type == EnvType.DM }
        assertTrue(dmFrames.none { it.to == "E" }, "E received the DM")
        assertTrue(net.nodes.getValue("E").added().isEmpty())
        assertEquals(2, dmFrames.size, "A->relay->D only: ${dmFrames.map { it.from + ">" + it.to }}")
        assertTrue(net.nodes.values.all { it.security().isEmpty() }, "honest diamond logged security events")
    }

    @Test fun t4_linkUpSyncDeliversMissedPost() {
        val net = SimNetwork()
        line(net, bcUp = false)
        net.nodes.getValue("A").node.post("while you were away")
        net.runFor(15_000)
        val c = net.nodes.getValue("C")
        assertTrue(c.added().isEmpty())
        net.linkOf("B", "C").up()
        net.runFor(C.SYNC_INTERVAL_MS)
        assertEquals(1, c.added().count { it.text == "while you were away" })
    }

    @Test fun t8_replayIsDroppedSilently() {
        val net = SimNetwork()
        line(net)
        val (b, c) = listOf("B", "C").map { net.nodes.getValue(it) }
        net.nodes.getValue("A").node.post("once")
        net.runFor(15_000)
        val orig = net.sent.first { it.from == "A" && it.to == "B" && it.env?.type == EnvType.POST }
        val mid = orig.env!!.msgId
        val before = net.sent.size
        val secBefore = b.security().size

        net.inject("A", "B", orig.frame)
        net.runFor(2_000)

        assertEquals(secBefore, b.security().size, "replay must not log a security event")
        assertTrue(net.sent.drop(before).none { it.from == "B" && it.env?.msgId?.contentEquals(mid) == true }, "B forwarded the replay")
        assertEquals(1, c.added().count { it.text == "once" })
        assertNotNull(b.added().singleOrNull { it.text == "once" })
    }
}
