package mesh.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** Section 5.3. ByteArray fields: compare envelopes by [msgId] or encoded bytes, not by ==. */
data class Envelope(
    val version: Int, val type: Int, val srcPub: ByteArray, val dst: ByteArray,
    val lamport: Long, val epoch: Long, val slot: Int, val initTtl: Int, val body: ByteArray,
    val powNonce: Long, val sig: ByteArray, val ttl: Int, val hops: Int,
) {
    val core: ByteArray by lazy { Codec.encodeCore(this) }
    val srcId: ByteArray by lazy { Crypto.nodeId(srcPub) }
    val msgId: ByteArray by lazy { Crypto.sha256(core).copyOf(8) }
    val isBroadcast get() = dst.all { it == 0.toByte() }

    companion object {
        val BROADCAST = ByteArray(8)

        /** Origin side: mines PoW for POST/DM and signs. Call off the mesh loop (mining can take seconds). */
        fun create(id: Identity, type: Int, dst: ByteArray, lamport: Long, epoch: Long, slot: Int, initTtl: Int, body: ByteArray): Envelope {
            val e = Envelope(C.PROTOCOL_VERSION, type, id.publicDer, dst, lamport, epoch, slot, initTtl, body, 0, ByteArray(0), initTtl, 0)
            val nonce = if (type == EnvType.POST || type == EnvType.DM) Pow.mine(e.core, Pow.bits(e)) else 0L
            return e.copy(powNonce = nonce, sig = Crypto.sign(id.private, e.core))
        }
    }
}

sealed interface Frame {
    class Env(val env: Envelope) : Frame
    class Hello(val pub: ByteArray, val nonce: ByteArray) : Frame
    class HelloAuth(val sig: ByteArray) : Frame
    class SyncOffer(val ids: List<ByteArray>) : Frame
    class SyncWant(val ids: List<ByteArray>) : Frame
    /** Stretch 4: two POSTs by one key for one (epoch, slot) with different msgIds. */
    class BanProof(val a: Envelope, val b: Envelope) : Frame
    /** U9 tree broadcast: 1 to 64 msgIds each. IHAVE also carries, per id, the announcer's distance in links from the source (0 = its own). */
    class IHave(val ids: List<ByteArray>, val hops: List<Int>) : Frame { init { require(hops.size == ids.size) } }
    class Graft(val ids: List<ByteArray>) : Frame
    object Prune : Frame
}

// Sections 5.2 and 5.3. Decoders are strict: wrong lengths, unknown types or trailing bytes give null.
object Codec {
    fun encodeCore(e: Envelope): ByteArray = write {
        u8(e.version); u8(e.type); u8Bytes(e.srcPub)
        require(e.dst.size == 8); write(e.dst)
        writeLong(e.lamport); writeInt(e.epoch.toInt()); u8(e.slot); u8(e.initTtl)
        require(e.body.size <= 0xffff); writeShort(e.body.size); write(e.body)
    }

    fun encodeEnvelope(e: Envelope): ByteArray = e.core + write {
        writeLong(e.powNonce); u8Bytes(e.sig); u8(e.ttl); u8(e.hops)
    }

    fun decodeEnvelope(b: ByteArray): Envelope? = parse(b) { readEnvelope() }

    fun encodeFrame(f: Frame): ByteArray = write {
        when (f) {
            is Frame.Env -> { u8(FrameType.ENVELOPE); write(encodeEnvelope(f.env)) }
            is Frame.Hello -> { u8(FrameType.HELLO); u8Bytes(f.pub); require(f.nonce.size == 16); write(f.nonce) }
            is Frame.HelloAuth -> { u8(FrameType.HELLO_AUTH); u8Bytes(f.sig) }
            is Frame.SyncOffer -> { u8(FrameType.SYNC_OFFER); ids(f.ids) }
            is Frame.SyncWant -> { u8(FrameType.SYNC_WANT); ids(f.ids) }
            is Frame.BanProof -> { u8(FrameType.BAN_PROOF); listOf(f.a, f.b).forEach { val x = encodeEnvelope(it); writeShort(x.size); write(x) } }
            is Frame.IHave -> { require(f.ids.size in 1..C.MAX_TREE_IDS); u8(FrameType.IHAVE); writeShort(f.ids.size); f.ids.forEachIndexed { i, b -> require(b.size == 8); write(b); u8(f.hops[i].coerceIn(0, 255)) } }
            is Frame.Graft -> { require(f.ids.size in 1..C.MAX_TREE_IDS); u8(FrameType.GRAFT); ids(f.ids) }
            Frame.Prune -> u8(FrameType.PRUNE)
        }
    }.also { require(it.size <= C.MAX_FRAME) { "frame too large: ${it.size}" } }

