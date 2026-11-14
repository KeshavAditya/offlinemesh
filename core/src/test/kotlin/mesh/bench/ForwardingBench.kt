package mesh.bench

import mesh.core.C
import mesh.core.DebugFlag
import mesh.core.EnvType
import mesh.core.SimNetwork
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

// U11: forwarding benchmark over the virtual-time simulator. Run with ./gradlew :core:bench (excluded from :core:test).
// Writes its own section of docs/NUMBERS.md between the BENCH markers.
class ForwardingBench {
    private class Topo(val name: String, val n: Int, val edges: List<Pair<Int, Int>>)

    private class Result(
        val postRatio: Double, val dmRatio: Double, val sendsPerPost: Double, val bytesPerDeliveredPost: Double,
        val dmTxPerDm: Double, val ackTxPerDm: Double, val medianMs: Long, val p95Ms: Long, val p99Ms: Long,
    )

    private val seed = 11

    private fun line3() = Topo("line of 3", 3, listOf(0 to 1, 1 to 2))

    private fun grid5() = Topo("5x5 grid", 25, buildList {
        for (r in 0 until 5) for (c in 0 until 5) { val i = r * 5 + c; if (c < 4) add(i to i + 1); if (r < 4) add(i to i + 5) }
    })

    /** Nodes at random in a [size] m square; each pair among either end's 5 nearest (and within [range] m) is linked,
     *  shortest first, while both ends have fewer than MAX_LINKS links (MeshNode refuses a 6th). */
    private fun random(name: String, n: Int, size: Double, range: Double): Topo {
        val rnd = Random(seed)
        val pts = List(n) { rnd.nextDouble(size) to rnd.nextDouble(size) }
        fun d(i: Int, j: Int) = hypot(pts[i].first - pts[j].first, pts[i].second - pts[j].second)
        val near = List(n) { i -> (0 until n).filter { it != i }.sortedBy { d(i, it) }.take(5).toSet() }
        val deg = IntArray(n)
        val edges = ArrayList<Pair<Int, Int>>()
        val pairs = (0 until n).flatMap { i -> (i + 1 until n).map { i to it } }.filter { (i, j) -> j in near[i] || i in near[j] }
        for ((i, j) in pairs.sortedBy { d(it.first, it.second) }) {
            if (d(i, j) <= range && deg[i] < C.MAX_LINKS && deg[j] < C.MAX_LINKS) { edges += i to j; deg[i]++; deg[j]++ }
        }
        return Topo(name, n, edges)
    }

    private val room = random("30 in a room", 30, 10.0, 1e9)
    private val wide = random("50 over a wide area", 50, 100.0, 25.0)

    private fun run(t: Topo, naive: Boolean, dropRelayFraction: Double = 0.0, posts: Int = 2, dms: Int = 10, failover: Boolean = !naive): Result {
        val net = SimNetwork()
        val rnd = Random(seed)
        val names = List(t.n) { "N$it" }
        names.forEach { net.node(it).node.also { n -> n.naiveFlood = naive; n.failover = failover } }
        t.edges.forEach { (a, b) -> net.link(names[a], names[b]) }
        net.runFor(C.PRESENCE_INTERVAL_MS + 10_000)   // PRESENCE settles: every node has beaconed at least twice
        // Black holes turn bad after routes formed (DROP_RELAY also stops their PRESENCE relaying, which would hide them).
        names.shuffled(rnd).take((t.n * dropRelayFraction).toInt()).forEach { net.nodes.getValue(it).node.setDebug(DebugFlag.DROP_RELAY, true) }
        net.sent.clear()

        val sentAt = HashMap<String, Long>()
        val start = net.nowMs()
        for ((i, from) in names.withIndex()) {
            val sim = net.nodes.getValue(from)
            repeat(posts) { k -> val at = rnd.nextLong(WINDOW_MS); val text = "p:$from:$k"
                net.schedule(at) { sentAt[text] = net.nowMs(); sim.node.post(text) } }
            repeat(dms) { k -> val at = rnd.nextLong(WINDOW_MS); val text = "d:$from:$k"
                val to = names[(i + 1 + rnd.nextInt(t.n - 1)) % t.n]
                net.schedule(at) { sentAt[text] = net.nowMs(); sim.node.sendDm(net.nodes.getValue(to).id, text) } }
        }
        net.runFor(WINDOW_MS + DRAIN_MS)

        val delays = ArrayList<Long>()
        var postGot = 0; var dmGot = 0
        for (sim in net.nodes.values) for (m in sim.added()) {
            if (m.mine) continue
            val at = sentAt[m.text] ?: continue
            delays += m.tsMs - at
            if (m.text.startsWith("p:")) postGot++ else dmGot++
        }
        var postTx = 0; var postBytes = 0L; var dmTx = 0; var ackTx = 0
        for (s in net.sent) when (s.env?.type) {
            EnvType.POST -> { postTx++; postBytes += s.frame.size }
            EnvType.DM -> dmTx++
            EnvType.ACK -> ackTx++
        }
        val nPosts = t.n * posts; val nDms = t.n * dms
        delays.sort()
        fun pct(p: Double) = if (delays.isEmpty()) -1 else delays[((delays.size - 1) * p).toInt()]
        return Result(
            postGot.toDouble() / maxOf(1, nPosts * (t.n - 1)), dmGot.toDouble() / nDms, postTx.toDouble() / maxOf(1, nPosts),
            postBytes.toDouble() / maxOf(1, postGot), dmTx.toDouble() / maxOf(1, dmGot), ackTx.toDouble() / maxOf(1, dmGot),
            pct(0.5), pct(0.95), pct(0.99),
        ).also { assertTrue(it.postRatio <= 1.0 && it.dmRatio <= 1.0, "duplicate deliveries in ${t.name}") }
    }

