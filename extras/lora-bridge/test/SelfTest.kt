// UNTESTED ON HARDWARE — not part of the demo build
// Pure-JVM self-test of the framing, chunking and policy (no radio, no jSerialComm, no JUnit). How to run: ../README.md.
package mesh.extras.lora

import mesh.core.LinkId
import mesh.core.TransportListener
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.random.Random

private var checks = 0
private fun ok(cond: Boolean, what: String) { check(cond) { "FAIL: $what" }; checks++ }

private fun varint(v: Int): ByteArray { val b = ByteArrayOutputStream(); var x = v; while (x >= 0x80) { b.write((x and 0x7f) or 0x80); x = x ushr 7 }; b.write(x); return b.toByteArray() }
private fun le32(v: Long) = ByteArray(4) { ((v ushr (8 * it)) and 0xff).toByte() }
private fun field(tag: Int, body: ByteArray) = byteArrayOf(tag.toByte()) + varint(body.size) + body

/** What the radio would hand the other side: FromRadio.packet = MeshPacket with `from` filled in. */
private fun relay(toRadio: ByteArray, from: Long): ByteArray? {
    if (toRadio[0] != 0x0A.toByte()) return null                     // not ToRadio.packet (want_config, heartbeat)
    var i = 1; var n = 0; var s = 0
    while (true) { val x = toRadio[i++].toInt() and 0xff; n = n or ((x and 0x7f) shl s); if (x < 0x80) break; s += 7 }
    val mp = toRadio.copyOfRange(i, i + n)
    return field(0x12, byteArrayOf(0x0D) + le32(from) + mp)
}
private fun myInfo(num: Long) = field(0x1A, byteArrayOf(0x08) + varint(num.toInt() and 0x7fffffff))

private class Rec : TransportListener {
    val ups = ArrayList<LinkId>(); val frames = ArrayList<Pair<LinkId, ByteArray>>()
    @Synchronized override fun onLinkUp(link: LinkId, description: String) { ups += link }
    @Synchronized override fun onFrame(link: LinkId, frame: ByteArray) { frames += link to frame }
    override fun onLinkDown(link: LinkId) {}
}

private fun blockingIn() = PipedInputStream().also { PipedOutputStream(it) }   // read() blocks: the radio never talks

