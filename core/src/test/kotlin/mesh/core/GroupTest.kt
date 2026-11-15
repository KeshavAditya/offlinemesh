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
    private fun forge(s: Setup, from: String, to: String, plain: ByteArray, f: Identity = s.n(from).node.id): String {
        val t = s.n(to).node.id
        val body = Crypto.seal(Crypto.dmKey(f.private, t.publicDer), f.nodeId + t.nodeId, Crypto.padDm(plain)!!)
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
        assertEquals(1, ids.toSet().size, "group message id differs between members")
        assertTrue(ids[0].length == 32 && ids[0].startsWith(a.id), ids[0])
        listOf("B", "C", "E", "F", "H", "I").forEach { assertTrue(s.n(it).added().isEmpty(), "$it emitted MessageAdded") }
        val own = a.added().single()
        assertEquals(ids[0], own.id)
        val shown = a.node.snapshot().gdms.getValue(s.gid).single()
        assertEquals(3, shown.delivered); assertEquals(3, shown.total)
        assertTrue(a.node.snapshot().feed.isEmpty() && a.node.snapshot().dms.isEmpty(), "group message leaked into feed or dms")
        val st = a.events.filterIsInstance<MeshEvent.GroupStatusChanged>()
        assertEquals(listOf(1, 2, 3), st.map { it.delivered }); assertTrue(st.all { it.total == 3 && it.id == own.id })
    }

    @Test fun t13_nonOwnerMemberSends() {
        val s = setup()
        s.n("D").node.sendGroup(s.gid, "from D")
        s.net.runFor(30_000)
        listOf("A", "G", "J").forEach { m -> assertEquals(1, s.groupMsgs(m).count { it.text == "from D" && it.from == s.n("D").id }, m) }
        val shown = s.n("D").node.snapshot().gdms.getValue(s.gid).single()
        assertEquals(3, shown.delivered); assertEquals(3, shown.total)
    }

    private fun invite(gid: String, salt: ByteArray, name: String, pubs: List<ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(2); o.write(Crypto.unhex(gid)); o.write(salt); o.write(name.length); o.write(name.toByteArray())
        o.write(pubs.size); pubs.forEach { o.write(it.size); o.write(it) }
        return o.toByteArray()
    }
    private fun pubs(vararg n: SimNode) = n.map { it.node.id.publicDer }
    private fun gidOf(owner: SimNode, salt: ByteArray) = Crypto.hex(Crypto.sha256(owner.node.id.nodeId, salt).copyOf(8))

    /** The salt [n] holds for [gid], read by reflection: the test plays a member who saw the real invite. */
    private fun saltOf(n: SimNode, gid: String): ByteArray {
        val f = MeshNode::class.java.getDeclaredField("groups").apply { isAccessible = true }
        val g = (f.get(n.node) as Map<*, *>)[gid]!!
        return g.javaClass.getDeclaredField("salt").apply { isAccessible = true }.get(g) as ByteArray
    }

    @Test fun t14_nonMemberAndNonOwnerAreIgnoredAndRemovedMemberGetsNothing() {
        val s = setup()
        val (a, d, g, j) = listOf("A", "D", "G", "J").map { s.n(it) }
        // GROUP_TEXT from B, who is not a member.
        val f1 = forge(s, "B", "D", byteArrayOf(3) + Crypto.unhex(s.gid) + Crypto.randomBytes(8) + "forged".toByteArray())
        // GROUP_INVITE change from G, a member but not the owner, dropping A and J.
        val f2 = forge(s, "G", "D", invite(s.gid, saltOf(d, s.gid), "hijack", pubs(g, d)))
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

    @Test fun t16_inviteWithGidNotBoundToSenderIsIgnoredAndAddsNoContacts() {
        val s = setup()
        val (b, d) = listOf("B", "D").map { s.n(it) }
        val stranger = Identity.generate()   // a key nobody on the mesh has seen
        val salt = Crypto.randomBytes(8)
        val forgedGid = gidOf(s.n("A"), salt)   // bound to A, but sent by B
        forge(s, "B", "D", invite(forgedGid, salt, "forged", pubs(b, d) + stranger.publicDer))
        s.net.runFor(5_000)
        assertTrue(d.node.snapshot().groups.none { it.id == forgedGid }, "invite with a gid not bound to its sender accepted")
        // Control: the same invite with a gid bound to B is accepted, yet its unknown member never becomes a contact.
        val goodGid = gidOf(b, salt)
        forge(s, "B", "D", invite(goodGid, salt, "real", pubs(b, d) + stranger.publicDer))
        s.net.runFor(5_000)
        assertEquals(listOf(b.id, d.id, stranger.idHex), d.node.snapshot().groups.single { it.id == goodGid }.members)
        assertTrue(d.node.snapshot().contacts.none { it.id == stranger.idHex }, "invite member added to contacts")
    }

    @Test fun t17_memberPreClaimingAnExistingGidCannotTakeOver() {
        val s = setup()
        val (a, d, g, j) = listOf("A", "D", "G", "J").map { s.n(it) }
        a.node.setGroupMembers(s.gid, listOf(d.id, g.id))
        s.net.runFor(20_000)
        assertTrue(j.node.snapshot().groups.isEmpty())
        // G knows the real gid and salt and claims the group at J, which no longer knows it, before A re-adds J.
        forge(s, "G", "J", invite(s.gid, saltOf(g, s.gid), "hijack", pubs(g, j)))
        s.net.runFor(5_000)
        assertTrue(j.node.snapshot().groups.isEmpty(), "J accepted G's claim on A's gid")
        a.node.setGroupMembers(s.gid, listOf(d.id, g.id, j.id))
        s.net.runFor(20_000)
        val jg = j.node.snapshot().groups.single()
        assertEquals("crew", jg.name); assertEquals(a.id, jg.owner); assertEquals(4, jg.members.size)
    }

    @Test fun t18_fifthGroupFromOneUnverifiedOwnerIsDropped() {
        val s = setup()
        val (b, d) = listOf("B", "D").map { s.n(it) }
        fun offer(name: String): String {
            val salt = Crypto.randomBytes(8)
            val gid = gidOf(b, salt)
            forge(s, "B", "D", invite(gid, salt, name, pubs(b, d)))
            s.net.runFor(3_000)
            return gid
        }
        val gids = (1..5).map { offer("spam$it") }
        val have = d.node.snapshot().groups.map { it.id }.toSet()
        assertEquals(gids.take(4).toSet() + s.gid, have, "D should keep crew and B's first 4 groups only")
        d.node.setVerified(b.id, true)
        s.net.runFor(100)
        val sixth = offer("verified")
        assertTrue(d.node.snapshot().groups.any { it.id == sixth }, "a verified owner is not capped at 4")
    }

    /** Forges invites to D, as [owner], for a fresh group {owner, D}; returns its gid. */
    private fun offer(s: Setup, owner: Identity): String {
        val salt = Crypto.randomBytes(8)
        val gid = Crypto.hex(Crypto.sha256(owner.nodeId, salt).copyOf(8))
        forge(s, "B", "D", invite(gid, salt, "g", listOf(owner.publicDer, s.n("D").node.id.publicDer)), owner)
        s.net.runFor(3_000)
        return gid
    }
    private fun groupsOf(s: Setup, name: String) = s.n(name).node.snapshot().groups.map { it.id }
    private fun spam(s: Setup) = List(8) { Identity.generate() }.flatMap { o -> List(4) { offer(s, o) } }

    @Test fun t18b_fullTableEvictsOldestEvictableGroupForNewInvites() {
        val s = setup()
        val spam = spam(s)
        assertEquals(spam, groupsOf(s, "D"), "8 unverified owners x 4 fill the table; crew, the oldest and never posted in, made room for the last")
        val j = s.n("J")
        s.n("D").node.setVerified(j.id, true)
        s.net.runFor(100)
        val fromJ = offer(s, j.node.id)
        assertEquals(spam.drop(1) + fromJ, groupsOf(s, "D"), "a verified owner gets in by evicting the oldest evictable group")
        val ninth = offer(s, Identity.generate())
        assertEquals(spam.drop(2) + fromJ + ninth, groupsOf(s, "D"), "a 9th unverified owner evicts the oldest unverified group, not the verified one")
    }

    @Test fun t18c_groupIPostedInIsNeverEvictedBySpam() {
        val s = setup()
        s.n("D").node.sendGroup(s.gid, "hi")
        s.net.runFor(10_000)
        val spam = spam(s)
        assertEquals(listOf(s.gid) + spam.drop(1), groupsOf(s, "D"), "crew stays; the oldest spam group made room instead")
        val v = List(31) { Identity.generate() }.onEach { s.n("D").node.setVerified(Crypto.hex(it.nodeId), true) }
        s.net.runFor(100)
        val fromV = v.map { offer(s, it) }
        offer(s, Identity.generate())
        assertEquals(listOf(s.gid) + fromV, groupsOf(s, "D"), "with only crew and verified groups left, a spam invite is dropped")
    }

    @Test fun t18d_verifiedInvitesStopAtHardCap() {
        val s = setup()
        val owners = List(70) { Identity.generate() }
        owners.forEach { s.n("D").node.setVerified(Crypto.hex(it.nodeId), true) }
        s.net.runFor(100)
        val fromV = owners.map { offer(s, it) }
        assertEquals(fromV.take(64), groupsOf(s, "D"), "crew made room once, then verified owners filled up to 64; the rest were dropped")
    }

    @Test fun t18e_postedFlagSurvivesRestart() {
        val s = setup()
        s.n("D").node.sendGroup(s.gid, "hi")
        s.net.runFor(10_000)
        val links = s.adj.getValue("D").map { s.net.linkOf("D", it) }
        links.forEach { it.down() }
        s.net.runFor(100)
        s.n("D").restart()
        links.forEach { it.up() }
        s.net.runFor(10_000)
        val spam = spam(s)
        assertEquals(listOf(s.gid) + spam.drop(1), groupsOf(s, "D"), "crew is still marked posted after restart")
    }

    @Test fun t19_twoMembersReusingOneGroupMsgIdAreBothDelivered() {
        val s = setup()
        val gm = Crypto.randomBytes(8)
        for (m in listOf("D", "G")) {
            forge(s, m, "J", byteArrayOf(3) + Crypto.unhex(s.gid) + gm + "same id from $m".toByteArray())
            s.net.runFor(5_000)
        }
        val got = s.groupMsgs("J")
        assertEquals(setOf("same id from D", "same id from G"), got.map { it.text }.toSet())
        assertEquals(setOf(s.n("D").id + Crypto.hex(gm), s.n("G").id + Crypto.hex(gm)), got.map { it.id }.toSet())
    }
}
