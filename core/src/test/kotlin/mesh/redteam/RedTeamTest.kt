package mesh.redteam

import mesh.core.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Agent D: adversarial cases not in plan 10.2. Attacker frames are injected over a real link, so the link is the one blamed.
class RedTeamTest {
    private fun epoch(net: SimNetwork) = Math.floorDiv(net.nowMs(), C.EPOCH_SECONDS * 1000L)
    private fun env(e: Envelope) = Codec.encodeFrame(Frame.Env(e))
    private fun SimNetwork.n(name: String) = nodes.getValue(name)
    private fun hexOf(e: Envelope) = Crypto.hex(e.msgId)
    private fun SimNetwork.sentOf(e: Envelope, from: String? = null) = sent.count { s -> (from == null || s.from == from) && s.env?.msgId?.contentEquals(e.msgId) == true }

    /** Signs any field combination (Envelope.create only makes honest ones). PoW is mined for POST/DM unless [pow] is false. */
    private fun forge(
        id: Identity, type: Int, epoch: Long, body: ByteArray, slot: Int = 0, lamport: Long = 1, initTtl: Int = 6,
        ttl: Int = initTtl, hops: Int = 0, version: Int = 1, dst: ByteArray = Envelope.BROADCAST, pow: Boolean = true,
    ): Envelope {
        val e = Envelope(version, type, id.publicDer, dst, lamport, epoch, slot, initTtl, body, 0, ByteArray(0), ttl, hops)
        val nonce = if (pow && (type == EnvType.POST || type == EnvType.DM)) Pow.mine(e.core, Pow.bits(e)) else 0L
        return e.copy(powNonce = nonce, sig = Crypto.sign(id.private, e.core))
    }

    private fun post(id: Identity, epoch: Long, text: String, slot: Int = 0, lamport: Long = 1) =
        forge(id, EnvType.POST, epoch, text.toByteArray(), slot = slot, lamport = lamport)

    private fun presence(id: Identity, epoch: Long, name: String, lamport: Long) =
        forge(id, EnvType.PRESENCE, epoch, name.toByteArray().let { byteArrayOf(it.size.toByte()) + it }, lamport = lamport)

    private fun pair(net: SimNetwork, a: String, b: String) { net.node(a); net.node(b); net.link(a, b); net.runFor(3_000) }

    @Test fun fuzzedAndTruncatedFramesDecodeToNullWithoutThrowing() {
        val rnd = Random(7)
        repeat(3_000) { Codec.decodeFrame(rnd.nextBytes(rnd.nextInt(0, 600))) }   // must not throw
        repeat(1_000) {   // valid frame type byte, random rest
            val t = listOf(FrameType.ENVELOPE, FrameType.HELLO, FrameType.HELLO_AUTH, FrameType.SYNC_OFFER, FrameType.SYNC_WANT).random(rnd)
            Codec.decodeFrame(byteArrayOf(t.toByte()) + rnd.nextBytes(rnd.nextInt(0, 300)))
        }
        val id = Identity.generate()
        val valid = listOf(
            env(post(id, 1, "hello")), env(presence(id, 1, "me", 1)),
            Codec.encodeFrame(Frame.Hello(id.publicDer, ByteArray(16))), Codec.encodeFrame(Frame.SyncOffer(listOf(ByteArray(8), ByteArray(8)))),
        )
        for (f in valid) {
            for (n in 1 until f.size) assertNull(Codec.decodeFrame(f.copyOf(n)), "prefix $n of ${f.size} decoded")
            assertNull(Codec.decodeFrame(f + 0), "trailing byte accepted")
        }
        assertNull(Codec.decodeFrame(ByteArray(C.MAX_FRAME + 1).also { it[0] = 1 }))
        assertNull(Codec.decodeFrame(env(presence(id, 1, "x".repeat(C.MAX_NAME + 1), 1))), "name over MAX_NAME accepted")
        assertNull(Codec.decodeFrame(env(post(id, 1, "y".repeat(C.MAX_POST_BODY + 1)))), "post over MAX_POST_BODY accepted")
        // Stream reader: bad length headers fail fast, before any allocation.
        for (len in listOf(0, C.MAX_FRAME + 1, -1)) {
            val hdr = java.nio.ByteBuffer.allocate(4).putInt(len).array()
            assertFailsWith<IOException> { Codec.readFrame(DataInputStream(ByteArrayInputStream(hdr))) }
        }
    }

