package mesh.ui

import mesh.core.BundleResult
import mesh.core.C
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleRouteTest {
    @Test fun downloadAndUpload() {
        val file = "OMB1-test-bytes".toByteArray()
        val got = ArrayList<ByteArray>()
        val node = object : UiNode by FakeNode() {
            override fun exportBundle() = file
            override fun importBundle(bytes: ByteArray) = got.add(bytes).let { if (bytes.contentEquals(file)) BundleResult("ab", 3, 2, 1, 0) else null }
        }
        val port = ServerSocket(0).use { it.localPort }
        val ui = UiServer(node, port).apply { start() }
        try {
            val http = HttpClient.newHttpClient()
            val uri = URI("http://127.0.0.1:$port/api/bundle")
            val dl = http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofByteArray())
            assertEquals(200, dl.statusCode())
            assertContentEquals(file, dl.body())
            assertEquals("application/octet-stream", dl.headers().firstValue("content-type").get())
            assertTrue(Regex("""attachment; filename="offlinemesh-\d{4}-\d\d-\d\d\.omb"""").matches(dl.headers().firstValue("content-disposition").get()))

            fun up(b: ByteArray) = http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofByteArray(b)).build(), HttpResponse.BodyHandlers.ofString())
            val ok = up(file)
            assertEquals(200, ok.statusCode())
            assertEquals("""{"ok":true,"from":"ab","total":3,"accepted":2,"duplicates":1,"rejected":0}""", ok.body())
            assertEquals(400, up("junk".toByteArray()).statusCode())
            assertEquals(413, up(ByteArray(C.BUNDLE_MAX_BYTES + 1)).statusCode())
            assertEquals(2, got.size, "the oversized body never reaches the node")
        } finally { ui.stop() }
    }
}
