// UNTESTED ON HARDWARE — not part of the demo build
package mesh.extras.lora

import mesh.core.EnvType
import mesh.core.FrameType
import mesh.core.LinkId
import mesh.core.Transport
import mesh.core.TransportListener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Carries OfflineMesh frames over a Meshtastic radio on its serial (USB) client API.
 *
 * Wire: Meshtastic stream framing (0x94 0xC3, u16 length <= 512, protobuf ToRadio/FromRadio). Our bytes ride in
 * MeshPacket.decoded (Data) with portnum PRIVATE_APP (256). Inside that payload: a 6-byte header then a chunk.
 * Each remote bridge (Meshtastic node number) is one LinkId "lora!xxxxxxxx", so the core's per-link HELLO handshake works.
 *
 * Policy: only public material crosses. Sent and received alike, only HELLO, HELLO_AUTH and ENVELOPE frames of type
 * POST pass; a SYNC_OFFER is rewritten to an empty offer (3 bytes, a keepalive for the core's 45 s silent-link
 * check), at most one per [keepaliveMs] per link. DMs, ACKs, PRESENCE, IHAVE/GRAFT/PRUNE, SYNC_WANT, BAN_PROOF are
 * dropped. The core still verifies everything that arrives; this filter is about airtime and metadata, not trust.
 *
 * Field numbers are from meshtastic/protobufs mesh.proto and portnums.proto; check them against the current .proto
 * before first use.
 */