    @Test fun malformedFramesCloseLinkAndPeerIsRefusedFor60s() {
        val net = SimNetwork()
        pair(net, "M", "B")
        val (m, b) = net.n("M") to net.n("B")
        val k = Identity.generate()
        val good = env(post(k, epoch(net), "fine"))
        val junk = listOf(
            ByteArray(C.MAX_FRAME + 1).also { it[0] = 1 },                 // oversized
            good.copyOf(good.size - 1),                                     // truncated envelope
            byteArrayOf(0x7f, 1, 2, 3),                                     // unknown frame type
            env(presence(k, epoch(net), "n".repeat(C.MAX_NAME + 1), 5)),    // name too long
            byteArrayOf(FrameType.SYNC_OFFER.toByte(), 0, 5) + ByteArray(8), // count says 5, carries 1
        )
        junk.forEach { net.inject("M", "B", it) }
        net.runFor(500)
        assertEquals(5, b.security().count { it.kind == SecKind.MALFORMED && it.link == "sim:M" })
        assertTrue(net.linkOf("M", "B").isUp, "score 0 must not close the link")
        net.inject("M", "B", byteArrayOf())   // sixth: score -20
        net.runFor(500)
        assertFalse(net.linkOf("M", "B").isUp, "link survived six malformed frames")
        assertTrue(b.security().any { it.kind == SecKind.LINK_CLOSED && it.link == "sim:M" })

        net.linkOf("M", "B").up(); net.runFor(1_000)
        assertFalse(net.linkOf("M", "B").isUp, "refused peer reconnected inside 60 s")
        net.runFor(C.REFUSE_MS)
        net.linkOf("M", "B").up(); net.runFor(1_000)
        assertTrue(net.linkOf("M", "B").isUp, "peer still refused after 60 s")
        m.node.post("back again")
        net.runFor(15_000)
        assertEquals(1, b.added().count { it.text == "back again" })
    }

    @Test fun badPowPenalisesLinkNotSource() {
        val net = SimNetwork()
        pair(net, "M", "B")
        val b = net.n("B")
        val k = Identity.generate()
        fun badPow(i: Int): Envelope {
            var lam = 1L
            while (true) { val e = forge(k, EnvType.POST, epoch(net), "bad pow $i".toByteArray(), slot = i % 3, lamport = lam++, pow = false); if (!Pow.verify(e)) return e }
        }
        net.inject("M", "B", env(badPow(0))); net.inject("M", "B", env(badPow(1)))
        net.runFor(500)
        assertEquals(2, b.security().count { it.kind == SecKind.BAD_POW && it.link == "sim:M" })
        assertTrue(net.linkOf("M", "B").isUp, "two bad PoW (score 0) closed the link")
        net.inject("M", "B", env(badPow(2))); net.runFor(500)
        assertFalse(net.linkOf("M", "B").isUp, "third bad PoW did not close the link")
        assertTrue(b.added().isEmpty())
        // The key named in the bad envelopes is not banned: once M is allowed back, K's honest post is accepted.
        net.runFor(C.REFUSE_MS); net.linkOf("M", "B").up(); net.runFor(1_000)
        net.inject("M", "B", env(post(k, epoch(net), "honest k")))
        net.runFor(500)
        assertEquals(1, b.added().count { it.text == "honest k" })
    }

    @Test fun epochWindowEdgesAndStaleIsNotPenalisedOrForwarded() {
        val net = SimNetwork()
        for (n in listOf("M", "B", "C")) net.node(n)
        net.link("M", "B"); net.link("B", "C"); net.runFor(3_000)
        val (b, c) = net.n("B") to net.n("C")
        val k = Identity.generate()
        val now = epoch(net)
        val ok = listOf(post(k, now + C.MAX_FUTURE_EPOCHS, "edge future"), post(k, now - C.MAX_AGE_EPOCHS, "edge old"))
        val stale = (1..8).map { i -> post(k, if (i % 2 == 0) now + C.MAX_FUTURE_EPOCHS + i else now - C.MAX_AGE_EPOCHS - i, "stale $i") }
        (ok + stale).forEach { net.inject("M", "B", env(it)) }
        net.runFor(1_000)
        assertEquals(setOf("edge future", "edge old"), b.added().map { it.text }.toSet())
        assertEquals(8, b.security().count { it.kind == SecKind.STALE && it.link == "sim:M" })
        assertTrue(net.linkOf("M", "B").isUp, "STALE must not cost link score")
        assertTrue(stale.all { net.sentOf(it, from = "B") == 0 }, "B forwarded a stale envelope")
        assertTrue(c.added().none { it.text.startsWith("stale") })
    }

