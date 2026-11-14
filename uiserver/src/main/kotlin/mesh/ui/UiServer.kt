package mesh.ui

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mesh.core.MeshEvent
import mesh.core.MeshNode
import mesh.core.Snapshot
import java.util.concurrent.CopyOnWriteArraySet

/** What the UI needs from a node. MeshNode is a final class, so a fake for UI work implements this instead. */
interface UiNode {
    fun snapshot(): Snapshot
    fun addListener(l: (MeshEvent) -> Unit)
    fun exec(c: Cmd)
}

fun MeshNode.asUiNode(): UiNode = object : UiNode {
    override fun snapshot() = this@asUiNode.snapshot()
    override fun addListener(l: (MeshEvent) -> Unit) = this@asUiNode.addListener(l)
    override fun exec(c: Cmd) = this@asUiNode.exec(c)
}

/** Serves the page, /ws, /api/snapshot and /api/cmd on 127.0.0.1 only (section 6). */
class UiServer(private val node: UiNode, private val port: Int) {
    constructor(node: MeshNode, port: Int) : this(node.asUiNode(), port)

    private val json = Json { encodeDefaults = true }
    private val subs = CopyOnWriteArraySet<Channel<String>>()
    private val page by lazy { javaClass.getResource("/web/index.html")!!.readText() }
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        node.addListener { e ->
            val s = eventJson(e)
            // A client too slow to keep up is dropped; it reconnects and re-renders from a fresh snapshot.
            for (q in subs) if (!q.trySend(s).isSuccess) q.close()
        }
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(WebSockets)
            intercept(ApplicationCallPipeline.Plugins) {
                // Block other web pages (CSRF, DNS rebinding) from driving this node through the browser.
                if (!localOnly(call.request.headers[HttpHeaders.Host], call.request.headers[HttpHeaders.Origin])) {
                    call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                    finish()
                }
            }
            routing {
                get("/") { call.respondText(page, ContentType.Text.Html) }
                get("/api/snapshot") { call.respondText(snapshotJson(), ContentType.Application.Json) }
                post("/api/cmd") {
                    val cmd = try { parseCmd(call.receiveText()) } catch (e: IllegalArgumentException) {
                        return@post call.respondText(e.message ?: "bad command", status = HttpStatusCode.BadRequest)
                    }
                    node.exec(cmd)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
                webSocket("/ws") {
                    val q = Channel<String>(1000)
                    subs += q  // subscribe before the snapshot so no event is missed; the page dedups by id
                    try {
                        send(Frame.Text(snapshotJson()))
                        launch { for (s in q) send(Frame.Text(s)); this@webSocket.cancel() }
                        for (f in incoming) if (f is Frame.Text) {
                            try { node.exec(parseCmd(f.readText())) } catch (_: IllegalArgumentException) {}
                        }
                    } finally {
                        subs -= q
                        q.close()
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 1000)
    }

    private fun snapshotJson() =
        JsonObject(mapOf("t" to JsonPrimitive("snapshot")) + json.encodeToJsonElement(node.snapshot()).jsonObject).toString()

    private fun eventJson(e: MeshEvent) = when (e) {
        is MeshEvent.MessageAdded -> buildJsonObject { put("t", "msg"); put("msg", json.encodeToJsonElement(e.msg)) }
        is MeshEvent.StatusChanged -> buildJsonObject {
            put("t", "status"); put("id", e.id); put("status", e.status.name); put("hops", e.hops); put("via", e.via)
        }
        is MeshEvent.NetworkChanged -> buildJsonObject {
            put("t", "net"); put("contacts", json.encodeToJsonElement(e.contacts)); put("links", json.encodeToJsonElement(e.links))
        }
        is MeshEvent.SecurityLogged -> buildJsonObject { put("t", "sec"); put("event", json.encodeToJsonElement(e.event)) }
    }.toString()
}

private val LOCAL = setOf("127.0.0.1", "localhost")

/** Host must be loopback (any port, adb forward changes it); Origin, if sent, must be a loopback http page. */
internal fun localOnly(host: String?, origin: String?): Boolean {
    if (host == null || host.substringBeforeLast(':') !in LOCAL) return false
    if (origin == null) return true
    return origin.startsWith("http://") && origin.removePrefix("http://").substringBefore(':').substringBefore('/') in LOCAL
}