class MeshtasticTransport(
    private val open: () -> Pair<InputStream, OutputStream>,
    /** Fraction of airtime we may use in any rolling hour. EU 868 is 10%; Meshtastic's US page states no figure. */
    private val dutyCycle: Double = 0.10,
    /** Modem bit rate; Long Fast (default preset) is 1.07 kbps. */
    private val bitRate: Int = 1_070,
    private val keepaliveMs: Long = 30_000,
    private val beaconMs: Long = 300_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : Transport {
    override val name = "lora"

    @Volatile private var listener: TransportListener? = null
    @Volatile private var running = false
    private var out: OutputStream? = null
    private var input: InputStream? = null
    private val outLock = Any()
    @Volatile var myNum: Long = -1; private set
    private val peers = ConcurrentHashMap.newKeySet<Long>()
    private val lastKeepalive = ConcurrentHashMap<Long, Long>()
    private val airtime = ArrayDeque<Pair<Long, Long>>()   // (sentAtMs, airtimeMs), guarded by outLock
    private val reasm = Reassembler(clock)
    private val rnd = SecureRandom()
    private var seq = rnd.nextInt(0x10000)

    override fun start(listener: TransportListener) {
        this.listener = listener
        val (i, o) = open()
        input = i; out = o; running = true
        // Like the official Python client: 32 x 0xC3 wakes a sleeping radio and resyncs its parser.
        synchronized(outLock) { o.write(ByteArray(32) { 0xC3.toByte() }); o.flush() }
        write(Proto.wantConfig(rnd.nextInt() and 0x7fffffff))
        Thread({ readLoop(i) }, "lora-rx").apply { isDaemon = true }.start()
        Thread({
            var lastBeacon = Long.MIN_VALUE / 2
            while (running) {
                runCatching {
                    write(Proto.heartbeat())
                    if (clock() - lastBeacon >= beaconMs && radioSend(BROADCAST, Chunker.beacon(nextSeq()))) lastBeacon = clock()
                }
                Thread.sleep(minOf(beaconMs, 60_000L))
            }
        }, "lora-beacon").apply { isDaemon = true }.start()
    }

    override fun stop() {
        running = false
        runCatching { input?.close() }; runCatching { out?.close() }
        peers.toList().forEach { listener?.onLinkDown(link(it)) }
        peers.clear()
    }

    /** True also when the policy drops the frame: to the core this link is simply lossy, which LoRa is anyway. */
    override fun send(link: LinkId, frame: ByteArray): Boolean {
        val node = nodeOf(link) ?: return false
        if (node !in peers) return false
        val f = outbound(node, frame) ?: return true
        val parts = Chunker.split(nextSeq(), f) ?: return true   // too big for MAX_CHUNKS: shortcut, reject not queue
        return radioSend(node, *parts.toTypedArray())
    }

    override fun close(link: LinkId) {
        val node = nodeOf(link) ?: return
        if (peers.remove(node)) { lastKeepalive.remove(node); listener?.onLinkDown(link) }
    }

    /** Outbound policy; null means drop. */
    internal fun outbound(node: Long, frame: ByteArray): ByteArray? = when {
        Policy.passes(frame) -> frame
        frame.isNotEmpty() && frame[0].toInt() == FrameType.SYNC_OFFER -> {
            val now = clock(); val last = lastKeepalive[node]
            if (last != null && now - last < keepaliveMs) null else { lastKeepalive[node] = now; Policy.EMPTY_OFFER }
        }
        else -> null
    }

    private fun nextSeq() = synchronized(outLock) { seq = (seq + 1) and 0xffff; seq }

    /** Sends all chunks or none, inside the duty-cycle budget. */
    private fun radioSend(to: Long, vararg chunks: ByteArray): Boolean = synchronized(outLock) {
        val o = out ?: return false
        val now = clock()
        while (airtime.isNotEmpty() && now - airtime.first().first > HOUR_MS) airtime.removeFirst()
        val cost = chunks.sumOf { airtimeMs(it.size) }
        if (airtime.sumOf { it.second } + cost > (dutyCycle * HOUR_MS).toLong()) return false
        try {
            for (c in chunks) o.write(Proto.streamFrame(Proto.toRadioPacket(to, c, rnd.nextInt() or 1)))
            o.flush()
        } catch (e: Exception) { return false }
        airtime.addLast(now to cost)
        true
    }

    // Data payload + 16-byte LoRa header + ~10 bytes protobuf; preamble ignored. Rough on purpose: the radio enforces its own limit too.
    private fun airtimeMs(payload: Int): Long = ((payload + 26) * 8 * 1000L) / bitRate

    private fun write(b: ByteArray) = synchronized(outLock) { out?.let { it.write(Proto.streamFrame(b)); it.flush() } }

    private fun readLoop(i: InputStream) {
        val d = Proto.Deframer()
        val buf = ByteArray(1024)
        try {
            while (running) {
                val n = i.read(buf)
                if (n < 0) break
                for (msg in d.feed(buf, n)) onFromRadio(msg)
            }
        } catch (_: Exception) { }
        if (running) stop()
    }

    internal fun onFromRadio(msg: ByteArray) {
        val fr = Proto.parseFromRadio(msg) ?: return
        fr.myNodeNum?.let { myNum = it }
        val p = fr.packet ?: return
        if (p.portnum != Proto.PRIVATE_APP || p.from == myNum) return
        if (myNum >= 0 && p.to != myNum && p.to != BROADCAST) return
        val (isBeacon, frame) = reasm.accept(p.from, p.payload) ?: return
        val l = listener ?: return
        if (peers.add(p.from)) l.onLinkUp(link(p.from), "lora !%08x snr %.1f".format(p.from, p.snr))
        if (isBeacon || frame == null) return
        if (Policy.passes(frame) || frame.contentEquals(Policy.EMPTY_OFFER)) l.onFrame(link(p.from), frame)
    }

    companion object {
        const val BROADCAST = 0xffffffffL
        private const val HOUR_MS = 3_600_000L
        fun link(node: Long): LinkId = "lora!%08x".format(node)
        fun nodeOf(link: LinkId): Long? = link.removePrefix("lora!").takeIf { it != link }?.toLongOrNull(16)
    }
}

/** Which frames may cross. Frame layout (Codec): [frameType][version][envType]... for ENVELOPE. */
object Policy {
    val EMPTY_OFFER = byteArrayOf(FrameType.SYNC_OFFER.toByte(), 0, 0)
    fun passes(f: ByteArray): Boolean = f.isNotEmpty() && when (f[0].toInt()) {
        FrameType.HELLO, FrameType.HELLO_AUTH -> true
        FrameType.ENVELOPE -> f.size > 2 && f[2].toInt() == EnvType.POST
        else -> false
    }
}

/**
 * Our payload inside Data.payload: 'O', kind (0 beacon, 1 chunk), u16 seq, u8 index, u8 count, then data.
 * Meshtastic DATA_PAYLOAD_LEN is 233; we stay at [MAX_PAYLOAD] for margin.
 */
object Chunker {
    const val MAX_PAYLOAD = 230
    const val HEADER = 6
    const val MAX_CHUNKS = 8            // 8 x 224 = 1792 B: a 1,000-byte post envelope (~1.2 KB) fits
    const val MAX_FRAME = MAX_CHUNKS * (MAX_PAYLOAD - HEADER)

    fun beacon(seq: Int) = byteArrayOf('O'.code.toByte(), 0, (seq shr 8).toByte(), seq.toByte(), 0, 1)

    fun split(seq: Int, f: ByteArray): List<ByteArray>? {
        if (f.isEmpty() || f.size > MAX_FRAME) return null
        val per = MAX_PAYLOAD - HEADER
        val n = (f.size + per - 1) / per
        return List(n) { i ->
            val part = f.copyOfRange(i * per, minOf(f.size, (i + 1) * per))
            byteArrayOf('O'.code.toByte(), 1, (seq shr 8).toByte(), seq.toByte(), i.toByte(), n.toByte()) + part
        }
    }
}

/** Reassembles chunks per (sender, seq). Bounded: [MAX_PENDING] partial frames, each dropped after [TIMEOUT_MS]. */
class Reassembler(private val clock: () -> Long) {
    private class Partial(val count: Int, val born: Long) { val parts = arrayOfNulls<ByteArray>(count) }
    private val pending = LinkedHashMap<Pair<Long, Int>, Partial>()

    /** Returns (isBeacon, frame or null when still incomplete), or null for a malformed payload. */
    @Synchronized fun accept(from: Long, p: ByteArray): Pair<Boolean, ByteArray?>? {
        if (p.size < Chunker.HEADER || p.size > Chunker.MAX_PAYLOAD || p[0] != 'O'.code.toByte()) return null
        val kind = p[1].toInt(); val seq = ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff)
        val idx = p[4].toInt() and 0xff; val cnt = p[5].toInt() and 0xff
        if (kind == 0) return if (p.size == Chunker.HEADER) true to null else null
        if (kind != 1 || cnt !in 1..Chunker.MAX_CHUNKS || idx >= cnt || p.size == Chunker.HEADER) return null
        val now = clock()
        pending.values.removeIf { now - it.born > TIMEOUT_MS }
        val key = from to seq
        val part = pending[key] ?: Partial(cnt, now).also {
            if (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
            pending[key] = it
        }
        if (part.count != cnt) { pending.remove(key); return null }
        part.parts[idx] = p.copyOfRange(Chunker.HEADER, p.size)
        if (part.parts.any { it == null }) return false to null
        pending.remove(key)
        val bos = ByteArrayOutputStream()
        part.parts.forEach { bos.write(it!!) }
        return false to bos.toByteArray()
    }

    companion object { const val MAX_PENDING = 16; const val TIMEOUT_MS = 60_000L }
}

/** Just enough protobuf for ToRadio/FromRadio/MeshPacket/Data, plus the serial stream framing. */
object Proto {
    const val PRIVATE_APP = 256
    const val MAX_LEN = 512   // the client API treats a longer length as corrupt

    class Packet(val from: Long, val to: Long, val portnum: Int, val payload: ByteArray, val snr: Float)
    class FromRadio(val packet: Packet?, val myNodeNum: Long?, val configCompleteId: Long?)

    fun streamFrame(msg: ByteArray): ByteArray {
        require(msg.size <= MAX_LEN) { "ToRadio too long: ${msg.size}" }
        return byteArrayOf(0x94.toByte(), 0xC3.toByte(), (msg.size shr 8).toByte(), msg.size.toByte()) + msg
    }

    /** Finds frames in the serial byte stream; the radio also prints debug text between them, which is skipped. */
    class Deframer {
        private val buf = ByteArrayOutputStream()
        private var state = 0; private var len = 0
        fun feed(b: ByteArray, n: Int = b.size): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            for (k in 0 until n) {
                val x = b[k].toInt() and 0xff
                when (state) {
                    0 -> if (x == 0x94) state = 1
                    1 -> state = if (x == 0xC3) 2 else if (x == 0x94) 1 else 0
                    2 -> { len = x shl 8; state = 3 }
                    3 -> { len = len or x; buf.reset(); state = if (len in 1..MAX_LEN) 4 else 0 }
                    4 -> { buf.write(x); if (buf.size() == len) { out += buf.toByteArray(); state = 0 } }
                }
            }
            return out
        }
    }

    // ---- writer
    private class W { val b = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7fL.inv() != 0L) { b.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; b.write(x.toInt()) }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun uint(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun fixed32(field: Int, v: Long) { tag(field, 5); for (i in 0 until 4) b.write(((v ushr (8 * i)) and 0xff).toInt()) }
        fun bytes(field: Int, v: ByteArray) { tag(field, 2); varint(v.size.toLong()); b.write(v) }
    }
    private fun msg(f: W.() -> Unit) = W().apply(f).b.toByteArray()

    fun wantConfig(id: Int) = msg { uint(3, id.toLong()) }                      // ToRadio.want_config_id = 3
    fun heartbeat() = msg { bytes(7, ByteArray(0)) }                            // ToRadio.heartbeat = 7
    fun toRadioPacket(to: Long, payload: ByteArray, id: Int) = msg {            // ToRadio.packet = 1
        bytes(1, msg {
            fixed32(2, to)                                                      // MeshPacket.to = 2
            bytes(4, msg { uint(1, PRIVATE_APP.toLong()); bytes(2, payload) })  // MeshPacket.decoded = 4: Data.portnum = 1, payload = 2
            fixed32(6, id.toLong() and 0xffffffffL)                             // MeshPacket.id = 6
            uint(9, 3)                                                          // MeshPacket.hop_limit = 9 (Meshtastic default 3)
        })
    }

    // ---- reader: returns null on any malformed input
    private class R(val b: ByteArray) { var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { require(i < b.size && s < 64); val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 } }
        fun fixed32(): Long { require(i + 4 <= b.size); var r = 0L; for (k in 0 until 4) r = r or ((b[i++].toLong() and 0xff) shl (8 * k)); return r }
        fun bytes(): ByteArray { val n = varint(); require(n in 0..(b.size - i).toLong()); return b.copyOfRange(i, i + n.toInt()).also { i += n.toInt() } }
        fun skip(wire: Int) { when (wire) { 0 -> varint(); 1 -> { require(i + 8 <= b.size); i += 8 }; 2 -> bytes(); 5 -> fixed32(); else -> throw IllegalArgumentException("wire $wire") } }
        fun each(f: (field: Int, wire: Int) -> Unit) { while (i < b.size) { val t = varint(); f((t ushr 3).toInt(), (t and 7).toInt()) } }
    }

    fun parseFromRadio(b: ByteArray): FromRadio? = runCatching {
        var packet: Packet? = null; var my: Long? = null; var done: Long? = null
        val r = R(b)
        r.each { f, w -> when {
            f == 2 && w == 2 -> packet = parsePacket(r.bytes())                 // FromRadio.packet = 2
            f == 3 && w == 2 -> my = parseMyNum(r.bytes())                      // FromRadio.my_info = 3
            f == 7 && w == 0 -> done = r.varint()                               // FromRadio.config_complete_id = 7
            else -> r.skip(w)
        } }
        FromRadio(packet, my, done)
    }.getOrNull()

    private fun parseMyNum(b: ByteArray): Long? { var n: Long? = null; val r = R(b)
        r.each { f, w -> if (f == 1 && w == 0) n = r.varint() else r.skip(w) }; return n }   // MyNodeInfo.my_node_num = 1

    private fun parsePacket(b: ByteArray): Packet? {
        var from = 0L; var to = 0L; var port = -1; var payload = ByteArray(0); var snr = 0f
        val r = R(b)
        r.each { f, w -> when {
            f == 1 && w == 5 -> from = r.fixed32()                              // MeshPacket.from = 1
            f == 2 && w == 5 -> to = r.fixed32()                                // MeshPacket.to = 2
            f == 4 && w == 2 -> { val d = R(r.bytes()); d.each { df, dw -> when {
                df == 1 && dw == 0 -> port = d.varint().toInt()                 // Data.portnum = 1
                df == 2 && dw == 2 -> payload = d.bytes()                       // Data.payload = 2
                else -> d.skip(dw) } } }
            f == 8 && w == 5 -> snr = java.lang.Float.intBitsToFloat(r.fixed32().toInt())   // MeshPacket.rx_snr = 8
            else -> r.skip(w)                                                   // encrypted = 5 (not for us) and the rest
        } }
        return if (port < 0) null else Packet(from, to, port, payload, snr)
    }
}
