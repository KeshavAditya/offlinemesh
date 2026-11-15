package mesh.desktop

import mesh.core.Identity
import mesh.core.MeshNode
import mesh.core.ExecutorScheduler
import mesh.lan.LanTransport
import mesh.ui.GuestServer
import mesh.ui.UiServer
import mesh.ui.WebTransport
import java.io.File
import kotlin.system.exitProcess

const val USAGE = "usage: desktop --data <dir> --ui-port <n> --listen <n> [--peer host:port]... [--no-discovery] [--guest-port <n>]"

class Args(val data: File, val uiPort: Int, val listen: Int, val peers: List<String>, val discovery: Boolean, val guestPort: Int? = null)

fun parseArgs(a: Array<String>): Args {
    var data: File? = null; var ui: Int? = null; var listen: Int? = null
    val peers = mutableListOf<String>(); var discovery = true; var guest: Int? = null
    var i = 0
    fun value(): String = a.getOrNull(++i) ?: throw IllegalArgumentException("${a[i - 1]} needs a value")
    fun port(): Int = value().toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("bad port: ${a[i]}")
    while (i < a.size) {
        when (a[i]) {
            "--data" -> data = File(value())
            "--ui-port" -> ui = port()
            "--listen" -> listen = port()
            "--peer" -> peers += value().also {
                require(Regex("""[^:\s]+:\d{1,5}""").matches(it)) { "bad peer (want host:port): $it" }
            }
            "--no-discovery" -> discovery = false
            "--guest-port" -> guest = port()
            else -> throw IllegalArgumentException("unknown argument: ${a[i]}")
        }
        i++
    }
    return Args(
        data ?: throw IllegalArgumentException("--data is required"),
        ui ?: throw IllegalArgumentException("--ui-port is required"),
        listen ?: throw IllegalArgumentException("--listen is required"),
        peers, discovery, guest,
    )
}

fun main(argv: Array<String>) {
    val args = try { parseArgs(argv) } catch (e: IllegalArgumentException) {
        System.err.println(e.message); System.err.println(USAGE); exitProcess(2)
    }
    val identity = Identity.loadOrCreate(args.data)
    val lan = LanTransport(args.listen, args.peers, if (args.discovery) identity.nodeId.copyOf(4) else null)
    val web = WebTransport()
    val node = MeshNode(args.data, ExecutorScheduler(), listOf(lan, web))
    val guest = args.guestPort?.let { GuestServer(node, it, web) }
    val ui = UiServer(node, args.uiPort) { guest }
    node.start()
    ui.start()
    guest?.start()
    println("node ${identity.idHex} ui http://127.0.0.1:${args.uiPort}/ lan :${args.listen} peers ${args.peers}")
    guest?.let { g -> println("guest ${g.urls().joinToString(" ")} (browser mesh node: ws /mesh?pin=${g.pin})") }
    Runtime.getRuntime().addShutdownHook(Thread { guest?.stop(); ui.stop(); node.stop() })
    Thread.currentThread().join()
}