    @Test fun ttlVersionAndLamportAbuseRejectedAtMinus20Each() {
        val net = SimNetwork()
        for (n in listOf("M", "B", "C")) net.node(n)
        net.link("M", "B"); net.link("B", "C"); net.runFor(3_000)
        val b = net.n("B")
        val k = Identity.generate()
        val ep = epoch(net)
        val bad = listOf(
            forge(k, EnvType.POST, ep, "init 9".toByteArray(), initTtl = 9),
            forge(k, EnvType.POST, ep, "ttl raised".toByteArray(), slot = 1, initTtl = 6, ttl = 7, hops = 0),
            forge(k, EnvType.POST, ep, "hops lie".toByteArray(), slot = 2, initTtl = 6, ttl = 6, hops = 1),
            forge(k, EnvType.POST, ep + 1, "version 2".toByteArray(), version = 2),
            forge(k, EnvType.POST, ep + 1, "lamport jump".toByteArray(), slot = 1, lamport = 2_000_000_000L),
        )
        bad.forEach { net.inject("M", "B", env(it)) }
        net.runFor(1_000)
        assertTrue(b.added().isEmpty(), "accepted: ${b.added().map { it.text }}")
        assertTrue(bad.all { net.sentOf(it, from = "B") == 0 })
        assertTrue(net.linkOf("M", "B").isUp, "five -20 penalties (score 0) closed the link")
        net.inject("M", "B", env(forge(k, EnvType.PRESENCE, ep, byteArrayOf(1, 'x'.code.toByte()), initTtl = 200)))
        net.runFor(500)
        assertFalse(net.linkOf("M", "B").isUp, "sixth sanity failure did not close the link")
    }

    @Test fun forgedAcksDoNotMarkDmDelivered() {
        val net = SimNetwork()
        for (n in listOf("A", "M", "C")) net.node(n)
        net.link("A", "M"); net.link("M", "C"); net.runFor(3_000)
        val (a, m, c) = listOf("A", "M", "C").map { net.n(it) }
        m.node.setDebug(DebugFlag.DROP_RELAY, true)   // M swallows the DM and tries to fake its delivery
        net.runFor(100)
        a.node.sendDm(c.id, "for C only")
        net.runFor(2_000)
        val dmId = Crypto.unhex(a.added().single { it.to == c.id }.id)
        val ep = epoch(net)
        val ackByM = forge(m.node.id, EnvType.ACK, ep, dmId, initTtl = 8, dst = a.node.id.nodeId)
        val ackSpoofC = Envelope(1, EnvType.ACK, c.node.id.publicDer, a.node.id.nodeId, 9, ep, 0, 8, dmId, 0, Crypto.randomBytes(64), 8, 0)
        val ackUnknown = forge(c.node.id, EnvType.ACK, ep, Crypto.randomBytes(8), initTtl = 8, dst = a.node.id.nodeId)
        listOf(ackByM, ackSpoofC, ackUnknown).forEach { net.inject("M", "A", env(it)) }
        net.runFor(2_000)
        assertTrue(a.events.filterIsInstance<MeshEvent.StatusChanged>().none { it.status == MsgStatus.DELIVERED }, "forged ACK marked the DM delivered")
        val bad = a.security().filter { it.kind == SecKind.BAD_SIGNATURE }
        assertEquals(1, bad.size); assertEquals("sim:M", bad[0].link)
        assertTrue(c.added().isEmpty())
    }

    @Test fun secondValidAckForOneDmIsDroppedAndNotForwarded() {
        val net = SimNetwork()
        for (n in listOf("A", "B", "C")) net.node(n)
        net.link("A", "B"); net.link("B", "C"); net.runFor(3_000)
        val (a, c) = net.n("A") to net.n("C")
        a.node.sendDm(c.id, "ack once")
        net.runFor(5_000)
        val delivered = { a.events.filterIsInstance<MeshEvent.StatusChanged>().count { it.status == MsgStatus.DELIVERED } }
        assertEquals(1, delivered())
        val dmId = Crypto.unhex(a.added().single { it.to == c.id }.id)
        val ack2 = forge(c.node.id, EnvType.ACK, epoch(net), dmId, lamport = 999, initTtl = 8, dst = a.node.id.nodeId)
        net.inject("C", "B", env(ack2))
        net.runFor(1_000)
        assertEquals(0, net.sentOf(ack2, from = "B"), "B forwarded a second ACK for the same DM")
        net.inject("B", "A", env(ack2))
        net.runFor(1_000)
        assertEquals(1, delivered())
    }

