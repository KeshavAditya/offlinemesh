package mesh.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Section 10.1.
class UnitTest {
    private val a = Identity.generate()
    private val b = Identity.generate()

    private fun env(type: Int, body: ByteArray, initTtl: Int = 6, dst: ByteArray = Envelope.BROADCAST, slot: Int = 0) =
        Envelope.create(a, type, dst, 42, 170_000_000, slot, initTtl, body)

    private fun dmBody(): ByteArray {
        val key = Crypto.dmKey(a.private, b.publicDer)
        return Crypto.seal(key, a.nodeId + b.nodeId, byteArrayOf(0) + "hi".toByteArray())
    }

    private fun allEnvelopes() = listOf(
        env(EnvType.PRESENCE, byteArrayOf(5) + "alice".toByteArray()),
        env(EnvType.POST, "hello mesh".toByteArray(), slot = 2),
        env(EnvType.DM, dmBody(), C.TTL_DM, b.nodeId),
        env(EnvType.ACK, ByteArray(8) { it.toByte() }, C.TTL_ACK, b.nodeId),
    )

    private fun allFrames() = allEnvelopes().map { Frame.Env(it) } + listOf(
        Frame.Hello(a.publicDer, Crypto.randomBytes(16)),
        Frame.HelloAuth(Crypto.randomBytes(70)),
        Frame.SyncOffer(List(3) { Crypto.randomBytes(8) }),
        Frame.SyncWant(emptyList()),
        Frame.SyncWant(List(1) { Crypto.randomBytes(8) }),
    )

    // ---- Codec ----

    @Test fun envelopeRoundTrip() {
        for (e in allEnvelopes()) {
            val bytes = Codec.encodeEnvelope(e)
            val d = assertNotNull(Codec.decodeEnvelope(bytes), "type ${e.type}")
            assertContentEquals(bytes, Codec.encodeEnvelope(d))
            assertContentEquals(e.msgId, d.msgId)
            assertContentEquals(a.nodeId, d.srcId)
            assertEquals(e.lamport, d.lamport); assertEquals(e.epoch, d.epoch); assertEquals(e.slot, d.slot)
            assertEquals(e.initTtl, d.ttl); assertEquals(0, d.hops)
            assertTrue(Crypto.verify(d.srcPub, d.core, d.sig))
        }
    }

    @Test fun frameRoundTrip() {
        for (f in allFrames()) {
            val bytes = Codec.encodeFrame(f)
            val d = assertNotNull(Codec.decodeFrame(bytes), f::class.simpleName)
            assertEquals(f::class, d::class)
            assertContentEquals(bytes, Codec.encodeFrame(d))
        }
    }

    @Test fun truncatedAndTrailingRejected() {
        for (f in allFrames()) {
            val bytes = Codec.encodeFrame(f)
            for (n in 0 until bytes.size) assertNull(Codec.decodeFrame(bytes.copyOf(n)), "${f::class.simpleName} cut to $n")
            assertNull(Codec.decodeFrame(bytes + 0), "${f::class.simpleName} + trailing byte")
        }
        for (e in allEnvelopes()) assertNull(Codec.decodeEnvelope(Codec.encodeEnvelope(e) + 7))
    }

    @Test fun oversizedAndInvalidRejected() {
        assertNull(Codec.decodeFrame(ByteArray(C.MAX_FRAME + 1).also { it[0] = FrameType.ENVELOPE.toByte() }))
        assertNull(Codec.decodeFrame(byteArrayOf(0x7f)))                                      // unknown frame type
        assertNull(Codec.decodeEnvelope(Codec.encodeEnvelope(env(EnvType.POST, "x".toByteArray())).also { it[1] = 9 }))
        val longPost = env(EnvType.PRESENCE, byteArrayOf(1, 65)).copy(type = EnvType.POST, body = ByteArray(C.MAX_POST_BODY + 1))
        assertNull(Codec.decodeEnvelope(Codec.encodeEnvelope(longPost)))
        val longName = env(EnvType.PRESENCE, byteArrayOf(1, 65)).copy(body = byteArrayOf(33) + ByteArray(33))
        assertNull(Codec.decodeEnvelope(Codec.encodeEnvelope(longName)))
        val badAck = env(EnvType.ACK, ByteArray(8)).copy(body = ByteArray(9))
        assertNull(Codec.decodeEnvelope(Codec.encodeEnvelope(badAck)))
        val noncedPresence = env(EnvType.PRESENCE, byteArrayOf(0)).copy(powNonce = 1)
        assertNull(Codec.decodeEnvelope(Codec.encodeEnvelope(noncedPresence)))
        assertFailsWith<IllegalArgumentException> { Codec.encodeFrame(Frame.SyncOffer(List(8000) { ByteArray(8) })) }
    }

