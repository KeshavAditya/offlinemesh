package mesh.redteam

import mesh.core.C
import mesh.core.Crypto
import mesh.core.SimNetwork
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Agent D: one POST's transmissions vs link count on random connected graphs (same builder as 10.2 #9), for docs/NUMBERS.md.
class BroadcastCostTest {
    private class Run(val links: Int, val tx: Int, val received: Int, val within6: Int, val honestSecurityEvents: Int)

    private fun measure(n: Int, maxEdges: Int, seed: Int): Run {
        val net = SimNetwork()
        val rnd = Random(seed)
        val names = (0 until n).map { "N$it" }
        names.forEach { net.node(it) }
        val deg = IntArray(n)
        val edges = HashSet<Pair<Int, Int>>()
        fun add(i: Int, j: Int) { edges += minOf(i, j) to maxOf(i, j); deg[i]++; deg[j]++; net.link(names[i], names[j]) }
        for (i in 1 until n) add(i, (0 until i).filter { deg[it] < C.MAX_LINKS }.random(rnd))
        repeat(20 * n) {
            val i = rnd.nextInt(n); val j = rnd.nextInt(n)
            if (edges.size < maxEdges && i != j && (minOf(i, j) to maxOf(i, j)) !in edges && deg[i] < C.MAX_LINKS && deg[j] < C.MAX_LINKS) add(i, j)
        }
        net.runFor(3_000)
        val src = net.nodes.getValue("N0")
        src.node.post("cost")
        net.runFor(20_000)
        val dist = IntArray(n) { -1 }.also { it[0] = 0 }
        val queue = ArrayDeque(listOf(0))
        while (queue.isNotEmpty()) { val i = queue.removeFirst(); for ((a, b) in edges) for ((x, y) in listOf(a to b, b to a)) if (x == i && dist[y] < 0) { dist[y] = dist[i] + 1; queue += y } }
        val counts = names.drop(1).map { name -> net.nodes.getValue(name).added().count { it.text == "cost" } }
        assertTrue(counts.all { it <= 1 }, "n=$n: duplicate delivery")
        val mid = src.added().single { it.text == "cost" }.id
        val tx = net.sent.count { it.env?.let { e -> Crypto.hex(e.msgId) == mid } == true }
        assertTrue(tx <= 2 * edges.size, "n=$n: $tx > 2 x ${edges.size}")
        val sec = net.nodes.values.sumOf { it.security().size }
        return Run(edges.size, tx, counts.sum(), (1 until n).count { dist[it] in 1..6 }, sec).also {
            println("BCAST nodes=$n links=${it.links} tx=${it.tx} bound=${2 * it.links} received=${it.received}/${n - 1} within6Hops=${it.within6} honestSecurityEvents=$sec")
        }
    }

    @Test fun broadcastCostGrowsWithLinksNotNodes() {
        for ((n, e) in listOf(3 to 2, 10 to 15, 25 to 40)) {
            val r = measure(n, e, 7)
            assertEquals(n - 1, r.received, "n=$n: not all received")
            assertEquals(0, r.honestSecurityEvents, "n=$n: honest nodes logged security events")
        }
    }

    // Was a bug: start-up PRESENCE floods and the link-up replay (up to 64) tripped the receivers' 30-per-10 s bucket.
    // Fixed by sender-side pacing in MeshNode.give().
    @Test fun fiftyHonestNodesTripNoLinkFlood() {
        val r = measure(50, 90, 7)
        assertEquals(0, r.honestSecurityEvents)
        assertEquals(r.within6, r.received)
    }
}