    @Test fun presenceSpamFromOneKeyIsRateLimitedAndOldPresenceIgnored() {
        val net = SimNetwork()
        for (n in listOf("M", "B", "C")) net.node(n)
        net.link("M", "B"); net.link("B", "C"); net.runFor(3_000)
        val b = net.n("B")
        val k = Identity.generate()
        val spam = (0 until 20).map { presence(k, epoch(net), "spam$it", it + 10L) }
        spam.forEachIndexed { i, p -> net.schedule(i * 50L) { net.inject("M", "B", env(p)) } }
        net.runFor(2_000)
        fun nameOfK() = b.node.snapshot().contacts.single { it.id == k.idHex }.name
        assertEquals("spam0", nameOfK())
        assertEquals(1, spam.sumOf { net.sentOf(it, from = "B") }, "B forwarded more than one PRESENCE per 10 s")
        assertTrue(b.security().isEmpty(), "under-rate presence spam was penalised: ${b.security()}")
        net.runFor(11_000)
        net.inject("M", "B", env(presence(k, epoch(net), "older", 1)))   // lower lamport than the cached one
        net.runFor(11_000)
        assertEquals("spam0", nameOfK(), "older PRESENCE replaced the contact name")
        net.inject("M", "B", env(presence(k, epoch(net), "newer", 100)))
        net.runFor(500)
        assertEquals("newer", nameOfK())
    }

    @Test fun equivocationAcrossTwoLinksBansSourceNotLinksAndBanExpires() {
        val net = SimNetwork()
        for (n in listOf("B", "C", "D")) net.node(n)
        net.link("B", "D"); net.link("C", "D"); net.runFor(3_000)
        val d = net.n("D")
        val k = Identity.generate()
        val ep = epoch(net)
        net.inject("B", "D", env(post(k, ep, "version one"))); net.runFor(500)
        net.inject("C", "D", env(post(k, ep, "version two", lamport = 2))); net.runFor(500)
        assertEquals(listOf("version one"), d.added().map { it.text })
        val eq = d.security().single { it.kind == SecKind.RATE_EQUIVOCATION }
        assertEquals(k.idHex, eq.src); assertEquals("sim:C", eq.link)
        net.inject("B", "D", env(post(k, epoch(net), "while banned", slot = 1, lamport = 3))); net.runFor(500)
        assertTrue(d.added().none { it.text == "while banned" }, "banned source accepted")
        assertTrue(net.linkOf("B", "D").isUp && net.linkOf("C", "D").isUp, "equivocation is the source's fault, not a link's")
        assertTrue(d.security().none { it.kind == SecKind.LINK_CLOSED })
        net.runFor(C.BAN_DURATION_MS)
        net.inject("B", "D", env(post(k, epoch(net), "after ban", lamport = 4))); net.runFor(500)
        assertEquals(1, d.added().count { it.text == "after ban" }, "ban did not expire after BAN_DURATION")
    }

    @Test fun maxLinksIsEnforced() {
        val net = SimNetwork()
        net.node("H")
        val spokes = (0..C.MAX_LINKS).map { "S$it" }
        spokes.forEach { net.node(it); net.link("H", it); net.runFor(200) }
        net.runFor(3_000)
        assertEquals(C.MAX_LINKS, spokes.count { net.linkOf("H", it).isUp })
        assertFalse(net.linkOf("H", spokes.last()).isUp, "sixth link accepted")
        assertEquals(C.MAX_LINKS, net.n("H").node.snapshot().links.size)
    }

    /** Slot reuse across a receiver restart: the slot ledger is rebuilt from messages.log, so equivocation is still caught. */
    @Test fun receiverLedgerSurvivesRestart() {
        val net = SimNetwork()
        pair(net, "M", "B")
        val b = net.n("B")
        val k = Identity.generate()
        val ep = epoch(net)
        val v1 = post(k, ep, "before restart")
        net.inject("M", "B", env(v1)); net.runFor(500)
        assertEquals(1, b.added().count { it.text == "before restart" })
        net.linkOf("M", "B").down(); net.runFor(100)
        b.restart()
        net.linkOf("M", "B").up(); net.runFor(1_000)
        net.inject("M", "B", env(v1)); net.runFor(500)   // replay after restart: silent drop
        assertTrue(b.security().isEmpty(), "replay after restart logged ${b.security()}")
        net.inject("M", "B", env(post(k, ep, "reused slot", lamport = 2))); net.runFor(500)
        assertEquals(k.idHex, b.security().single { it.kind == SecKind.RATE_EQUIVOCATION }.src)
        assertEquals(1, b.added().count { it.text == "before restart" })
        assertTrue(b.added().none { it.text == "reused slot" })
    }

