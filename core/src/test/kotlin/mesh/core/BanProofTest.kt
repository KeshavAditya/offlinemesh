package mesh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Stretch 4: ban proof gossip (frame BAN_PROOF, section 5.2).
class BanProofTest {
    private fun post(id: Identity, epoch: Long, slot: Int, text: String) =
        Envelope.create(id, EnvType.POST, Envelope.BROADCAST, 1, epoch, slot, C.TTL_POST, text.toByteArray())
    private fun isProof(s: SimNetwork.Sent) = s.frame[0].toInt() == FrameType.BAN_PROOF
    private fun epoch(net: SimNetwork) = Math.floorDiv(net.nowMs(), C.EPOCH_SECONDS * 1000L)

    @Test fun proofChecksEverything() {
        val v = Identity.generate(); val w = Identity.generate()
        val a = post(v, 100, 0, "one"); val b = post(v, 100, 0, "two")
        val g = Guard()
        assertTrue(g.proofOk(a, b))
        assertFalse(g.proofOk(a, b.copy(sig = Crypto.sign(w.private, b.core))), "bad signature")
        assertFalse(g.proofOk(a, post(v, 100, 1, "two")), "different slot")
        assertFalse(g.proofOk(a, post(v, 101, 0, "two")), "different epoch")
        assertFalse(g.proofOk(a, a), "same msgId twice")
        assertFalse(g.proofOk(a, post(w, 100, 0, "two")), "two different keys")
        val f = Codec.decodeFrame(Codec.encodeFrame(Frame.BanProof(a, b))) as Frame.BanProof
        assertTrue(f.a.msgId.contentEquals(a.msgId) && f.b.msgId.contentEquals(b.msgId))
    }

    @Test fun nodesBehindTheWitnessBanViaGossip() {
        val net = SimNetwork()
        listOf("A", "B", "C", "D").forEach { net.node(it) }
        net.link("A", "B"); net.link("B", "C"); net.link("C", "D")
        net.runFor(3_000)
        val (a, b, c, d) = listOf("A", "B", "C", "D").map { net.nodes.getValue(it) }
        a.node.attack(Attack.FLOOD)
        net.runFor(10_000)
        assertEquals(3, c.added().count { it.text.startsWith("flood") }, "proof overtook the honest posts")
        for ((n, via) in listOf(b to "sim:A", c to "sim:B", d to "sim:C")) {
            val eq = n.security().single { it.kind == SecKind.RATE_EQUIVOCATION }
            assertEquals(a.id, eq.src); assertEquals(via, eq.link)
        }
        val proofs = net.sent.filter(::isProof)
        assertEquals(listOf("B" to "C", "C" to "D"), proofs.map { it.from to it.to }, "proof relayed more than once per hop")
        // The same proof again: D already banned A, so no second log and no relay.
        net.inject("C", "D", proofs.last().frame)
        net.runFor(1_000)
        assertEquals(1, d.security().count { it.kind == SecKind.RATE_EQUIVOCATION })
        assertTrue(net.sent.none { isProof(it) && it.from == "D" }, "D relayed a proof it already acted on")
        a.node.post("after ban")
        net.runFor(20_000)
        assertTrue(listOf(b, c, d).all { n -> n.added().none { it.text == "after ban" } })
    }

    @Test fun forgedProofBansNobodyAndPenalisesTheLink() {
        val net = SimNetwork()
        listOf("M", "B", "C").forEach { net.node(it) }
        net.link("M", "B"); net.link("B", "C")
        net.runFor(3_000)
        val (m, b, c) = listOf("M", "B", "C").map { net.nodes.getValue(it) }
        val ep = epoch(net)
        val x = post(c.node.id, ep, 0, "real")
        val forgedSig = post(c.node.id, ep, 0, "fake").let { it.copy(sig = Crypto.sign(m.node.id.private, it.core)) }
        net.inject("M", "B", Codec.encodeFrame(Frame.BanProof(x, forgedSig)))
        net.inject("M", "B", Codec.encodeFrame(Frame.BanProof(x, post(c.node.id, ep, 1, "other slot"))))
        net.runFor(1_000)
        val bad = b.security().filter { it.kind == SecKind.BAD_SIGNATURE }
        assertEquals(2, bad.size); assertTrue(bad.all { it.link == "sim:M" })
        assertTrue(b.security().any { it.kind == SecKind.LINK_CLOSED && it.link == "sim:M" })
        assertTrue(net.nodes.values.all { n -> n.security().none { it.kind == SecKind.RATE_EQUIVOCATION } })
        assertTrue(net.sent.none { isProof(it) && it.from == "B" }, "forged proof relayed")
        // C is not banned at B: its next post arrives.
        net.runFor(10_000)
        c.node.post("still here")
        net.runFor(15_000)
        assertEquals(1, b.added().count { it.text == "still here" })
    }
}
