package mesh.core

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Data mule: stored envelopes carried in one signed file between nodes with no link; every ingress check still applies.
class BundleTest {
    /** The plan's OMB1 layout, written independently of exportBundle. */
    private fun bundleOf(signer: Identity, envs: List<ByteArray>): ByteArray {
        val b = ByteBuffer.allocate(16 + envs.sumOf { 4 + it.size } + signer.publicDer.size + 200)
        b.put("OMB1".toByteArray()).putInt(envs.size)
        envs.forEach { b.putInt(it.size).put(it) }
        b.putShort(signer.publicDer.size.toShort()).put(signer.publicDer)
        val signed = b.array().copyOf(b.position())
        val sig = Crypto.sign(signer.private, signed)
        return signed + ByteBuffer.allocate(2).putShort(sig.size.toShort()).array() + sig
    }

    private fun envsOf(bundle: ByteArray): List<ByteArray> {
        val b = ByteBuffer.wrap(bundle, 4, bundle.size - 4)
        return List(b.int) { ByteArray(b.int).also { a -> b.get(a) } }
    }

    private fun setup(): Triple<SimNetwork, SimNode, SimNode> {
        val net = SimNetwork()
        val (a, c) = listOf("A", "C").map { net.node(it) }
        net.runFor(1_000)
        listOf("one", "two", "three").forEach { a.node.post(it) }
        net.runFor(C.EPOCH_SECONDS * 1000L + 1_000)
        return Triple(net, a, c)
    }

    @Test fun exportedBundleImportsIntoUnlinkedNode() {
        val (net, a, c) = setup()
        val file = a.node.exportBundle()
        assertEquals(3, envsOf(file).size)
        val r = assertNotNull(c.node.importBundle(file))
        assertEquals(BundleResult(a.id, 3, 3, 0, 0), r)
        assertEquals(setOf("one", "two", "three"), c.added().map { it.text }.toSet())
        assertTrue(c.added().all { it.from == a.id })
        assertEquals(BundleResult(a.id, 3, 0, 3, 0), c.node.importBundle(file), "a re-import is all duplicates")
        assertTrue(net.sent.none { it.to == "bundle" || it.from == "bundle" })
    }

    @Test fun tamperedOrOversizedBundleIsRejectedWhole() {
        val (_, a, c) = setup()
        val file = a.node.exportBundle()
        for (i in listOf(5, file.size / 2, file.size - 1)) {
            val bad = file.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull(c.node.importBundle(bad), "flipped byte $i")
        }
        assertNull(c.node.importBundle(file.copyOf(file.size - 1)), "truncated")
        assertNull(c.node.importBundle("OMB1".toByteArray() + ByteArray(C.BUNDLE_MAX_BYTES)), "oversized")
        assertTrue(c.added().isEmpty())
        assertTrue(c.security().any { it.link == "bundle" && it.kind == SecKind.MALFORMED })
    }

    @Test fun badPowEnvelopeInSignedBundleIsRejectedAlone() {
        val (_, a, c) = setup()
        val envs = envsOf(a.node.exportBundle()).map { Codec.decodeEnvelope(it)!! }
        val victim = envs.first { String(it.body) == "three" }
        val badPow = generateSequence(victim.powNonce + 1) { it + 1 }.map { victim.copy(powNonce = it) }.first { !Pow.verify(it) }
        val mule = Identity.generate()   // any carrier can sign; the bundle signature only vouches for the file
        val file = bundleOf(mule, envs.map { if (it === victim) badPow else it }.map(Codec::encodeEnvelope))
        assertEquals(BundleResult(mule.idHex, 3, 2, 0, 1), c.node.importBundle(file))
        assertEquals(setOf("one", "two"), c.added().map { it.text }.toSet())
        assertTrue(c.security().any { it.link == "bundle" && it.kind == SecKind.BAD_POW })
    }
}
