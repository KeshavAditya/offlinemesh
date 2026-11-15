package mesh.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Web (browser) links do not count toward maxLinks, and do not get refused by it. */
class WebLinkCapTest {
    private class Fake(override val name: String) : Transport {
        var l: TransportListener? = null
        val closed = mutableListOf<LinkId>()
        override fun start(listener: TransportListener) { l = listener }
        override fun stop() {}
        override fun send(link: LinkId, frame: ByteArray) = true
        override fun close(link: LinkId) { closed += link }
    }

    @Test fun webLinksExemptFromMaxLinks() {
        val net = SimNetwork()
        val lan = Fake("lan"); val web = Fake("web")
        val node = MeshNode(Files.createTempDirectory("weblink").toFile(), net, listOf(lan, web))
        node.offLoop = { r -> r() }
        node.start()
        repeat(8) { web.l!!.onLinkUp("w$it", "web 10.0.0.$it") }
        repeat(C.MAX_LINKS + 1) { lan.l!!.onLinkUp("l$it", "lan 10.0.1.$it:9001") }
        net.runFor(100)
        assertEquals(emptyList(), web.closed)
        assertEquals(listOf("l${C.MAX_LINKS}"), lan.closed)
        assertEquals(8 + C.MAX_LINKS, node.snapshot().links.size)
    }
}
