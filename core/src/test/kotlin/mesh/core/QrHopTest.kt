package mesh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// U6: one envelope carried by QR between two nodes with no link; the full ingress pipeline still applies.
class QrHopTest {
    private fun postFrom(a: SimNode, net: SimNetwork, text: String): String {
        a.node.post(text)
        net.runFor(C.EPOCH_SECONDS * 1000L + 1_000)   // own posts wait for the epoch after start-up
        return a.added().last { it.text == text }.id
    }

    @Test fun exportedPostImportsVerifiedAndOnceOnly() {
        val net = SimNetwork()
        val (a, x) = listOf("A", "X").map { net.node(it) }
        net.runFor(1_000)
        val mid = postFrom(a, net, "hello by qr")
        val bytes = assertNotNull(a.node.exportEnvelope(mid))
        val e = assertNotNull(Codec.decodeEnvelope(bytes))
        assertTrue(Pow.verify(e) && Crypto.verify(e.srcPub, e.core, e.sig))

        // One flipped body byte: the PoW or signature check fails; logged on link "qr", no crash, nothing shown.
        val bad = bytes.copyOf().also { val i = e.core.size - 3; it[i] = (it[i].toInt() xor 1).toByte() }
        assertFalse(x.node.importEnvelope(bad))
        assertTrue(x.security().any { it.link == "qr" && (it.kind == SecKind.BAD_POW || it.kind == SecKind.BAD_SIGNATURE) })
        assertFalse(x.node.importEnvelope(byteArrayOf(1, 2, 3)))
        assertTrue(x.security().any { it.link == "qr" && it.kind == SecKind.MALFORMED })

        assertTrue(x.node.importEnvelope(bytes))
        val got = x.added().single()
        assertEquals(a.id, got.from); assertEquals("hello by qr", got.text); assertEquals(mid, got.id)
        assertTrue(x.node.snapshot().feed.any { it.id == mid })

        assertFalse(x.node.importEnvelope(bytes), "a re-scan is a duplicate")
        assertEquals(1, x.added().size)
    }

    @Test fun importedPostIsForwardedOnceLinked() {
        val net = SimNetwork()
        val (a, x, c) = listOf("A", "X", "C").map { net.node(it) }
        net.runFor(1_000)
        val mid = postFrom(a, net, "relay me")
        assertTrue(x.node.importEnvelope(a.node.exportEnvelope(mid)!!))
        net.runFor(2_000)
        net.link("X", "C")
        net.runFor(20_000)
        assertEquals(a.id, c.added().single { it.id == mid }.from)
        assertTrue(net.sent.none { it.to == "qr" || it.from == "qr" })
    }

    @Test fun bigOrUnknownIsNotExported() {
        val net = SimNetwork()
        val a = net.node("A")
        net.runFor(1_000)
        val mid = postFrom(a, net, "x".repeat(400))
        assertNull(a.node.exportEnvelope(mid))
        assertNull(a.node.exportEnvelope("00".repeat(16)))
    }
}