fun main() {
    // 1. Stream framing: debug text, a stray 0x94, an oversized length and two good frames.
    val m1 = Random.nextBytes(40); val m2 = Random.nextBytes(512)
    val stream = "INFO | boot\r\n".toByteArray() + byteArrayOf(0x94.toByte()) + Proto.streamFrame(m1) +
        byteArrayOf(0x94.toByte(), 0xC3.toByte(), 0x02, 0x58) + "junk".toByteArray() + Proto.streamFrame(m2)
    val got = Proto.Deframer().run { stream.toList().chunked(7).flatMap { feed(it.toByteArray()) } }
    ok(got.size == 2 && got[0].contentEquals(m1) && got[1].contentEquals(m2), "deframer finds exactly the two good frames")
    ok(runCatching { Proto.streamFrame(ByteArray(513)) }.isFailure, "streamFrame refuses > 512")

    // 2. Policy: frameType, version, envType.
    ok(Policy.passes(byteArrayOf(0x01, 1, 2, 9)), "POST envelope passes")
    ok(!Policy.passes(byteArrayOf(0x01, 1, 3, 9)), "DM dropped")
    ok(!Policy.passes(byteArrayOf(0x01, 1, 4, 9)), "ACK dropped")
    ok(!Policy.passes(byteArrayOf(0x01, 1, 1, 9)), "PRESENCE dropped")
    ok(!Policy.passes(byteArrayOf(0x01, 1)), "truncated envelope dropped")
    ok(Policy.passes(byteArrayOf(0x10, 5)) && Policy.passes(byteArrayOf(0x11, 5)), "HELLO / HELLO_AUTH pass")
    ok(listOf(0x12, 0x13, 0x14, 0x15, 0x16, 0x17).none { Policy.passes(byteArrayOf(it.toByte(), 0)) }, "sync, ban proof, tree frames dropped")

    // 3. Chunking: 1,500 bytes out of order; too big; malformed headers.
    val big = Random.nextBytes(1500)
    val parts = Chunker.split(7, big)!!
    ok(parts.all { it.size <= Chunker.MAX_PAYLOAD } && parts.size == 7, "1500 B -> 7 chunks of <= 230 B")
    val r = Reassembler { 0L }
    val res = parts.reversed().map { r.accept(1, it) }
    ok(res.dropLast(1).all { it == (false to null) } && res.last()!!.second!!.contentEquals(big), "reassembles out of order")
    ok(Chunker.split(1, ByteArray(Chunker.MAX_FRAME + 1)) == null, "frame over MAX_FRAME refused")
    ok(r.accept(1, byteArrayOf('X'.code.toByte(), 1, 0, 0, 0, 1, 5)) == null, "bad magic")
    ok(r.accept(1, byteArrayOf('O'.code.toByte(), 1, 0, 0, 2, 2, 5)) == null, "index >= count")
    ok(r.accept(1, byteArrayOf('O'.code.toByte(), 1, 0, 0, 0, 9, 5)) == null, "count over MAX_CHUNKS")
    var t = 0L; val rt = Reassembler { t }
    rt.accept(2, Chunker.split(3, big)!![0]); t += Reassembler.TIMEOUT_MS + 1
    ok(rt.accept(2, Chunker.split(3, big)!![1]) == (false to null), "stale partial expired, not completed")

    // 4. Two bridges through a fake radio.
    val A = 0x0a0a0a0aL; val B = 0x0b0b0b0bL
    val aOut = ByteArrayOutputStream(); val ra = Rec(); val rb = Rec()
    val a = MeshtasticTransport({ blockingIn() to aOut }); a.start(ra)
    val b = MeshtasticTransport({ blockingIn() to ByteArrayOutputStream() }); b.start(rb)
    a.onFromRadio(myInfo(A)); b.onFromRadio(myInfo(B))
    ok(a.myNum == A, "my_info parsed")
    a.onFromRadio(relay(Proto.toRadioPacket(MeshtasticTransport.BROADCAST, Chunker.beacon(1), 5), B)!!)
    ok(ra.ups == listOf("lora!0b0b0b0b"), "beacon from B brings up link lora!0b0b0b0b")
    ok(!a.send("lora!0c0c0c0c", byteArrayOf(0x01, 1, 2)), "send to unknown link is false")

    val post = byteArrayOf(0x01, 1, 2) + Random.nextBytes(1197)
    val dm = byteArrayOf(0x01, 1, 3) + Random.nextBytes(300)
    val offer = byteArrayOf(0x12, 0, 2) + Random.nextBytes(16)
    ok(a.send("lora!0b0b0b0b", post) && a.send("lora!0b0b0b0b", dm), "post sent, DM accepted and dropped")
    ok(a.send("lora!0b0b0b0b", offer) && a.send("lora!0b0b0b0b", offer), "two offers accepted")
    Proto.Deframer().feed(aOut.toByteArray()).mapNotNull { relay(it, A) }.forEach(b::onFromRadio)
    val toB = rb.frames.map { it.second }
    ok(rb.ups.contains("lora!0a0a0a0a"), "B sees link lora!0a0a0a0a")
    ok(toB.count { it.contentEquals(post) } == 1, "post arrives intact once")
    ok(toB.none { it[0].toInt() == 0x01 && it[2].toInt() == 3 }, "no DM crossed")
    ok(toB.count { it.contentEquals(Policy.EMPTY_OFFER) } == 1, "offers became one empty keepalive")
    ok(toB.size == 2, "nothing else crossed")

    // 5. Inbound policy: a DM injected over the air from A is not delivered.
    val before = rb.frames.size
    Chunker.split(9, dm)!!.forEach { b.onFromRadio(relay(Proto.toRadioPacket(B, it, 6), A)!!) }
    ok(rb.frames.size == before, "inbound DM dropped")

    // 6. Duty cycle: a 0.01% budget (360 ms/hour) cannot carry a 7-chunk post.
    val c = MeshtasticTransport({ blockingIn() to ByteArrayOutputStream() }, dutyCycle = 0.0001); c.start(Rec())
    c.onFromRadio(relay(Proto.toRadioPacket(MeshtasticTransport.BROADCAST, Chunker.beacon(1), 5), B)!!)
    ok(!c.send("lora!0b0b0b0b", post), "over the airtime budget -> false")

    a.stop(); b.stop(); c.stop()
    println("lora-bridge self-test: $checks checks passed")
}
