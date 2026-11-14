package mesh.core

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Section 13.2 tests 12 to 15 (group chats as fan-out DMs), on the 10.2 simulator.
class GroupTest {
    private class Setup(val net: SimNetwork, val adj: Map<String, Set<String>>, val gid: String) {
        fun n(name: String) = net.nodes.getValue(name)
        fun groupMsgs(name: String) = n(name).added().filter { it.group == gid }
    }

    /** Random connected graph of 10 nodes A..J; A creates the group {A, D, G, J}. */
    private fun setup(): Setup {
        val net = SimNetwork()
        val rnd = Random(7)
        val names = ('A'..'J').map { "$it" }
        names.forEach { net.node(it) }
        val adj = names.associateWith { HashSet<String>() }
        fun add(a: String, b: String) {
            if (a == b || b in adj.getValue(a) || adj.getValue(a).size >= C.MAX_LINKS || adj.getValue(b).size >= C.MAX_LINKS) return
            adj.getValue(a) += b; adj.getValue(b) += a; net.link(a, b)
        }
        for (i in 1 until 10) add(names[i], names.take(i).filter { adj.getValue(it).size < C.MAX_LINKS }.random(rnd))
        repeat(6) { add(names[rnd.nextInt(10)], names[rnd.nextInt(10)]) }
        net.runFor(10_000)
        val a = net.nodes.getValue("A")
        val members = listOf("D", "G", "J").map { net.nodes.getValue(it).id }
        assertTrue(a.node.snapshot().contacts.map { it.id }.containsAll(members), "A does not know D, G, J yet")
        val gid = a.node.createGroup("crew", members)
        net.runFor(10_000)
        return Setup(net, adj, gid)
    }

    /** Signs a DM carrying [plain] from [from] to [to] and hands it to one of [from]'s neighbours. */
    private fun forge(s: Setup, from: String, to: String, plain: ByteArray): String {
        val f = s.n(from).node.id; val t = s.n(to).node.id
        val body = Crypto.seal(Crypto.dmKey(f.private, t.publicDer), f.nodeId + t.nodeId, plain)
        val ep = Math.floorDiv(s.net.nowMs(), C.EPOCH_SECONDS * 1000L)
        val e = Envelope.create(f, EnvType.DM, t.nodeId, 1_000, ep, 0, C.TTL_DM, body)
        s.net.inject(from, s.adj.getValue(from).first(), Codec.encodeFrame(Frame.Env(e)))
        return Crypto.hex(e.msgId)
    }

    @Test fun t12_groupMessageReachesMembersOnly() {
        val s = setup()
        val a = s.n("A")
        assertEquals(listOf(a.id) + listOf("D", "G", "J").map { s.n(it).id }, a.node.snapshot().groups.single().members)
        listOf("D", "G", "J").forEach { m -> assertEquals("crew", s.n(m).node.snapshot().groups.single { it.id == s.gid }.name) }
        a.node.sendGroup(s.gid, "hello crew")
        s.net.runFor(30_000)
        val ids = listOf("D", "G", "J").map { m ->
            val got = s.n(m).added()
            assertEquals(1, got.size, "$m: $got")
            assertEquals(s.gid, got.single().group); assertEquals("hello crew", got.single().text)
            got.single().id
        }
        assertEquals(1, ids.toSet().size, "groupMsgId differs between members")
        listOf("B", "C", "E", "F", "H", "I").forEach { assertTrue(s.n(it).added().isEmpty(), "$it emitted MessageAdded") }
        val own = a.added().single()
        assertEquals(ids[0], own.id)
        val shown = a.node.snapshot().gdms.getValue(s.gid).single()
        assertEquals(3, shown.delivered); assertEquals(3, shown.total)
        assertTrue(a.node.snapshot().feed.isEmpty() && a.node.snapshot().dms.isEmpty(), "group message leaked into feed or dms")
        val st = a.events.filterIsInstance<MeshEvent.GroupStatusChanged>()
        assertEquals(listOf(1, 2, 3), st.map { it.delivered }); assertTrue(st.all { it.total == 3 && it.groupMsgId == own.id })
    }

