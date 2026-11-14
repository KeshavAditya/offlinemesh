package mesh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// U10: DM failover and black-hole detection.
class FailoverTest {
    @Test fun diamondReroutesAroundDroppingRelay() {
        val net = SimNetwork()
        for (n in listOf("A", "B", "C", "D")) net.node(n)
        net.link("A", "B"); net.link("B", "D"); net.link("A", "C"); net.link("C", "D")
        net.runFor(3_000)
        val (a, b, c, d) = listOf("A", "B", "C", "D").map { net.nodes.getValue(it) }
        val via = a.node.snapshot().contacts.single { it.id == d.id }.via
        val (bad, good) = if (via == b.id) b to c else c to b
        bad.node.setDebug(DebugFlag.DROP_RELAY, true)

        a.node.sendDm(d.id, "around the hole")
        net.runFor(8_000)   // timer 3 s + 1 s x 1 hop, then the backup; well before the first 15 s sync round
        val got = d.added().single()
        assertEquals("around the hole", got.text); assertEquals(good.id, got.via)
        val mine = a.node.snapshot().dms.getValue(d.id).single()
        assertEquals(MsgStatus.DELIVERED, mine.status); assertEquals(good.id, mine.rerouteVia)

        net.runFor(40_000)
        val failed = a.security().filter { it.kind == SecKind.ROUTE_FAILED }
        assertEquals(1, failed.size, "ROUTE_FAILED logged ${failed.size} times")
        assertEquals("sim:${bad.name}", failed[0].link); assertEquals(d.id, failed[0].src)
        assertEquals(1, d.added().size)
        assertTrue(listOf(a, b, c, d).all { n -> n.security().none { it.kind == SecKind.BANNED } })

        // The failed first link stays avoided: the next DM goes straight to the backup.
        val before = net.sent.count { it.env?.type == EnvType.DM && it.from == "A" }
        a.node.sendDm(d.id, "second")
        net.runFor(5_000)
        val dmOut = net.sent.filter { it.env?.type == EnvType.DM && it.from == "A" }.drop(before)
        assertEquals(listOf(good.name), dmOut.map { it.to })
        assertEquals(1, a.security().count { it.kind == SecKind.ROUTE_FAILED })
    }

    @Test fun groupFanOutDmFailsOverAndCountsOnce() {
        val net = SimNetwork()
        for (n in listOf("A", "B", "C", "D")) net.node(n)
        net.link("A", "B"); net.link("B", "D"); net.link("A", "C"); net.link("C", "D")
        net.runFor(3_000)
        val (a, b, c, d) = listOf("A", "B", "C", "D").map { net.nodes.getValue(it) }
        val gid = a.node.createGroup("crew", listOf(b.id, c.id, d.id))
        net.runFor(5_000)
        val via = a.node.snapshot().contacts.single { it.id == d.id }.via
        (if (via == b.id) b else c).node.setDebug(DebugFlag.DROP_RELAY, true)
        a.node.sendGroup(gid, "hello crew")
        net.runFor(8_000)   // ACK timer 4 s, then the backup
        assertEquals("hello crew", d.added().single { it.group == gid }.text)
        val shown = a.node.snapshot().gdms.getValue(gid).single()
        assertEquals(3, shown.delivered); assertEquals(3, shown.total)
        net.runFor(40_000)
        assertEquals(listOf(1, 2, 3), a.events.filterIsInstance<MeshEvent.GroupStatusChanged>().map { it.delivered })
        assertEquals(1, a.security().count { it.kind == SecKind.ROUTE_FAILED })
    }

    @Test fun healthyPathIsNeverResent() {
        val net = SimNetwork()
        val names = listOf("A", "B", "C", "D", "E")
        names.forEach { net.node(it) }
        names.zipWithNext().forEach { (x, y) -> net.link(x, y) }
        net.runFor(3_000)
        val (a, e) = net.nodes.getValue("A") to net.nodes.getValue("E")
        a.node.sendDm(e.id, "four hops")
        net.runFor(40_000)
        assertEquals("four hops", e.added().single().text)
        assertEquals(4, net.sent.count { it.env?.type == EnvType.DM }, "DM was resent")
        val mine = a.node.snapshot().dms.getValue(e.id).single()
        assertEquals(MsgStatus.DELIVERED, mine.status); assertNull(mine.rerouteVia)
        assertTrue(net.nodes.values.all { it.security().isEmpty() })
    }
}
