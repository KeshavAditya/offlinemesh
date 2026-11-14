package mesh.core

import java.nio.file.Files
import java.util.PriorityQueue

/** Virtual-time scheduler shared by every node in one simulation; also the network the SimTransports talk over. */
class SimNetwork(startMs: Long = 1_700_000_003_000L) : Scheduler {
    private class Task(val at: Long, val seq: Long, val run: () -> Unit)
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private var now = startMs
    private var seq = 0L

    override fun nowMs() = now
    override fun post(task: () -> Unit) = schedule(0, task)
    override fun schedule(delayMs: Long, task: () -> Unit) { queue += Task(now + delayMs, seq++, task) }

    fun runFor(ms: Long) {
        val end = now + ms
        while (queue.isNotEmpty() && queue.peek().at <= end) queue.poll().also { now = it.at }.run()
        now = end
    }

    /** One delivered frame, for assertions and transmission counts. */
    class Sent(val from: String, val to: String, val frame: ByteArray, val atMs: Long) {
        val env: Envelope? get() = (Codec.decodeFrame(frame) as? Frame.Env)?.env
    }
    val sent = ArrayList<Sent>()

    val nodes = LinkedHashMap<String, SimNode>()
    private val links = HashMap<Pair<String, String>, SimLink>()

    fun node(name: String): SimNode = SimNode(this, name).also { nodes[name] = it }

    fun link(a: String, b: String, up: Boolean = true): SimLink =
        SimLink(this, nodes.getValue(a), nodes.getValue(b)).also { links[a to b] = it; links[b to a] = it; if (up) it.up() }

    fun linkOf(a: String, b: String) = links.getValue(a to b)

    /** Delivers [frame] from [from] to [to] over their link as if [from] had sent it. */
    fun inject(from: String, to: String, frame: ByteArray) { linkOf(from, to).carry(nodes.getValue(from), frame) }

    companion object { const val LATENCY_MS = 5L }
}

class SimLink(private val net: SimNetwork, val a: SimNode, val b: SimNode) {
    var isUp = false; private set
    fun idAt(n: SimNode) = "sim:" + (if (n === a) b else a).name

    fun up() {
        if (isUp) return
        isUp = true
        net.post { a.transport.listener?.onLinkUp(idAt(a), "sim ${b.name}"); b.transport.listener?.onLinkUp(idAt(b), "sim ${a.name}") }
    }

    fun down() {
        if (!isUp) return
        isUp = false
        net.post { a.transport.listener?.onLinkDown(idAt(a)); b.transport.listener?.onLinkDown(idAt(b)) }
    }

    fun carry(from: SimNode, frame: ByteArray): Boolean {
        if (!isUp) return false
        val to = if (from === a) b else a
        net.schedule(SimNetwork.LATENCY_MS) {
            if (isUp) { net.sent += SimNetwork.Sent(from.name, to.name, frame, net.nowMs()); to.transport.listener?.onFrame(idAt(to), frame) }
        }
        return true
    }
}

class SimTransport(private val net: SimNetwork, private val owner: SimNode) : Transport {
    override val name = "sim"
    var listener: TransportListener? = null
    override fun start(listener: TransportListener) { this.listener = listener }
    override fun stop() { listener = null }
    private fun link(id: LinkId) = net.linkOf(owner.name, id.removePrefix("sim:"))
    override fun send(link: LinkId, frame: ByteArray) = link(link).carry(owner, frame)
    override fun close(link: LinkId) = link(link).down()
}

/** A real MeshNode on a SimTransport, mining inline so everything stays in virtual time. */
class SimNode(net: SimNetwork, val name: String) {
    val transport = SimTransport(net, this)
    val dir = Files.createTempDirectory("sim-$name").toFile().also { it.deleteOnExit() }
    val node = MeshNode(dir, net, listOf(transport)).also { it.offLoop = { r -> r() } }
    val events = ArrayList<MeshEvent>()
    val id get() = node.id.idHex

    init {
        node.addListener { events += it }
        node.start()
        node.setName(name)
    }

    fun added() = events.filterIsInstance<MeshEvent.MessageAdded>().map { it.msg }
    fun security() = events.filterIsInstance<MeshEvent.SecurityLogged>().map { it.event }
}