    /** Slot reuse across an origin restart in the same epoch as its last posts: no slot is reused, so no ban. */
    @Test fun originRestartTwiceInOneEpochReusesNoSlot() {
        val net = SimNetwork()
        pair(net, "A", "B")
        val (a, b) = net.n("A") to net.n("B")
        repeat(3) { a.node.post("first $it") }
        net.runFor(7_100)   // posts go out at the next boundary; we stay inside that epoch
        assertEquals(3, b.added().count { it.text.startsWith("first") })
        repeat(2) { net.linkOf("A", "B").down(); net.runFor(50); a.restart(); net.linkOf("A", "B").up(); net.runFor(200) }
        repeat(3) { a.node.post("second $it") }
        net.runFor(30_000)
        assertEquals(3, b.added().count { it.text.startsWith("second") })
        assertTrue(b.security().isEmpty(), "B: ${b.security()}")
    }

    @Test fun syncWantWithDuplicateIdsSendsOneCopy() {
        val net = SimNetwork()
        pair(net, "M", "B")
        val b = net.n("B")
        b.node.post("stored")
        net.runFor(15_000)
        val pid = Crypto.unhex(b.added().single { it.text == "stored" }.id)
        val before = net.sent.size
        net.inject("M", "B", Codec.encodeFrame(Frame.SyncWant(List(512) { pid })))
        net.runFor(1_000)
        val copies = net.sent.drop(before).count { it.from == "B" && it.env?.msgId?.contentEquals(pid) == true }
        assertTrue(copies <= 1, "one SYNC_WANT made B send $copies copies")
    }

    @Test fun repeatedSyncWantForHeldEnvelopeIsNotResent() {
        val net = SimNetwork()
        pair(net, "M", "B")
        val b = net.n("B")
        b.node.post("held")
        net.runFor(15_000)
        val pid = Crypto.unhex(b.added().single { it.text == "held" }.id)
        assertEquals(1, net.n("M").added().count { it.text == "held" })
        val before = net.sent.size
        repeat(50) { net.inject("M", "B", Codec.encodeFrame(Frame.SyncWant(listOf(pid)))) }
        net.runFor(1_000)
        val copies = net.sent.drop(before).count { it.from == "B" && it.env?.msgId?.contentEquals(pid) == true }
        assertTrue(copies <= 1, "50 SYNC_WANTs for an envelope M already has made B send $copies copies")
    }

    @Test fun mutedPeerIsSentNothingAndIgnored() {
        val net = SimNetwork()
        pair(net, "A", "B")
        val (a, b) = net.n("A") to net.n("B")
        b.node.setMuted(a.id, true)
        net.runFor(100)
        val before = net.sent.size
        a.node.post("can you hear me")
        b.node.post("not for A")
        net.runFor(35_000)   // past a presence and two sync rounds, under the 45 s silent-link limit
        assertTrue(b.added().none { it.text == "can you hear me" }, "muted peer's post accepted")
        assertTrue(a.added().none { it.text == "not for A" }, "muted peer was sent a post")
        assertTrue(net.sent.drop(before).none { it.from == "B" }, "B sent frames to a muted peer")
    }

    @Test fun tamperedDmIsRejectedAndNeverAcked() {
        val net = SimNetwork()
        for (n in listOf("A", "B", "C")) net.node(n)
        net.link("A", "B"); net.link("B", "C"); net.runFor(3_000)
        val (a, b, c) = listOf("A", "B", "C").map { net.n(it) }
        b.node.setDebug(DebugFlag.TAMPER_RELAY, true)
        net.runFor(100)
        a.node.sendDm(c.id, "tamper this dm")
        net.runFor(3_000)
        assertTrue(c.security().any { it.kind == SecKind.BAD_SIGNATURE && it.link == "sim:B" })
        assertTrue(c.added().isEmpty(), "tampered DM delivered")
        assertTrue(net.sent.none { it.from == "C" && it.env?.type == EnvType.ACK }, "C acked a tampered DM")
        assertTrue(a.events.filterIsInstance<MeshEvent.StatusChanged>().none { it.status == MsgStatus.DELIVERED })
        assertTrue(c.security().none { it.kind == SecKind.RATE_EQUIVOCATION || it.kind == SecKind.BANNED })
    }
}
