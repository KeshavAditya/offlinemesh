package mesh.ui

import mesh.core.Attack
import mesh.core.Contact
import mesh.core.DebugFlag
import mesh.core.DebugState
import mesh.core.LinkInfo
import mesh.core.MeshEvent
import mesh.core.Msg
import mesh.core.MsgStatus
import mesh.core.Sec
import mesh.core.SecKind
import mesh.core.SelfInfo
import mesh.core.Snapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dev-only node that answers commands and emits sample events, so the page can be built before core exists. */
class FakeNode : UiNode {
    private val ex = Executors.newSingleThreadScheduledExecutor()
    private val listeners = mutableListOf<(MeshEvent) -> Unit>()
    private var self = SelfInfo("a3f29c01d4e5b6f7", "A")
    private val b = "b7e1aa0233c4d5e6"
    private val c = "c90d11f2e3a4b5c6"
    private var contacts = listOf(
        Contact(b, "B", true, 1, null, true, false, 0),
        Contact(c, "C", false, 2, b, false, false, 0),
    )
    private val links = listOf(LinkInfo("lan:127.0.0.1:9002", b, "lan", true))
    private val feed = mutableListOf<Msg>()
    private val dms = mutableMapOf<String, MutableList<Msg>>()
    private val security = mutableListOf<Sec>()
    private var debug = DebugState(false, false)
    private var lamport = 0L
    private var n = 0

    init {
        ex.scheduleAtFixedRate({
            n++
            emit(MeshEvent.MessageAdded(msg(c, null, "sample post $n", mine = false, MsgStatus.RECEIVED, 2, b).also { feed += it }))
            if (n % 2 == 0) emit(MeshEvent.SecurityLogged(sec(SecKind.BAD_SIGNATURE, "sample bad signature $n")))
            if (n % 3 == 0) emit(MeshEvent.MessageAdded(msg(c, self.id, "sample dm $n", mine = false, MsgStatus.RECEIVED, 2, b).also { dms.getOrPut(c) { mutableListOf() } += it }))
        }, 1, 5, TimeUnit.SECONDS)
    }

    private fun msg(from: String, to: String?, text: String, mine: Boolean, st: MsgStatus, hops: Int, via: String?) =
        Msg("m${++lamport}x${System.nanoTime() % 100000}", from, to, text, lamport, System.currentTimeMillis(), mine, st, hops, via)

    private fun sec(kind: SecKind, detail: String) = Sec(System.currentTimeMillis(), kind, detail, "lan:127.0.0.1:9002", c).also { security += it }

    private fun emit(e: MeshEvent) = listeners.forEach { it(e) }

    override fun snapshot(): Snapshot = ex.submit<Snapshot> {
        Snapshot(self, contacts, links, feed.toList(), dms.mapValues { it.value.toList() }, security.toList(), debug)
    }.get()

    override fun addListener(l: (MeshEvent) -> Unit) { ex.execute { listeners += l } }

    override fun exec(c: Cmd) { ex.execute { run(c) } }

    private fun run(cmd: Cmd) {
        when (cmd) {
            is Cmd.Post -> emit(MeshEvent.MessageAdded(msg(self.id, null, cmd.text, true, MsgStatus.SENT, 0, null).also { feed += it }))
            is Cmd.Dm -> {
                val m = msg(self.id, cmd.to, cmd.text, true, MsgStatus.QUEUED, 0, null).also { dms.getOrPut(cmd.to) { mutableListOf() } += it }
                emit(MeshEvent.MessageAdded(m))
                ex.schedule({ emit(MeshEvent.StatusChanged(m.id, MsgStatus.SENT, 0, null)) }, 1, TimeUnit.SECONDS)
                ex.schedule({ emit(MeshEvent.StatusChanged(m.id, MsgStatus.DELIVERED, 2, b)) }, 3, TimeUnit.SECONDS)
            }
            is Cmd.DmImg -> emit(MeshEvent.MessageAdded(msg(self.id, cmd.to, "(photo)", true, MsgStatus.SENT, 0, null)
                .copy(img = java.util.Base64.getEncoder().encodeToString(cmd.jpeg)).also { dms.getOrPut(cmd.to) { mutableListOf() } += it }))
            is Cmd.Name -> self = self.copy(name = cmd.name)
            is Cmd.Mute -> { contacts = contacts.map { if (it.id == cmd.id) it.copy(muted = cmd.on) else it }; emit(MeshEvent.NetworkChanged(contacts, links)) }
            is Cmd.Verify -> { contacts = contacts.map { if (it.id == cmd.id) it.copy(verified = cmd.on) else it }; emit(MeshEvent.NetworkChanged(contacts, links)) }
            is Cmd.Debug -> debug = if (cmd.flag == DebugFlag.TAMPER_RELAY) debug.copy(tamper = cmd.on) else debug.copy(drop = cmd.on)
            is Cmd.Attack -> emit(MeshEvent.SecurityLogged(sec(if (cmd.kind == Attack.FLOOD) SecKind.RATE_EQUIVOCATION else SecKind.BAD_SIGNATURE, "own attack ${cmd.kind}")))
        }
    }
}

/** Run with `./gradlew :uiserver:runFake` (port 8081, or pass --args="<port> [guest port]"; a guest port starts guest mode). */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 8081
    val node = FakeNode()
    val guest = args.getOrNull(1)?.toInt()?.let { GuestServer(node, it).apply { start() } }
    UiServer(node, port) { guest }.start()
    println("fake node UI on http://127.0.0.1:$port")
    Thread.currentThread().join()
}
