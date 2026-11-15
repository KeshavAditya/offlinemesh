package mesh.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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
import kotlinx.coroutines.delay
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
import mesh.core.Stats
import java.util.concurrent.CopyOnWriteArraySet

/** What the UI needs from a node. MeshNode is a final class, so a fake for UI work implements this instead. */
interface UiNode {
    fun snapshot(): Snapshot
    fun addListener(l: (MeshEvent) -> Unit)
    fun exec(c: Cmd)
    fun stats(): Stats = snapshot().stats
}

fun MeshNode.asUiNode(): UiNode = object : UiNode {
    override fun snapshot() = this@asUiNode.snapshot()
    override fun addListener(l: (MeshEvent) -> Unit) = this@asUiNode.addListener(l)
    override fun exec(c: Cmd) = this@asUiNode.exec(c)
    override fun stats() = this@asUiNode.stats()
}

/**
 * Serves the page, /ws, /api/snapshot and /api/cmd on 127.0.0.1 only (section 6), plus /api/qr and /api/guest for the
 * Network tab. [guest] returns the running guest server (5.13) or null when guest mode is off.
 */
class UiServer(private val node: UiNode, private val port: Int, private val guest: () -> GuestServer? = { null }) {
    constructor(node: MeshNode, port: Int, guest: () -> GuestServer? = { null }) : this(node.asUiNode(), port, guest)

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
                get("/api/stats") { call.respondText(json.encodeToString(Stats.serializer(), node.stats()), ContentType.Application.Json) }
                get("/api/snapshot") { call.respondText(snapshotJson(), ContentType.Application.Json) }
                post("/api/cmd") {
                    val cmd = try { parseCmd(call.receiveText()) } catch (e: IllegalArgumentException) {
                        return@post call.respondText(e.message ?: "bad command", status = HttpStatusCode.BadRequest)
                    }
                    node.exec(cmd)
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
                get("/api/qr") {
                    val text = call.request.queryParameters["text"].orEmpty()
                    if (text.isEmpty() || text.length > MAX_QR_TEXT)
                        return@get call.respondText("text: 1 to $MAX_QR_TEXT characters", status = HttpStatusCode.BadRequest)
                    call.respondText(qrSvg(text), ContentType.Image.SVG)
                }
                get("/api/guest") {
                    val g = guest()  // {"pin","urls"} while guest mode is on, else null; the page polls it to catch PIN rotation
                    val body = if (g == null) "null" else buildJsonObject { put("pin", g.pin); put("urls", json.encodeToJsonElement(g.urls())) }.toString()
                    call.respondText(body, ContentType.Application.Json)
                }
                webSocket("/ws") {
                    val q = Channel<String>(1000)
                    subs += q  // subscribe before the snapshot so no event is missed; the page dedups by id
                    try {
                        send(Frame.Text(snapshotJson()))
                        launch { for (s in q) send(Frame.Text(s)); this@webSocket.cancel() }
                        launch { while (true) { delay(2_000); q.trySend(statsJson()) } }   // U1; through q so sends never interleave
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

    private fun statsJson() = buildJsonObject { put("t", "stats"); put("stats", json.encodeToJsonElement(node.stats())) }.toString()

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
        is MeshEvent.GroupChanged -> buildJsonObject { put("t", "group"); put("group", json.encodeToJsonElement(e.group)) }
        MeshEvent.Reset -> snapshotJson()   // U2: a wipe or a peer revoke replaced state; the page re-renders from it
        is MeshEvent.GroupStatusChanged -> buildJsonObject {
            put("t", "gstatus"); put("id", e.id); put("delivered", e.delivered); put("total", e.total)
        }
    }.toString()
}

private const val MAX_QR_TEXT = 200

/** QR code of [text] as a black-on-white SVG, with the 4-module quiet zone scanners need. */
internal fun qrSvg(text: String): String {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
    val d = StringBuilder()
    for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) d.append("M$x ${y}h1v1h-1z")
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" shape-rendering="crispEdges">""" +
        """<rect width="100%" height="100%" fill="#fff"/><path d="$d"/></svg>"""
}

private val LOCAL = setOf("127.0.0.1", "localhost")

/** Host must be loopback (any port, adb forward changes it); Origin, if sent, must be a loopback http page. */
internal fun localOnly(host: String?, origin: String?): Boolean {
    if (host == null || host.substringBeforeLast(':') !in LOCAL) return false
    if (origin == null) return true
    return origin.startsWith("http://") && origin.removePrefix("http://").substringBefore(':').substringBefore('/') in LOCAL
}
