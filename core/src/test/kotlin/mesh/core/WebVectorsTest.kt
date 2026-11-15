package mesh.core

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECPrivateKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-check with the web client's uiserver/src/main/resources/web/meshcore.js. Writes build/web-vectors/kotlin.json
 * (frames made here, with their private keys) and checks build/web-vectors/js.json (frames made by meshcore.js) when present.
 * Both directions in one command: `node --test uiserver/src/test/js/meshcore.test.mjs` (it writes js.json, runs this test, then reads kotlin.json).
 */
class WebVectorsTest {
    private val dir = File("build/web-vectors")
    private val map = MapSerializer(String.serializer(), String.serializer())
    private fun h(b: ByteArray) = Crypto.hex(b)
    private fun u(s: String?) = Crypto.unhex(assertNotNull(s))
    private fun scalar(i: Identity) = (i.private as ECPrivateKey).s.toString(16).padStart(64, '0')
    private fun identity(priv: String, pub: String): Identity {
        val params = (Identity.generate().private as ECPrivateKey).params
        return Identity(KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, u(priv)), params)), u(pub))
    }
    private fun frame(e: Envelope) = Codec.encodeFrame(Frame.Env(e))
    private fun env(s: String?) = assertIs<Frame.Env>(Codec.decodeFrame(u(s))).env
    private val ep = System.currentTimeMillis() / 10_000

    @Test fun writeKotlinVectors() {
        val a = Identity.generate(); val b = Identity.generate()
        val v = linkedMapOf<String, String>()
        v["a.priv"] = scalar(a); v["a.pub"] = h(a.publicDer); v["b.priv"] = scalar(b); v["b.pub"] = h(b.publicDer)
        val post = Envelope.create(a, EnvType.POST, Envelope.BROADCAST, 7, ep, 1, C.TTL_POST, "hello from kotlin".toByteArray())
        v["post.frame"] = h(frame(post)); v["post.msgId"] = h(post.msgId); v["post.text"] = "hello from kotlin"
        val post2 = Envelope.create(a, EnvType.POST, Envelope.BROADCAST, 8, ep, 1, C.TTL_POST, "second in slot 1".toByteArray())
        v["banProof.frame"] = h(Codec.encodeFrame(Frame.BanProof(post, post2)))
        val pres = Envelope.create(a, EnvType.PRESENCE, Envelope.BROADCAST, 9, ep, 0, C.TTL_PRESENCE, byteArrayOf(5) + "alice".toByteArray())
        v["presence.frame"] = h(frame(pres))
        val plain = byteArrayOf(0) + "secret über kotlin".toByteArray()
        val body = Crypto.seal(Crypto.dmKey(a.private, b.publicDer), a.nodeId + b.nodeId, Crypto.padDm(plain)!!)
        val dm = Envelope.create(a, EnvType.DM, b.nodeId, 10, ep, 0, C.TTL_DM, body)
        v["dm.frame"] = h(frame(dm)); v["dm.msgId"] = h(dm.msgId); v["dm.plain"] = h(plain)
        // A relayed copy: ttl/hops change but the signature and msgId still hold.
        v["dmRelayed.frame"] = h(frame(dm.copy(ttl = dm.ttl - 1, hops = 1)))
        v["ack.frame"] = h(frame(Envelope.create(b, EnvType.ACK, a.nodeId, 3, ep, 0, C.TTL_ACK, dm.msgId)))
        v["dmKey"] = h(Crypto.dmKey(a.private, b.publicDer))
        val nonce = Crypto.randomBytes(16)
        v["hello.frame"] = h(Codec.encodeFrame(Frame.Hello(a.publicDer, nonce)))
        v["helloAuth.peerNonce"] = h(nonce)   // B's nonce; A signs it for B
        v["helloAuth.frame"] = h(Codec.encodeFrame(Frame.HelloAuth(Crypto.helloSign(a, nonce, b.nodeId))))
        val ids = listOf(post.msgId, dm.msgId)
        v["syncOffer.frame"] = h(Codec.encodeFrame(Frame.SyncOffer(ids)))
        v["syncWant.frame"] = h(Codec.encodeFrame(Frame.SyncWant(ids)))
        v["syncWantEmpty.frame"] = h(Codec.encodeFrame(Frame.SyncWant(emptyList())))
        v["ihave.frame"] = h(Codec.encodeFrame(Frame.IHave(ids, listOf(0, 3))))
        v["graft.frame"] = h(Codec.encodeFrame(Frame.Graft(ids)))
        v["prune.frame"] = h(Codec.encodeFrame(Frame.Prune))
        val core = post.core
        v["pow.core"] = h(core); v["pow.bits"] = "18"; v["pow.nonce"] = Pow.mine(core, 18).toString()
        for (n in listOf(0, 61, 62, 4093, 4094, 39_999)) v["pad.$n"] = h(Crypto.padDm(ByteArray(n + 1) { (it * 7).toByte() })!!)
        dir.mkdirs()
        File(dir, "kotlin.json").writeText(Json.encodeToString(map, v))
    }

    @Test fun checkJsVectors() {
        val f = File(dir, "js.json")
        if (!f.exists()) { println("WebVectorsTest: no ${f.path}, skipped (run node --test uiserver/src/test/js/meshcore.test.mjs)"); return }
        val v = Json.decodeFromString(map, f.readText())
        val c = identity(v["c.priv"]!!, v["c.pub"]!!); val d = identity(v["d.priv"]!!, v["d.pub"]!!)
        assertContentEquals(Crypto.nodeId(c.publicDer), u(v["c.id"]))
        // Every frame decodes and re-encodes to the same bytes.
        for ((k, x) in v) if (k.endsWith(".frame")) assertContentEquals(u(x), Codec.encodeFrame(assertNotNull(Codec.decodeFrame(u(x)), k)), k)
        for (k in listOf("post", "presence", "dm", "ack")) {
            val e = env(v["$k.frame"])
            assertContentEquals(c.publicDer.takeIf { k != "ack" } ?: d.publicDer, e.srcPub, k)
            assertTrue(Crypto.verify(e.srcPub, e.core, e.sig), "$k signature")
            if (e.type == EnvType.POST || e.type == EnvType.DM) assertTrue(Pow.verify(e), "$k pow")
            v["$k.msgId"]?.let { assertEquals(it, h(e.msgId), "$k msgId") }
        }
        assertEquals(v["post.text"], String(env(v["post.frame"]).body))
        assertEquals("carol", String(env(v["presence.frame"]).body, 1, 5))
        val dm = env(v["dm.frame"])
        val opened = Crypto.open(Crypto.dmKey(d.private, c.publicDer), dm.srcId + dm.dst, dm.body)?.let(Crypto::unpadDm)
        assertContentEquals(u(v["dm.plain"]), opened)
        val ack = env(v["ack.frame"])
        assertContentEquals(dm.msgId, ack.body); assertContentEquals(c.nodeId, ack.dst)
        assertEquals(v["dmKey"], h(Crypto.dmKey(c.private, d.publicDer)))
        val auth = assertIs<Frame.HelloAuth>(Codec.decodeFrame(u(v["helloAuth.frame"])))
        assertTrue(Crypto.helloVerify(c.publicDer, u(v["helloAuth.peerNonce"]), d.nodeId, auth.sig), "hello auth")
        assertEquals(v["pow.nonce"]!!.toLong(), Pow.mine(u(v["pow.core"]), v["pow.bits"]!!.toInt()))
        for ((k, x) in v) if (k.startsWith("pad.")) { val n = k.substring(4).toInt(); assertEquals(x, h(Crypto.padDm(ByteArray(n + 1) { (it * 7).toByte() })!!), k) }
        println("WebVectorsTest: js.json OK (${v.size} entries)")
    }
}