    fun decodeFrame(b: ByteArray): Frame? {
        if (b.size !in 1..C.MAX_FRAME) return null
        return parse(b) {
            when (u8()) {
                FrameType.ENVELOPE -> readEnvelope()?.let { Frame.Env(it) }
                FrameType.HELLO -> Frame.Hello(bytes(u8().also { need(it > 0) }), bytes(16))
                FrameType.HELLO_AUTH -> Frame.HelloAuth(bytes(u8().also { need(it > 0) }))
                FrameType.SYNC_OFFER -> Frame.SyncOffer(List(u16()) { bytes(8) })
                FrameType.SYNC_WANT -> Frame.SyncWant(List(u16()) { bytes(8) })
                FrameType.BAN_PROOF -> Frame.BanProof(proofPost(), proofPost())
                FrameType.IHAVE -> u16().also { need(it in 1..C.MAX_TREE_IDS) }.let { n -> val e = List(n) { bytes(8) to u8() }; Frame.IHave(e.map { it.first }, e.map { it.second }) }
                FrameType.GRAFT -> Frame.Graft(treeIds())
                FrameType.PRUNE -> Frame.Prune
                else -> null
            }
        }
    }

    /** Stream transports: u32 length then the frame. */
    fun writeFrame(out: OutputStream, frame: ByteArray) {
        require(frame.size in 1..C.MAX_FRAME)
        DataOutputStream(out).apply { writeInt(frame.size); write(frame); flush() }
    }

    /** Throws IOException on a length outside 1..MAX_FRAME (the caller closes the link) or on EOF. */
    fun readFrame(inp: DataInputStream): ByteArray {
        val n = inp.readInt().toLong() and 0xffffffffL
        if (n !in 1..C.MAX_FRAME.toLong()) throw IOException("bad frame length $n")
        return ByteArray(n.toInt()).also { inp.readFully(it) }
    }

    private fun bodyOk(type: Int, body: ByteArray): Boolean = when (type) {
        EnvType.PRESENCE -> body.isNotEmpty() && (body[0].toInt() and 0xff).let { it <= C.MAX_NAME && body.size == 1 + it }
        EnvType.POST -> body.size in 1..C.MAX_POST_BODY
        EnvType.DM -> body.size in (12 + 1 + 16)..(12 + C.MAX_DM_PADDED + 16)
        EnvType.ACK -> body.size == 8
        else -> false
    }

    private fun Reader.readEnvelope(): Envelope? {
        val version = u8(); val type = u8(); val srcPub = bytes(u8()); val dst = bytes(8)
        val lamport = buf.long; val epoch = buf.int.toLong() and 0xffffffffL
        val slot = u8(); val initTtl = u8(); val body = bytes(u16())
        val nonce = buf.long; val sig = bytes(u8()); val ttl = u8(); val hops = u8()
        need(srcPub.isNotEmpty() && sig.isNotEmpty() && bodyOk(type, body))
        need(nonce == 0L || type == EnvType.POST || type == EnvType.DM)
        return Envelope(version, type, srcPub, dst, lamport, epoch, slot, initTtl, body, nonce, sig, ttl, hops)
    }

    private fun Reader.proofPost(): Envelope = decodeEnvelope(bytes(u16()))?.takeIf { it.type == EnvType.POST } ?: throw Bad()

    private fun Reader.treeIds(): List<ByteArray> = u16().also { need(it in 1..C.MAX_TREE_IDS) }.let { n -> List(n) { bytes(8) } }

    private class Bad : Exception()
    private class Reader(b: ByteArray) {
        val buf: ByteBuffer = ByteBuffer.wrap(b)
        fun need(ok: Boolean) { if (!ok) throw Bad() }
        fun u8() = buf.get().toInt() and 0xff
        fun u16() = buf.short.toInt() and 0xffff
        fun bytes(n: Int): ByteArray { need(n <= buf.remaining()); return ByteArray(n).also { buf.get(it) } }
    }

    private fun <T> parse(b: ByteArray, f: Reader.() -> T?): T? = try {
        val r = Reader(b)
        r.f()?.takeIf { !r.buf.hasRemaining() }
    } catch (e: Bad) { null } catch (e: BufferUnderflowException) { null }

    private fun write(f: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).f() }.toByteArray()

    private fun DataOutputStream.u8(v: Int) { require(v in 0..255); writeByte(v) }
    private fun DataOutputStream.u8Bytes(b: ByteArray) { u8(b.size); write(b) }
    private fun DataOutputStream.ids(ids: List<ByteArray>) {
        require(ids.size <= 0xffff); writeShort(ids.size)
        ids.forEach { require(it.size == 8); write(it) }
    }
}
