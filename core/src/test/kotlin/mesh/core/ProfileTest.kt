package mesh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// U3 mission profiles: mixed-profile line A(TUNNEL) - B(CROWD) - C(SPARSE).
class ProfileTest {
    private fun line(net: SimNetwork, bcUp: Boolean = true): List<SimNode> {
        val (a, b, c) = listOf("A", "B", "C").map { net.node(it) }
        a.node.setProfile(Profile.TUNNEL); b.node.setProfile(Profile.CROWD); c.node.setProfile(Profile.SPARSE)
        net.link("A", "B"); net.link("B", "C", up = bcUp)
        net.runFor(3_000)
        return listOf(a, b, c)
    }

    private fun honest(net: SimNetwork) = assertTrue(net.nodes.values.all { it.security().isEmpty() }, "mixed profiles logged security events")

    /** Acceptance checks 1 and 3: 2-hop DM delivered, a post once on each node; idle past 45 s first so CROWD's slow presence must not trip the silent-link close. */
    @Test fun mixedLineDmAndPost() {
        val net = SimNetwork()
        val (a, b, c) = line(net)
        net.runFor(120_000)
        assertTrue(net.linkOf("A", "B").isUp && net.linkOf("B", "C").isUp, "mixed-profile link closed as silent")

        a.node.sendDm(c.id, "secret")
        net.runFor(5_000)
        val got = c.added().single { it.text == "secret" }
        assertEquals(2, got.hops)
        assertEquals(MsgStatus.DELIVERED, a.events.filterIsInstance<MeshEvent.StatusChanged>().last().status)

        c.node.post("hello mixed")
        net.runFor(15_000)
        for (n in listOf(a, b)) assertEquals(1, n.added().count { it.text == "hello mixed" }, "${n.name} copies")
        honest(net)

        // Changing profile alters no validated value: every envelope on the wire has the fixed TTLs and PoW bits.
        for (e in net.sent.mapNotNull { it.env }) {
            val cap = when (e.type) { EnvType.POST -> C.TTL_POST; EnvType.DM -> C.TTL_DM; EnvType.ACK -> C.TTL_ACK; else -> C.TTL_PRESENCE }
            assertEquals(cap, e.initTtl, "type ${e.type}")
            assertTrue(e.ttl <= cap)
            if (e.type == EnvType.POST || e.type == EnvType.DM) assertEquals(C.powBits(e.body.size, e.initTtl), Pow.bits(e))
        }
    }

    /** Acceptance check 6: a post made during a partition is caught up at link-up. */
    @Test fun mixedLinePartitionCatchUp() {
        val net = SimNetwork()
        val (a, _, c) = line(net, bcUp = false)
        a.node.post("while you were away")
        net.runFor(15_000)
        assertTrue(c.added().isEmpty())
        net.linkOf("B", "C").up()
        net.runFor(Profile.CROWD.syncMs)
        assertEquals(1, c.added().count { it.text == "while you were away" })
        honest(net)
    }

    @Test fun profileValuesAndPersistence() {
        val net = SimNetwork()
        val a = net.node("A")
        assertEquals("STANDARD", a.node.snapshot().profile)
        assertEquals(Profile.STANDARD.info(), a.node.snapshot().profileValues)
        a.node.setProfile(Profile.TUNNEL)
        net.runFor(10)
        a.restart()
        val s = a.node.snapshot()
        assertEquals("TUNNEL", s.profile)
        assertEquals(ProfileValues(15_000, 5_000, 0, 20, 5, 512, true), s.profileValues)
        assertTrue(Profile.SPARSE.longRange && !Profile.CROWD.longRange && !Profile.STANDARD.longRange)
        // STANDARD is the pre-U3 behaviour.
        assertEquals(ProfileValues(C.PRESENCE_INTERVAL_MS, C.SYNC_INTERVAL_MS, C.JITTER_MIN_MS, C.JITTER_MAX_MS, C.MAX_LINKS, C.RELAY_STORE_CAP, false), Profile.STANDARD.info())
    }

    /** A lone CROWD peer (60 s presence) with nothing to offer still keeps a STANDARD peer's 45 s silent-link check quiet. */
    @Test fun crowdPairStaysLinked() {
        val net = SimNetwork()
        net.node("S"); net.node("K").node.setProfile(Profile.CROWD)
        net.link("S", "K")
        net.runFor(180_000)
        assertTrue(net.linkOf("S", "K").isUp, "CROWD link closed as silent")
        honest(net)
    }

    /** Lowering max links keeps existing links and refuses new ones above the cap. */
    @Test fun crowdMaxLinksRefusesNewOnly() {
        val net = SimNetwork()
        val hub = net.node("H")
        val leaves = (1..5).map { net.node("L$it") }
        leaves.take(5).forEach { net.link("H", it.name) }
        net.runFor(3_000)
        hub.node.setProfile(Profile.CROWD)
        net.runFor(10)
        assertEquals(5, hub.node.snapshot().links.size, "existing links closed")
        net.link("H", net.node("L6").name)
        net.runFor(3_000)
        assertEquals(5, hub.node.snapshot().links.size)
    }
}
