package mesh.core

import kotlin.test.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Section 10.2, all eleven scenarios, plus the replay-after-LRU-eviction check.
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

    @Test fun t5_tamperingRelayIsBlamedNotTheSource() {
        val net = SimNetwork()
        line(net)
        val (a, b, c) = listOf("A", "B", "C").map { net.nodes.getValue(it) }
        b.node.setDebug(DebugFlag.TAMPER_RELAY, true)
        a.node.post("tamper me")
        net.runFor(15_000)
        val bad = c.security().filter { it.kind == SecKind.BAD_SIGNATURE }
        assertTrue(bad.isNotEmpty(), "C logged no BAD_SIGNATURE")
        assertTrue(bad.all { it.link == "sim:B" })
        assertTrue(c.added().none { it.text.startsWith("tamper") })
        assertTrue(c.security().none { it.kind == SecKind.RATE_EQUIVOCATION || it.kind == SecKind.BANNED })
        // A is not banned at C: once B relays honestly, A's next post arrives.
        b.node.setDebug(DebugFlag.TAMPER_RELAY, false)
        // C may have closed B's link (score below 0) and refuses B for 60 s; a ban on A would last 10 min.
        net.linkOf("B", "C").down(); net.runFor(C.REFUSE_MS + 1_000); net.linkOf("B", "C").up(); net.runFor(1_000)
        a.node.post("clean now")
        net.runFor(20_000)
        assertEquals(1, c.added().count { it.text == "clean now" })
    }

    @Test fun t6_floodAttackBansAtFirstHop() {
        val net = SimNetwork()
        line(net)
        val (a, b, c) = listOf("A", "B", "C").map { net.nodes.getValue(it) }
        a.node.attack(Attack.FLOOD)
        net.runFor(10_000)
        assertEquals(3, b.added().count { it.text.startsWith("flood") })
        val eq = b.security().single { it.kind == SecKind.RATE_EQUIVOCATION }
        assertEquals(a.id, eq.src); assertEquals("sim:A", eq.link)
        assertEquals(3, c.added().count { it.text.startsWith("flood") })
        a.node.post("after ban")
        net.runFor(30_000)
        assertTrue(b.added().none { it.text == "after ban" }, "B accepted a banned source")
        assertTrue(net.sent.none { it.from == "B" && it.env?.let { e -> e.type == EnvType.POST && String(e.body) == "after ban" } == true })
        assertTrue(c.added().none { it.text == "after ban" })
    }

    @Test fun t7_spoofedKeyPenalisesTheLinkNotTheVictim() {
        val net = SimNetwork()
        for (n in listOf("A", "M", "B")) net.node(n)
        net.link("A", "M"); net.link("M", "B")
        net.runFor(3_000)
        val (a, m, b) = listOf("A", "M", "B").map { net.nodes.getValue(it) }
        m.node.attack(Attack.SPOOF)
        net.runFor(5_000)
        for (n in listOf(a, b)) {
            val bad = n.security().filter { it.kind == SecKind.BAD_SIGNATURE }
            assertEquals(1, bad.size, "${n.name} security: ${n.security()}")
            assertEquals("sim:M", bad[0].link)
        }
        val victim = listOf(a, b).single { it.id == a.security().first { s -> s.kind == SecKind.BAD_SIGNATURE }.src }
        val other = if (victim === a) b else a
        victim.node.post("still trusted")
        net.runFor(20_000)
        assertEquals(1, other.added().count { it.text == "still trusted" }, "victim was banned")
        assertTrue(listOf(a, b).all { n -> n.security().none { it.kind == SecKind.RATE_EQUIVOCATION || it.kind == SecKind.BANNED } })
    }

    @Test fun t8b_replayAttackHookIsDroppedSilently() {
        val net = SimNetwork()
        line(net)
        val (a, b, c) = listOf("A", "B", "C").map { net.nodes.getValue(it) }
        a.node.post("replay me")
        net.runFor(15_000)
        val before = net.sent.size
        b.node.attack(Attack.REPLAY)
        net.runFor(2_000)
        val replayed = net.sent.drop(before).filter { it.env?.type == EnvType.POST }
        assertTrue(replayed.isNotEmpty() && replayed.all { it.from == "B" }, "replay was not sent or was forwarded")
        assertTrue(listOf(a, c).all { it.security().isEmpty() })
        assertEquals(1, c.added().count { it.text == "replay me" })
    }

    @Test fun t9_randomGraphBroadcastCost() {
        val net = SimNetwork()
        val rnd = Random(42)
        val names = (0 until 25).map { "N$it" }
        names.forEach { net.node(it) }
        val deg = IntArray(25)
        val edges = HashSet<Pair<Int, Int>>()
        fun add(i: Int, j: Int) { edges += minOf(i, j) to maxOf(i, j); deg[i]++; deg[j]++; net.link(names[i], names[j]) }
        for (i in 1 until 25) add(i, (0 until i).filter { deg[it] < C.MAX_LINKS }.random(rnd))   // spanning tree: connected
        repeat(200) {
            val i = rnd.nextInt(25); val j = rnd.nextInt(25)
            if (edges.size < 40 && i != j && (minOf(i, j) to maxOf(i, j)) !in edges && deg[i] < C.MAX_LINKS && deg[j] < C.MAX_LINKS) add(i, j)
        }
        assertTrue(deg.all { it <= C.MAX_LINKS })
        net.runFor(3_000)
        val src = net.nodes.getValue("N0")
        src.node.post("to everyone")
        net.runFor(20_000)
        assertTrue(net.nodes.values.drop(1).all { n -> n.added().count { it.text == "to everyone" } == 1 }, "not all 25 received it")
        val mid = src.added().single { it.text == "to everyone" }.id
        val tx = net.sent.count { it.env?.let { e -> Crypto.hex(e.msgId) == mid } == true }
        println("10.2 #9: broadcast to 25 nodes: $tx transmissions over ${edges.size} links (bound ${2 * edges.size})")
        assertTrue(tx <= 2 * edges.size, "$tx > 2 x ${edges.size}")
    }

    @Test fun t10_envelopeFloodClosesLink() {
        val net = SimNetwork()
        net.node("M"); net.node("B")
        net.link("M", "B")
        net.runFor(3_000)
        val (m, b) = listOf("M", "B").map { net.nodes.getValue(it) }
        val ep = Math.floorDiv(net.nowMs(), C.EPOCH_SECONDS * 1000L)
        val frames = (0 until 200).map { i ->
            Codec.encodeFrame(Frame.Env(Envelope.create(m.node.id, EnvType.POST, Envelope.BROADCAST, i + 1L, ep - i / 3, i % 3, 3, "spam $i".toByteArray())))
        }
        frames.forEachIndexed { i, f -> net.schedule(i * 10L) { net.inject("M", "B", f) } }
        net.runFor(3_000)
        assertTrue(b.security().any { it.kind == SecKind.LINK_FLOOD && it.link == "sim:M" })
        assertTrue(b.security().any { it.kind == SecKind.LINK_CLOSED && it.link == "sim:M" })
        assertFalse(net.linkOf("M", "B").isUp, "link to M still open")
    }

    @Test fun t11_restartMidEpochWaitsForNextEpoch() {
        val net = SimNetwork()
        line(net)
        val (a, b) = listOf("A", "B").map { net.nodes.getValue(it) }
        repeat(3) { a.node.post("before $it") }
        net.runFor(5_000)   // posts go out at the next epoch boundary
        assertEquals(3, b.added().count { it.text.startsWith("before") })
        net.linkOf("A", "B").down(); net.runFor(100)
        a.restart()
        val restartEpoch = Math.floorDiv(net.nowMs(), C.EPOCH_SECONDS * 1000L)
        net.linkOf("A", "B").up(); net.runFor(1_000)
        a.node.post("after restart")
        net.runFor(20_000)
        val f = net.sent.single { it.from == "A" && it.env?.let { e -> e.type == EnvType.POST && String(e.body) == "after restart" } == true }
        assertTrue(f.env!!.epoch > restartEpoch, "first post after restart reused the restart epoch")
        assertTrue(f.atMs >= (restartEpoch + 1) * C.EPOCH_SECONDS * 1000L)
        assertEquals(1, b.added().count { it.text == "after restart" })
        assertTrue(b.security().isEmpty(), "B: ${b.security()}")
    }

    /** 5.5 step 4: a link that never completes HELLO is closed after HELLO_TIMEOUT, so it cannot hold a link slot. */
    @Test fun silentPeerIsClosedAfterHelloTimeout() {
        val net = SimNetwork()
        net.node("S"); net.node("B")
        net.nodes.getValue("S").node.stop()   // S's transport has no listener: it never answers HELLO
        net.link("S", "B")
        net.runFor(C.HELLO_TIMEOUT_MS - 100)
        assertTrue(net.linkOf("S", "B").isUp)
        net.runFor(200)
        assertFalse(net.linkOf("S", "B").isUp, "unauthenticated link still open")
        assertTrue(net.nodes.getValue("B").security().any { it.kind == SecKind.LINK_CLOSED && it.link == "sim:S" })
    }

    /** Lead's extra: a replay after its msgId fell out of the SEEN_CAP LRU must still be dropped (POST and DM). */
    @Test fun replayAfterSeenEvictionIsStillDropped() {
        val net = SimNetwork()
        net.node("M"); net.node("B")
        net.link("M", "B")
        net.runFor(3_000)
        val (m, b) = listOf("M", "B").map { net.nodes.getValue(it) }
        m.node.post("old post"); m.node.sendDm(b.id, "old dm")
        net.runFor(15_000)
        assertEquals(2, b.added().size)
        val old = net.sent.filter { it.from == "M" && (it.env?.type == EnvType.POST || it.env?.type == EnvType.DM) }.map { it.frame }
        assertEquals(2, old.size)
        // Push SEEN_CAP + 10 fresh PRESENCE ids through B's seen set (distinct keys, under the link token rate).
        val ep = Math.floorDiv(net.nowMs(), C.EPOCH_SECONDS * 1000L)
        repeat(C.SEEN_CAP + 10) { i ->
            val f = Codec.encodeFrame(Frame.Env(Envelope.create(Identity.generate(), EnvType.PRESENCE, Envelope.BROADCAST, 1, ep, 0, 1, byteArrayOf(1, 'x'.code.toByte()))))
            net.schedule(i * 400L) { net.inject("M", "B", f) }
        }
        net.runFor((C.SEEN_CAP + 10) * 400L + 1_000)
        assertTrue(b.security().none { it.kind == SecKind.LINK_FLOOD }, "presence pacing tripped the bucket")
        assertTrue(b.node.snapshot().contacts.size <= 257, "contacts not capped: ${b.node.snapshot().contacts.size}")
        old.forEach { net.inject("M", "B", it) }
        net.runFor(2_000)
        assertEquals(2, b.added().size, "replay re-accepted: ${b.added().map { it.text }}")
    }
}
