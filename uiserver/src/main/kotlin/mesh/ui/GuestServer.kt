package mesh.ui

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import mesh.core.MeshNode
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Guest access (5.13): a second server on 0.0.0.0 for browsers on the host's hotspot. Read the feed and post through the
 * host, which signs guest posts as its own POSTs ("[guest name] text"), so they obey the host's per-epoch limit.
 * No DMs, no keys, no controls. Everything is behind [pin]; every guest IP may post once per 5 s.
 */
class GuestServer(
    private val node: UiNode,
    private val port: Int = 8090,
    val pin: String = "%04d".format(SecureRandom().nextInt(10_000)),
) {
    constructor(node: MeshNode, port: Int = 8090) : this(node.asUiNode(), port)

    private val json = Json { encodeDefaults = true }
    private val page by lazy { javaClass.getResource("/web/guest.html")!!.readText() }
    private val lastPost = ConcurrentHashMap<String, Long>()
    private val lastBadPin = ConcurrentHashMap<String, Long>()
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/") { call.respondText(page, ContentType.Text.Html) }
                get("/api/feed") {
                    if (!pinOk(call)) return@get call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                    val feed = node.snapshot().feed.sortedWith(compareBy({ it.lamport }, { it.id }))
                    call.respondText(json.encodeToJsonElement(feed).toString(), ContentType.Application.Json)
                }
                post("/api/guest-post") {
                    if (!pinOk(call)) return@post call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                    val body = try { guestPostBody(call.receiveText()) } catch (e: IllegalArgumentException) {
                        return@post call.respondText(e.message ?: "bad request", status = HttpStatusCode.BadRequest)
                    }
                    val now = System.currentTimeMillis()
                    val ip = call.request.origin.remoteAddress
                    val prev = lastPost.put(ip, now)
                    if (prev != null && now - prev < 5_000) {
                        lastPost[ip] = prev
                        return@post call.respondText("one post per 5 s", status = HttpStatusCode.TooManyRequests)
                    }
                    node.exec(Cmd.Post(body))
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }

    /** Constant-time PIN check; after a wrong PIN an IP gets no answer but 403 for 1 s, which slows guessing 10,000 PINs to hours. */
    private fun pinOk(call: ApplicationCall): Boolean {
        val ip = call.request.origin.remoteAddress
        val now = System.currentTimeMillis()
        if (now - (lastBadPin[ip] ?: 0L) < 1_000) return false
        val given = call.request.queryParameters["pin"] ?: ""
        val ok = MessageDigest.isEqual(given.toByteArray(), pin.toByteArray())
        if (!ok) lastBadPin[ip] = now
        return ok
    }

    /** Join URLs to show the host (5.13): one per IPv4 address on an ap*, swlan* or wlan* interface, or all non-loopback IPv4 if none. */
    fun urls(): List<String> {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
        fun v4(list: List<NetworkInterface>) = list.flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>() }
        val hotspot = v4(all.filter { i -> listOf("ap", "swlan", "wlan").any { i.name.startsWith(it) } })
        return (hotspot.ifEmpty { v4(all) }).map { "http://${it.hostAddress}:$port/?pin=$pin" }
    }
}

private val GUEST_NAME = Regex("""[^\]\[\p{Cntrl}]{1,20}""")

/** Validates {"name","text"} and returns the POST body "[guest name] text" (max 1,000 bytes, MAX_POST_BODY). */
fun guestPostBody(raw: String): String {
    val o = Json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException("not a JSON object")
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        ?: throw IllegalArgumentException("missing string field '$k'")
    val name = str("name"); val text = str("text")
    require(GUEST_NAME.matches(name)) { "name: 1 to 20 characters, no brackets" }
    require(text.isNotEmpty()) { "text is empty" }
    val body = "[guest $name] $text"
    require(body.toByteArray().size <= 1_000) { "text too long" }
    return body
}