    @Test fun bench() {
        val t0 = System.nanoTime()
        // Runs are independent networks; run them on parallel threads (PoW mining dominates the wall time).
        val topos = listOf(line3(), grid5(), room, wide)
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val main = topos.flatMap { t -> listOf(true, false).map { naive -> Triple(t, naive, pool.submit(Callable { run(t, naive) })) } }
        val failRuns = listOf("NAIVE" to (true to false), "CURRENT without U10" to (false to false), "CURRENT with U10" to (false to true)).map { (mode, cfg) ->
            mode to pool.submit(Callable { run(room, cfg.first, dropRelayFraction = 0.1, posts = 0, failover = cfg.second) }) }
        val rows = StringBuilder()
        for ((t, naive, f) in main) {
            val r = f.get()
            val mode = if (naive) "NAIVE" else "CURRENT"
            println("BENCH ${t.name} $mode post=%.3f dm=%.3f sendsPerPost=%.1f txPerDm=%.2f".format(r.postRatio, r.dmRatio, r.sendsPerPost, r.dmTxPerDm))
            rows.append("| ${t.name} (${t.n} nodes, ${t.edges.size} links) | $mode | %.3f | %.3f | %.1f | %.0f | %.2f | %.2f | %d | %d |\n".format(
                r.postRatio, r.dmRatio, r.sendsPerPost, r.bytesPerDeliveredPost, r.dmTxPerDm, r.ackTxPerDm, r.medianMs, r.p95Ms))
        }
        val fail = StringBuilder()
        for ((mode, f) in failRuns) {
            val r = f.get()
            println("BENCH room DROP_RELAY $mode dm=%.3f p95=%d p99=%d".format(r.dmRatio, r.p95Ms, r.p99Ms))
            fail.append("| $mode | %.3f | %.2f | %d | %d | %d |\n".format(r.dmRatio, r.dmTxPerDm, r.medianMs, r.p95Ms, r.p99Ms))
        }
        pool.shutdown()
        val secs = (System.nanoTime() - t0) / 1e9
        val section = """
$BEGIN
## Forwarding benchmark (U11, simulator, real core code, virtual time)

Generated by `./gradlew :core:bench` (core/src/test/kotlin/mesh/bench/ForwardingBench.kt); do not edit by hand. Wall time ${"%.0f".format(secs)} s.

Setup: topology seed $seed; links have ${SimNetwork.LATENCY_MS} ms latency. Random layouts link each node to its 5 nearest, shortest first, capped at ${C.MAX_LINKS} links per node (MeshNode refuses more); the wide area is 100 m x 100 m with a 25 m range, the room 10 m x 10 m.
Workload: PRESENCE settles for ${(C.PRESENCE_INTERVAL_MS + 10_000) / 1000} s, then every node sends 2 posts and 10 DMs to random other nodes at random times over ${WINDOW_MS / 1000} s, then ${DRAIN_MS / 1000} s more to deliver. Full workload, not shrunk.
NAIVE: every new envelope goes to every link except the one it came on (a test-only switch, `MeshNode.naiveFlood`), without U10 failover. CURRENT: guide 5.7 as built, with U10 DM failover. ACKs walk the DM's path back in both modes. Key pairs and forwarding jitter are not seeded, so reruns differ slightly.

Columns: delivery ratios (posts: copies received / posts x (nodes - 1); DMs: received / sent); full POST envelope frames sent per post (relays and sync answers); POST frame bytes on the air per delivered post copy; DM envelope frames and ACK frames per delivered DM; median and 95th-percentile delivery time over all delivered posts and DMs, virtual ms.

| Topology | Mode | Post delivery | DM delivery | Sends per post | Bytes per delivered post | DM tx per delivered DM | ACK tx per delivered DM | Median ms | p95 ms |
|---|---|---|---|---|---|---|---|---|---|
$rows
### Failure run: 30 in a room, 10% of nodes on DROP_RELAY

The same room, 3 of 30 nodes switched to DROP_RELAY after PRESENCE settled; DMs only (10 per node). Periodic SYNC (every ${C.SYNC_INTERVAL_MS / 1000} s) also hands stored DMs to non-dropping neighbours, so compare the delay columns with the room rows above. U10: an own DM with no ACK in 3 s + 1 s per route hop is resent on a backup route through a different first link (else flooded once), at most twice; "without U10" switches that off (`MeshNode.failover`).

| Mode | DM delivery | DM tx per delivered DM | Median ms | p95 ms | p99 ms |
|---|---|---|---|---|---|
$fail$END
"""
        val file = File(System.getProperty("bench.out") ?: "../docs/NUMBERS.md")
        val old = file.readText()
        val i = old.indexOf(BEGIN); val j = old.indexOf(END)
        file.writeText(if (i >= 0 && j > i) old.substring(0, i).trimEnd() + "\n" + section.trimEnd() + old.substring(j + END.length) else old.trimEnd() + "\n" + section)
        println("BENCH wrote ${file.canonicalPath} in %.0f s".format(secs))
    }

    companion object {
        const val WINDOW_MS = 120_000L
        const val DRAIN_MS = 120_000L
        const val BEGIN = "<!-- BEGIN U11 BENCH (generated by ./gradlew :core:bench) -->"
        const val END = "<!-- END U11 BENCH -->"
    }
}
