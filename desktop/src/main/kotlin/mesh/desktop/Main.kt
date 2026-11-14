package mesh.desktop

import mesh.core.Identity
import mesh.core.MeshNode
import mesh.core.Scheduler
import mesh.lan.LanTransport
import mesh.ui.UiServer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val USAGE = "usage: desktop --data <dir> --ui-port <n> --listen <n> [--peer host:port]... [--no-discovery]"

class Args(val data: File, val uiPort: Int, val listen: Int, val peers: List<String>, val discovery: Boolean)

fun parseArgs(a: Array<String>): Args {
    var data: File? = null; var ui: Int? = null; var listen: Int? = null
    val peers = mutableListOf<String>(); var discovery = true
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
            else -> throw IllegalArgumentException("unknown argument: ${a[i]}")
        }
        i++
    }
    return Args(
        data ?: throw IllegalArgumentException("--data is required"),
        ui ?: throw IllegalArgumentException("--ui-port is required"),
        listen ?: throw IllegalArgumentException("--listen is required"),
        peers, discovery,
    )
}

/** Real scheduler (section 6): one thread runs every mesh task in order. */
class ExecutorScheduler : Scheduler {
    private val ex = Executors.newSingleThreadScheduledExecutor { Thread(it, "mesh-loop") }
    override fun nowMs() = System.currentTimeMillis()
    override fun post(task: () -> Unit) { ex.execute(guard(task)) }
    override fun schedule(delayMs: Long, task: () -> Unit) { ex.schedule(guard(task), delayMs, TimeUnit.MILLISECONDS) }
    // One failing task must not kill the loop thread.
    private fun guard(task: () -> Unit) = Runnable { try { task() } catch (e: Throwable) { e.printStackTrace() } }
}

fun main(argv: Array<String>) {
    val args = try { parseArgs(argv) } catch (e: IllegalArgumentException) {
        System.err.println(e.message); System.err.println(USAGE); exitProcess(2)
    }
    val identity = Identity.loadOrCreate(args.data)
    val lan = LanTransport(args.listen, args.peers, if (args.discovery) identity.nodeId.copyOf(4) else null)
    val node = MeshNode(args.data, ExecutorScheduler(), listOf(lan))
    val ui = UiServer(node, args.uiPort)
    node.start()
    ui.start()
    println("node ${identity.idHex} ui http://127.0.0.1:${args.uiPort}/ lan :${args.listen} peers ${args.peers}")
    Runtime.getRuntime().addShutdownHook(Thread { ui.stop(); node.stop() })
    Thread.currentThread().join()
}