    @Test fun t13_nonOwnerMemberSends() {
        val s = setup()
        s.n("D").node.sendGroup(s.gid, "from D")
        s.net.runFor(30_000)
        listOf("A", "G", "J").forEach { m -> assertEquals(1, s.groupMsgs(m).count { it.text == "from D" && it.from == s.n("D").id }, m) }
        val shown = s.n("D").node.snapshot().gdms.getValue(s.gid).single()
        assertEquals(3, shown.delivered); assertEquals(3, shown.total)
    }

    private fun invite(gid: String, name: String, members: List<SimNode>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(2); o.write(Crypto.unhex(gid)); o.write(name.length); o.write(name.toByteArray())
        o.write(members.size); members.forEach { val p = it.node.id.publicDer; o.write(p.size); o.write(p) }
        return o.toByteArray()
    }

    @Test fun t14_nonMemberAndNonOwnerAreIgnoredAndRemovedMemberGetsNothing() {
        val s = setup()
        val (a, d, g, j) = listOf("A", "D", "G", "J").map { s.n(it) }
        // GROUP_TEXT from B, who is not a member.
        val f1 = forge(s, "B", "D", byteArrayOf(3) + Crypto.unhex(s.gid) + Crypto.randomBytes(8) + "forged".toByteArray())
        // GROUP_INVITE change from G, a member but not the owner, dropping A and J.
        val f2 = forge(s, "G", "D", invite(s.gid, "hijack", listOf(g, d)))
        s.net.runFor(20_000)
        for (f in listOf(f1, f2)) assertTrue(s.net.sent.any { it.to == "D" && it.env?.let { e -> Crypto.hex(e.msgId) == f } == true }, "forged DM never reached D")
        assertTrue(d.added().none { it.text == "forged" }, "non-member text shown")
        val dg = d.node.snapshot().groups.single { it.id == s.gid }
        assertEquals("crew", dg.name); assertEquals(a.id, dg.owner); assertEquals(4, dg.members.size)

        a.node.sendGroup(s.gid, "before removal")
        s.net.runFor(30_000)
        assertEquals(1, s.groupMsgs("J").size)
        a.node.setGroupMembers(s.gid, listOf(d.id, g.id))
        s.net.runFor(20_000)
        assertTrue(j.node.snapshot().groups.isEmpty(), "J still has the group")
        assertEquals(listOf(a.id, d.id, g.id), d.node.snapshot().groups.single().members)
        val jBefore = j.added().size
        a.node.sendGroup(s.gid, "after removal")
        s.net.runFor(30_000)
        assertEquals(jBefore, j.added().size, "J received something after removal")
        listOf("D", "G").forEach { m -> assertEquals(1, s.groupMsgs(m).count { it.text == "after removal" }, m) }
        assertEquals(1, j.node.snapshot().gdms.getValue(s.gid).size, "J lost its history")
        val last = a.node.snapshot().gdms.getValue(s.gid).single { it.text == "after removal" }
        assertEquals(2, last.delivered); assertEquals(2, last.total)
    }

    @Test fun t15_restartRebuildsGroupsFromLog() {
        val s = setup()
        s.n("A").node.sendGroup(s.gid, "from A")
        s.net.runFor(30_000)
        s.n("D").node.sendGroup(s.gid, "from D")
        s.net.runFor(30_000)
        for (m in listOf("D", "A")) {   // a plain member, then the owner
            val n = s.n(m)
            val before = n.node.snapshot()
            s.adj.getValue(m).forEach { s.net.linkOf(m, it).down() }
            s.net.runFor(100)
            n.restart()
            val after = n.node.snapshot()
            assertEquals(before.groups, after.groups, "$m groups")
            val msgs = after.gdms.getValue(s.gid)
            assertEquals(listOf("from A", "from D"), msgs.sortedBy { it.lamport }.map { it.text }, "$m history")
            assertEquals(before.gdms.getValue(s.gid).map { it.id }.toSet(), msgs.map { it.id }.toSet())
            val own = msgs.single { it.mine }
            assertEquals(3, own.delivered, "$m delivered after restart"); assertEquals(3, own.total)
        }
    }
}
