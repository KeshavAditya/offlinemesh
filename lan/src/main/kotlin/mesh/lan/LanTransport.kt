package mesh.lan

import mesh.core.C
import mesh.core.Codec
import mesh.core.LinkId
import mesh.core.Transport
import mesh.core.TransportListener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP links framed as u32 length + frame (Codec.writeFrame/readFrame). Peer identity is core's job (5.5); this only moves frames.
 * @param listenPort TCP port to accept on (0 = ephemeral, read [port] after start).
 * @param staticPeers "host:port" entries, always dialled, redialled with backoff while the transport runs.
 * @param discoveryPrefix 4-byte NodeId prefix; non-null turns on UDP broadcast discovery on [DISCOVERY_PORT].
 */
class LanTransport(
    private val listenPort: Int,
    private val staticPeers: List<String> = emptyList(),
    private val discoveryPrefix: ByteArray? = null,
) : Transport {
    override val name = "lan"

    companion object {
        const val DISCOVERY_PORT = 47474
        private val MAGIC = "OMESH1".toByteArray(Charsets.US_ASCII)
        private const val QUEUE = 64
        private const val CONNECT_TIMEOUT_MS = 5_000
    }

    private val running = AtomicBoolean(false)
    private val links = ConcurrentHashMap<LinkId, Link>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()   // "ip:port" of discovered peers with a dialer alive
    private val ids = AtomicInteger()
    private lateinit var listener: TransportListener
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null

    /** Bound TCP port, valid after [start]. */
    val port: Int get() = server?.localPort ?: -1

    override fun start(listener: TransportListener) {
        this.listener = listener
        running.set(true)
        val ss = ServerSocket(listenPort).also { server = it }
        thread("lan-accept") {
            while (running.get()) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                if (links.size >= C.MAX_LINKS) s.close() else open(s, "lan ${s.inetAddress.hostAddress}:${s.port}")
            }
        }
        for (p in staticPeers) {
            val host = p.substringBeforeLast(':'); val pt = p.substringAfterLast(':').toInt()
            thread("lan-dial-$p") { dialLoop(host, pt, forever = true) }
        }
        if (discoveryPrefix != null) startDiscovery(discoveryPrefix)
    }

    override fun stop() {
        running.set(false)
        server?.close(); udp?.close()
        links.values.forEach { it.close() }
    }

    override fun send(link: LinkId, frame: ByteArray): Boolean = links[link]?.queue?.offer(frame) ?: false

    override fun close(link: LinkId) { links[link]?.close() }

    /** Dial host:port; while the link lives, wait; then (static peers only) redial with backoff 1, 2, 4, 8 s. */
    private fun dialLoop(host: String, port: Int, forever: Boolean) {
        var backoff = 1_000L
        while (running.get()) {
            if (links.size < C.MAX_LINKS) {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    open(s, "lan $host:$port").done.await()
                    backoff = 1_000L
                } catch (e: IOException) { s.close() }
            }
            if (!forever || !running.get()) return
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { return }
            backoff = minOf(backoff * 2, 8_000L)
        }
    }

    private fun startDiscovery(prefix: ByteArray) {
        require(prefix.size == 4)
        val sock = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(DISCOVERY_PORT)) }
        udp = sock
        val beacon = ByteBuffer.allocate(12).put(MAGIC).put(prefix).putShort(port.toShort()).array()
        thread("lan-beacon") {
            val to = InetAddress.getByName("255.255.255.255")
            while (running.get()) {
                try { sock.send(DatagramPacket(beacon, beacon.size, to, DISCOVERY_PORT)) } catch (e: IOException) { if (sock.isClosed) return@thread }
                try { Thread.sleep(2_000) } catch (e: InterruptedException) { return@thread }
            }
        }
        thread("lan-discover") {
            val buf = ByteArray(64)
            while (running.get()) {
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (e: IOException) { return@thread }
                if (pkt.length != 12 || !buf.copyOfRange(0, 6).contentEquals(MAGIC)) continue
                val peer = buf.copyOfRange(6, 10)
                if (!lessUnsigned(prefix, peer)) continue          // tie-break: only the smaller prefix dials (also skips our own beacon)
                val host = pkt.address.hostAddress
                val tcp = ((buf[10].toInt() and 0xff) shl 8) or (buf[11].toInt() and 0xff)
                val key = "$host:$tcp"
                if (dialing.add(key)) thread("lan-dial-$key") { try { dialLoop(host, tcp, forever = false) } finally { dialing.remove(key) } }
            }
        }
    }

    private fun lessUnsigned(a: ByteArray, b: ByteArray): Boolean {
        for (i in a.indices) {
            val x = a[i].toInt() and 0xff; val y = b[i].toInt() and 0xff
            if (x != y) return x < y
        }
        return false
    }

    private fun open(s: Socket, description: String): Link {
        s.tcpNoDelay = true
        val l = Link("lan${ids.incrementAndGet()}", s)
        links[l.id] = l
        listener.onLinkUp(l.id, description)
        l.startThreads()
        return l
    }

    private inner class Link(val id: LinkId, val socket: Socket) {
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE)
        val done = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        private lateinit var writer: Thread

        fun startThreads() {
            writer = thread("lan-w-$id") {
                try { val out = BufferedOutputStream(socket.getOutputStream()); while (true) Codec.writeFrame(out, queue.take()) } catch (e: Exception) { close() }
            }
            thread("lan-r-$id") {
                try { val inp = DataInputStream(BufferedInputStream(socket.getInputStream())); while (true) listener.onFrame(id, Codec.readFrame(inp)) } catch (e: IOException) { close() }
            }
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { socket.close() } catch (_: IOException) {}
            if (::writer.isInitialized) writer.interrupt()
            links.remove(id)
            listener.onLinkDown(id)
            done.countDown()
        }
    }

    private fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true; start() }
}
