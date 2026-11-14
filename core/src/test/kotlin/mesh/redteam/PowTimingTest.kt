package mesh.redteam

import mesh.core.C
import mesh.core.Pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

// Agent D: measures PoW mining time on this JVM for docs/NUMBERS.md. Mining cost does not depend on core length (core is hashed once).
class PowTimingTest {
    private fun time(bits: Int, runs: Int): Pair<Double, Double> {
        var nonces = 0L
        val t0 = System.nanoTime()
        repeat(runs) { nonces += Pow.mine(Random.nextBytes(200), bits) + 1 }
        val ms = (System.nanoTime() - t0) / 1e6
        return ms / runs to nonces * 1000.0 / ms
    }

    @Test fun powMiningTimes() {
        Pow.mine(Random.nextBytes(200), 12) // warm up the JIT
        val post = C.powBits(1_000, 6)        // largest POST
        val dm = C.powBits(40_028, 8)         // 40,000 B plaintext + 12 B IV + 16 B tag
        val (postMs, postRate) = time(post, 40)
        val (dmMs, dmRate) = time(dm, 4)
        println("POW post bits=$post avgMs=%.1f hashesPerSec=%.0f postsPerSecOneCore=%.1f".format(postMs, postRate, 1000 / postMs))
        println("POW dm40k bits=$dm avgMs=%.1f hashesPerSec=%.0f".format(dmMs, dmRate))
        assertTrue(post == 15 && dm == 22)
    }
}
