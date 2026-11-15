package mesh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// U1: live measured statistics.
class StatsTest {
    private val net = SimNetwork()
    private fun line(): List<SimNode> {
        for (n in listOf("A", "B", "C")) net.node(n)
        net.link("A", "B"); net.link("B", "C")
        net.runFor(3_000)
        return net.nodes.values.toList()
    }

    @Test fun countersMoveOnPostAndDm() {
        val (a, b, c) = line()
        net.runFor(20_000)   // first sync round lands
        a.node.post("hello line")
        a.node.sendDm(c.id, "hi C")
        net.runFor(15_000)
        // A line has one path, so replay B's copy of the DM to give C a duplicate.
        net.inject("B", "C", net.sent.last { it.from == "B" && it.to == "C" && it.env?.type == EnvType.DM }.frame)
        net.runFor(1_000)
        assertEquals(MsgStatus.DELIVERED, a.node.snapshot().dms.getValue(c.id).single().status)
        val (sa, sb, sc) = listOf(a, b, c).map { it.node.stats() }
        println("U1 stats A=$sa\nU1 stats B=$sb\nU1 stats C=$sc")
        assertTrue(sa.originated >= 2, "A.originated ${sa.originated}")
        assertTrue(sb.relayed >= 2, "B.relayed ${sb.relayed}")
        assertTrue(sc.duplicatesDropped > 0 && sc.bytesIn > 0, "C dupes ${sc.duplicatesDropped} bytesIn ${sc.bytesIn}")
        assertTrue(sa.ackRttMsLast > 0 && sa.ackRttMsMedian > 0, "A rtt ${sa.ackRttMsLast}")
        assertTrue(sa.bytesOut > 0 && sb.syncRounds > 0)
        assertEquals(SecKind.values().map { it.name }.toSet(), sa.drops.keys)
        assertEquals(1, sa.linkUps); assertEquals(2, sb.linkUps)
        val snap = b.node.snapshot()
        assertEquals(sb.linkUps, snap.stats.linkUps); assertEquals("STANDARD", snap.profile)
        assertTrue(snap.links.all { it.tree == "eager" || it.tree == "lazy" })
    }

    // Plan section 0 step 5: links stay open between DMs instead of reconnecting per message.
    @Test fun tenDmsDoNotReopenLinks() {
        val (a, b, c) = line()
        val before = listOf(a, b, c).map { it.node.stats().linkUps }
        repeat(10) { a.node.sendDm(c.id, "dm $it"); net.runFor(2_000) }
        net.runFor(10_000)
        assertTrue(a.node.snapshot().dms.getValue(c.id).all { it.status == MsgStatus.DELIVERED })
        assertEquals(before, listOf(a, b, c).map { it.node.stats().linkUps })
        assertTrue(listOf(a, b, c).all { it.node.stats().linkDowns == 0 })
    }
}
