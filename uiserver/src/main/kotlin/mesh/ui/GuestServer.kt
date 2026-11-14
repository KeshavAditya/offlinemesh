package mesh.ui

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guest access (5.13): a second server on 0.0.0.0 for browsers on the host's hotspot. Read the feed and post through the
 * host, which signs guest posts as its own POSTs ("[guest name] text"), so they obey the host's per-epoch limit.
 * No DMs, no keys, no controls. Everything is behind [pin]; every guest IP may post once per 5 s.
 */
class GuestServer(
    private val node: UiNode,
    private val port: Int = 8090,
    private val guard: PinGuard = PinGuard(),
) {
    constructor(node: MeshNode, port: Int = 8090) : this(node.asUiNode(), port)

    /** Current PIN; it changes when [PinGuard] rotates it, so re-read it (and [urls]) from [PinGuard.onRotate]. */
    val pin: String get() = guard.pin

    private val json = Json { encodeDefaults = true }
    private val page by lazy { javaClass.getResource("/web/guest.html")!!.readText() }
    private val lastPost = ConcurrentHashMap<String, Long>()
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
                    // Cap the body before reading it: Content-Length first, then a bounded read for chunked bodies.
                    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0
                    val raw = if (declared > MAX_BODY) null else call.receiveChannel().readRemaining(MAX_BODY + 1L).readByteArray()
                    if (raw == null || raw.size > MAX_BODY)
                        return@post call.respondText("body too large", status = HttpStatusCode.PayloadTooLarge)
                    val body = try { guestPostBody(raw.decodeToString()) } catch (e: IllegalArgumentException) {
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

    private fun pinOk(call: ApplicationCall) =
        guard.check(call.request.origin.remoteAddress, call.request.queryParameters["pin"] ?: "")

    /** Join URLs to show the host (5.13): one per IPv4 address on an ap*, swlan* or wlan* interface, or all non-loopback IPv4 if none. */
    fun urls(): List<String> {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
        fun v4(list: List<NetworkInterface>) = list.flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>() }
        val hotspot = v4(all.filter { i -> listOf("ap", "swlan", "wlan").any { i.name.startsWith(it) } })
        return (hotspot.ifEmpty { v4(all) }).map { "http://${it.hostAddress}:$port/?pin=$pin" }
    }
}

private const val MAX_BODY = 4096

/**
 * PIN checks against guessing (5.13 keeps the 4-digit PIN): an IP with [perIp] wrong PINs is refused from then on, and after
 * [global] wrong PINs from anyone the PIN rotates (old QR codes stop working; the host must show the new one).
 * Each attempt is counted before the compare, so parallel requests cannot all slip past the limit.
 */
class PinGuard(private val perIp: Int = 5, private val global: Int = 20, var onRotate: (String) -> Unit = {}) {
    @Volatile var pin: String = newPin(); private set
    private val fails = ConcurrentHashMap<String, Int>()
    private val globalFails = AtomicInteger()

    fun check(ip: String, given: String): Boolean {
        val n = fails.compute(ip) { _, v -> (v ?: 0) + 1 }!!
        if (n > perIp) return false
        if (MessageDigest.isEqual(given.toByteArray(), pin.toByteArray())) { fails.compute(ip) { _, v -> (v ?: 1) - 1 }; return true }
        if (globalFails.incrementAndGet() >= global) rotate()
        return false
    }

    @Synchronized private fun rotate() {
        if (globalFails.get() < global) return
        pin = newPin(); fails.clear(); globalFails.set(0)
        System.err.println("guest: $global wrong PINs, PIN rotated")
        onRotate(pin)
    }

    private fun newPin() = "%04d".format(SecureRandom().nextInt(10_000))
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