    @Test fun streamFraming() {
        val f = Codec.encodeFrame(Frame.HelloAuth(byteArrayOf(1, 2, 3)))
        val out = ByteArrayOutputStream().also { Codec.writeFrame(it, f) }
        assertContentEquals(f, Codec.readFrame(DataInputStream(ByteArrayInputStream(out.toByteArray()))))
        for (len in listOf(0, C.MAX_FRAME + 1, -1)) {
            val bad = ByteArrayOutputStream().also { java.io.DataOutputStream(it).writeInt(len) }.toByteArray()
            assertFailsWith<IOException> { Codec.readFrame(DataInputStream(ByteArrayInputStream(bad + ByteArray(8)))) }
        }
    }

    // ---- Crypto ----

    @Test fun signVerifyAndFlippedBit() {
        val data = "core bytes".toByteArray()
        val sig = Crypto.sign(a.private, data)
        assertTrue(Crypto.verify(a.publicDer, data, sig))
        assertFalse(Crypto.verify(b.publicDer, data, sig))
        for (i in 0 until data.size * 8) {
            val d = data.copyOf().also { it[i / 8] = (it[i / 8].toInt() xor (1 shl (i % 8))).toByte() }
            assertFalse(Crypto.verify(a.publicDer, d, sig), "flipped data bit $i")
        }
        val s = sig.copyOf().also { it[sig.size - 1] = (it[sig.size - 1].toInt() xor 1).toByte() }
        assertFalse(Crypto.verify(a.publicDer, data, s))
        assertFalse(Crypto.verify(byteArrayOf(1, 2, 3), data, sig))                          // junk key never throws
    }

    @Test fun ecdhAgrees() {
        assertContentEquals(Crypto.ecdh(a.private, b.publicDer), Crypto.ecdh(b.private, a.publicDer))
        assertContentEquals(Crypto.dmKey(a.private, b.publicDer), Crypto.dmKey(b.private, a.publicDer))
        assertEquals(32, Crypto.dmKey(a.private, b.publicDer).size)
    }

    @Test fun gcmRoundTripAndFailures() {
        val key = Crypto.dmKey(a.private, b.publicDer)
        val aad = a.nodeId + b.nodeId
        val plain = byteArrayOf(0) + "secret".toByteArray()
        val body = Crypto.seal(key, aad, plain)
        assertContentEquals(plain, Crypto.open(Crypto.dmKey(b.private, a.publicDer), aad, body))
        assertNull(Crypto.open(Crypto.dmKey(a.private, Identity.generate().publicDer), aad, body), "wrong key")
        assertNull(Crypto.open(key, aad, body.copyOf().also { it[0] = (it[0] + 1).toByte() }), "wrong IV")
        assertNull(Crypto.open(key, b.nodeId + a.nodeId, body), "wrong AAD")
        assertNull(Crypto.open(key, aad, body.copyOf().also { it[20] = (it[20] + 1).toByte() }), "tampered ciphertext")
        assertNull(Crypto.open(key, aad, ByteArray(10)), "too short")
    }

    @Test fun helloSignatures() {
        val nonceB = Crypto.randomBytes(16)
        val sig = Crypto.helloSign(a, nonceB, b.nodeId)                                     // a answers b's HELLO
        assertTrue(Crypto.helloVerify(a.publicDer, nonceB, b.nodeId, sig))
        assertFalse(Crypto.helloVerify(a.publicDer, Crypto.randomBytes(16), b.nodeId, sig)) // replayed to another nonce
        assertFalse(Crypto.helloVerify(b.publicDer, nonceB, a.nodeId, sig))
    }

    // ---- PoW ----

    @Test fun powMinedNonceVerifiesAndPreviousFails() {
        assertEquals(15, C.powBits(10, 6)); assertEquals(16, C.powBits(100, 8)); assertEquals(22, C.powBits(40_000, 8))
        repeat(3) {
            val e = env(EnvType.POST, "pow $it".toByteArray())
            assertTrue(Pow.verify(e))
            assertTrue(e.powNonce > 0)
            assertFalse(Pow.verify(e.copy(powNonce = e.powNonce - 1)))
            assertTrue(Pow.verify(Codec.decodeEnvelope(Codec.encodeEnvelope(e))!!))
        }
        val dm = env(EnvType.DM, dmBody(), C.TTL_DM, b.nodeId)
        assertTrue(Pow.verify(dm))
        assertFalse(Pow.verify(dm.copy(lamport = 43)))                                      // nonce bound to the core
    }

    // ---- Identity ----

    @Test fun identitySurvivesReload() {
        val dir = Files.createTempDirectory("mesh-id").toFile()
        try {
            val first = Identity.loadOrCreate(dir)
            val again = Identity.loadOrCreate(dir)
            assertEquals(first.idHex, again.idHex)
            assertContentEquals(first.publicDer, again.publicDer)
            assertEquals(91, again.publicDer.size)
            assertTrue(Crypto.verify(first.publicDer, byteArrayOf(1), Crypto.sign(again.private, byteArrayOf(1))))
            assertEquals(19, Crypto.fingerprint(again.nodeId).length)
            assertIs<Frame.Hello>(Codec.decodeFrame(Codec.encodeFrame(Frame.Hello(again.publicDer, ByteArray(16)))))
        } finally { dir.deleteRecursively() }
    }
}
