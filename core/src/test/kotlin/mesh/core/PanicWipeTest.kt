package mesh.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// U2 panic wipe on the simulator: line A - B - C, A and C share DMs and two groups (one owned by each).
class PanicWipeTest {
    private class Setup(val net: SimNetwork, val gA: String, val gC: String) {
        fun n(name: String) = net.nodes.getValue(name)
    }

    private fun setup(): Setup {
        val net = SimNetwork()
        listOf("A", "B", "C").forEach { net.node(it) }
        net.link("A", "B"); net.link("B", "C")
        net.runFor(10_000)
        val a = net.nodes.getValue("A"); val c = net.nodes.getValue("C")
        a.node.sendDm(c.id, "hi C"); c.node.sendDm(a.id, "hi A")
        val gA = a.node.createGroup("ga", listOf(c.id)); val gC = c.node.createGroup("gc", listOf(a.id))
        net.runFor(10_000)
        a.node.sendGroup(gA, "a in ga"); a.node.sendGroup(gC, "a in gc"); c.node.sendGroup(gC, "c in gc")
        net.runFor(10_000)
        val s = c.node.snapshot()
        assertEquals(2, s.dms.getValue(a.id).size)
        assertEquals(listOf("a in ga"), s.gdms.getValue(gA).map { it.text })
        assertEquals(setOf("a in gc", "c in gc"), s.gdms.getValue(gC).map { it.text }.toSet())
        return Setup(net, gA, gC)
    }

    private fun ByteArray.has(x: ByteArray) = (0..size - x.size).any { i -> x.indices.all { this[i + it] == x[it] } }

    /** Every file under [dir] holds none of [needles]. */
    private fun assertClean(dir: File, needles: List<ByteArray>) {
        for (f in dir.walk().filter { it.isFile }) {
            val b = f.readBytes()
            needles.forEach { assertFalse(b.has(it), "${f.name} still holds old bytes") }
        }
    }

    @Test fun wipeRevokesAtPeersAndLeavesNoOldDataOnDisk() {
        val s = setup()
        val a = s.n("A"); val b = s.n("B"); val c = s.n("C")
        val old = a.node.id
        val oldA = old.idHex
        val needles = ArrayList<ByteArray>().apply { add(old.publicDer); add(old.nodeId) }
        Store(File(a.dir, "messages.log")).replay { e, _ -> needles += e.msgId }
        assertTrue(needles.size > 4)

        a.node.panicWipe()
        s.net.runFor(5_000)

        assertNotEquals(oldA, a.id)
        val sa = a.node.snapshot()
        assertTrue(sa.contacts.isEmpty() && sa.dms.isEmpty() && sa.groups.isEmpty() && sa.gdms.isEmpty() && sa.security.isEmpty(), "$sa")
        assertNotEquals("A", sa.self.name)
        assertEquals(0, sa.stats.originated)
        assertClean(a.dir, needles)

        val sc = c.node.snapshot()
        assertNull(sc.dms[oldA])
        assertTrue(c.security().any { it.kind == SecKind.PEER_REVOKED && it.src == oldA })
        assertTrue(sc.contacts.single { it.id == oldA }.revoked)

        // A later DM signed with old-A's key is dropped.
        val body = Crypto.seal(Crypto.dmKey(old.private, c.node.id.publicDer), old.nodeId + c.node.id.nodeId, Crypto.padDm(byteArrayOf(0) + "ghost".toByteArray())!!)
        val e = Envelope.create(old, EnvType.DM, c.node.id.nodeId, 1_000_000, Math.floorDiv(s.net.nowMs(), C.EPOCH_SECONDS * 1000L), 0, C.TTL_DM, body)
        s.net.inject("B", "C", Codec.encodeFrame(Frame.Env(e)))
        s.net.runFor(1_000)
        assertTrue(c.added().none { it.text == "ghost" })
        assertNull(c.node.snapshot().dms[oldA])

        // The transport redials (here by hand): both sides re-handshake and B learns the new key.
        s.net.linkOf("A", "B").up()
        s.net.runFor(10_000)
        assertTrue(a.node.snapshot().links.single().authed)
        assertTrue(b.node.snapshot().contacts.any { it.id == a.id && it.linked })
        assertTrue(c.node.snapshot().contacts.any { it.id == a.id && !it.revoked })
        assertClean(a.dir, needles)
    }

    @Test fun revokeRemovesOldGroupMessagesAndSurvivesRestart() {
        val s = setup()
        val a = s.n("A"); val c = s.n("C")
        val oldA = a.id
        a.node.panicWipe()
        s.net.runFor(5_000)
        fun check(sc: Snapshot) {
            assertNull(sc.dms[oldA])
            assertTrue(sc.groups.none { it.id == s.gA }, "group owned by old-A still there")
            assertTrue(sc.gdms[s.gA].isNullOrEmpty())
            assertEquals(listOf("c in gc"), sc.gdms.getValue(s.gC).map { it.text })
            assertTrue(sc.groups.any { it.id == s.gC })
        }
        check(c.node.snapshot())
        // messages.log was rewritten: a restart replays nothing of old-A.
        s.net.linkOf("B", "C").down()
        s.net.runFor(100)
        c.restart()
        check(c.node.snapshot())
    }
}
