package mesh.ui

import mesh.core.DebugState
import mesh.core.MeshEvent
import mesh.core.SelfInfo
import mesh.core.Snapshot
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// research/ADVANCES.md T8 (Briar audit OTF-002, NF-008): the hotspot server hands out only the APK and its own pages.
class GuestServerAttackTest {
    private val node = object : UiNode {
        override fun snapshot() = Snapshot(SelfInfo("aaaaaaaaaaaaaaaa", "B"), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), DebugState(false, false))
        override fun addListener(l: (MeshEvent) -> Unit) {}
        override fun exec(c: Cmd) {}
    }
    // The APK sits in the node's data folder next to files that must never leave it.
    private val dir = kotlin.io.path.createTempDirectory("t8").toFile()
    private val apk = File(dir, "app.apk").apply { writeText("APK-BYTES-0123456789") }
    init { listOf("messages.log", "identity.bin", "secret.txt").forEach { File(dir, it).writeText("SECRET-$it") } }
    private val port = ServerSocket(0).use { it.localPort }
    private val server = GuestServer(node, port, PinGuard(), WebTransport(), apk).apply { start() }

    @AfterTest fun stop() { server.stop(); dir.deleteRecursively() }

    /** Sends [head] as-is (no client-side path cleaning); returns the status (0 if the server just closed) and the whole reply. */
    private fun raw(head: String): Pair<Int, String> = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().apply { write("$head\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); flush() }
        val reply = try { s.getInputStream().readBytes().toString(Charsets.ISO_8859_1) } catch (e: java.io.IOException) { "" }
        (reply.substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull() ?: 0) to reply
    }

    private fun assertSafe(head: String) {
        val (status, reply) = raw(head)
        assertFalse("SECRET" in reply, "$head leaked a data file")
        assertFalse(status in 500..599, "$head gave $status")
        // A 302 is safe only to the fixed page root (captive-portal catch-all), never a reflected path or Host.
        val loc = reply.lineSequence().firstOrNull { it.startsWith("Location:", true) }?.substringAfter(':')?.trim()
        assertTrue(status == 0 || status in 400..499 || (status == 302 && loc == "/"), "$head gave $status $loc")
    }

    @Test fun traversalAndOddPathsServeNothingOutsideTheApkAndPages() {
        val paths = listOf(
            "/../secret.txt", "/../../secret.txt", "/%2e%2e/secret.txt", "/%2e%2e%2fsecret.txt", "/..%2f..%2fsecret.txt",
            "/..%5csecret.txt", "/app.apk/../secret.txt", "/app.apk/../../secret.txt", "/meshnode.js/../../messages.log",
            "/messages.log", "/identity.bin", "/data/messages.log", "/web/index.html", "/app.apk%00.html", "//secret.txt",
            "/" + "a".repeat(20_000), "/" + "../".repeat(2_000) + "secret.txt",
        )
        for (p in paths) assertSafe("GET $p HTTP/1.1")
        val (status, reply) = raw("GET /app.apk HTTP/1.1")   // control: the APK itself is public, so raw() really talks HTTP
        assertTrue(status == 200 && "APK-BYTES" in reply, "control GET /app.apk gave $status")
        assertTrue(raw("GET /../secret.txt HTTP/1.1").first == 302)
    }

    @Test fun captiveProbesRedirectToTheFixedRoot() {
        for (p in listOf("/generate_204", "/gen_204", "/hotspot-detect.html", "/library/test/success.html", "/ncsi.txt",
                "/connecttest.txt", "/canonical.html", "/success.txt", "/a/b?x=http://evil")) {
            val (status, reply) = raw("GET $p HTTP/1.1")
            assertTrue(status == 302 && "\r\nLocation: /\r\n" in reply, "$p gave $status")
        }
    }

    @Test fun onlyGetServesTheApk() {
        for (m in listOf("POST", "PUT", "DELETE", "PATCH")) assertSafe("$m /app.apk HTTP/1.1\r\nContent-Length: 0")
    }

    @Test fun rangeHeadersGiveApkBytesOrAnErrorNeverAnotherFile() {
        val ranges = listOf("bytes=0-3", "bytes=-3", "bytes=100-200", "bytes=0-1,3-4", "bytes=abc", "bytes=99999999999999999999-", "bytes=5-2")
        for (r in ranges) {
            val (status, reply) = raw("GET /app.apk HTTP/1.1\r\nRange: $r")
            assertFalse("SECRET" in reply || status in 500..599, "Range $r gave $status")
            assertTrue(status in 200..299 || status == 416, "Range $r gave $status")
        }
    }

    /** T7, UI side: mesh data reaches the DOM only via textContent; HTML sinks may take fixed string literals only. */
    @Test fun pagesNeverWriteMeshDataAsHtml() {
        val sink = Regex("""\.innerHTML\s*\+?=|outerHTML|insertAdjacentHTML|document\.write""")
        val literal = Regex("""'(?:[^'\\]|\\.)*'""")
        for (f in listOf("index.html", "guest.html", "webbackend.js", "meshnode.js", "meshcore.js")) {
            val src = javaClass.getResource("/web/$f")!!.readText()
            val bad = sink.findAll(src).map { m -> src.substring(m.range.first, src.indexOf(';', m.range.last).let { if (it < 0) src.length else it }) }
                .filter { !it.startsWith(".innerHTML") || !literal.replace(it.substringAfter('='), "").matches(Regex("""[\s+]*""")) }.toList()
            assertTrue(bad.isEmpty(), "$f writes non-literal HTML: $bad")
        }
    }
}
