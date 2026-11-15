package mesh.ui

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import mesh.core.LinkId
import mesh.core.Transport
import mesh.core.TransportListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * Browser mesh nodes over WebSocket (GuestServer /mesh): one binary message is one Transport frame, both ways.
 * A dumb pipe; core does HELLO and everything else. Core does not count "web" links toward maxLinks, so the cap is here.
 */
class WebTransport(private val maxLinks: Int = 16) : Transport {
    override val name = "web"
    @Volatile private var listener: TransportListener? = null
    private val links = HashMap<LinkId, Channel<ByteArray>>()   // guarded by itself
    private val ids = AtomicInteger()

    override fun start(listener: TransportListener) { this.listener = listener }
    override fun stop() { listener = null; synchronized(links) { links.values.toList() }.forEach { it.close() } }
    override fun send(link: LinkId, frame: ByteArray) = synchronized(links) { links[link] }?.trySend(frame)?.isSuccess == true
    override fun close(link: LinkId) { synchronized(links) { links[link] }?.close() }

    /** Runs one browser link for the life of [s]; [ip] goes into the link description. */
    internal suspend fun serve(s: DefaultWebSocketServerSession, ip: String) {
        val l = listener
        val id = "web${ids.incrementAndGet()}"
        val q = Channel<ByteArray>(256)
        val ok = l != null && synchronized(links) { (links.size < maxLinks).also { if (it) links[id] = q } }
        if (!ok) return s.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many web links"))
        try {
            l!!.onLinkUp(id, "web $ip")
            // Closing q (core closed the link, or stop()) ends this loop and the socket.
            s.launch {
                try { for (f in q) s.send(Frame.Binary(true, f)); s.close(CloseReason(CloseReason.Codes.NORMAL, "closed")) }
                catch (_: Exception) { q.close() }   // socket already gone; the read loop below ends on its own
            }
            for (f in s.incoming) if (f is Frame.Binary) l.onFrame(id, f.readBytes())
        } finally {
            synchronized(links) { links.remove(id) }
            q.close()
            l!!.onLinkDown(id)
        }
    }
}
