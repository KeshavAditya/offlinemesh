package mesh.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// U8: a newcomer copying a verified contact's name is flagged, never banned.
class ImpersonationTest {
    private class Line(val net: SimNetwork, val a: SimNode, val b: SimNode, val c: SimNode)
    private fun line(): Line {
        val net = SimNetwork()
        val (a, b, c) = listOf("A", "B", "C").map { net.node(it) }
        net.link("A", "B"); net.link("B", "C")
        net.runFor(40_000)
        return Line(net, a, b, c)
    }
    private fun SimNode.imp() = security().filter { it.kind == SecKind.NAME_IMPERSONATION }
    private fun SimNode.contact(id: String) = node.snapshot().contacts.single { it.id == id }

    private fun copyName(name: String): Line = line().apply {
        b.node.setVerified(a.id, true)
        net.runFor(1_000)
        c.node.setName(name)
        net.runFor(40_000)
    }

    @Test fun copiedNameIsSuspectAndLoggedOnce() = copyName("A").run {
        val logs = b.imp()
        assertEquals(1, logs.size, "logs $logs")
        assertEquals(c.id, logs[0].src); assertEquals(a.id, logs[0].detail)
        assertTrue(b.contact(c.id).suspect); assertFalse(b.contact(a.id).suspect)
        assertEquals(1, b.node.stats().drops["NAME_IMPERSONATION"])
        net.runFor(8 * 60_000)   // C keeps sending PRESENCE every 30 s: still within 10 minutes of the first log
        assertEquals(1, b.imp().size)
        assertTrue(b.node.snapshot().contacts.none { it.id != c.id && it.suspect })
        c.node.setName("Carol")
        net.runFor(40_000)
        assertFalse(b.contact(c.id).suspect)
    }

    @Test fun caseAndWhitespaceVariantsTrigger() {
        for (n in listOf("a", "  A ", " a\t")) copyName(n).run {
            assertEquals(1, b.imp().size, "name '$n'"); assertTrue(b.contact(c.id).suspect)
        }
    }

    @Test fun unrelatedNameDoesNot() = copyName("Alex").run {
        assertTrue(b.imp().isEmpty()); assertFalse(b.contact(c.id).suspect)
    }

    @Test fun verifyingLaterFlagsExistingCopy() = line().run {
        c.node.setName("A")
        net.runFor(40_000)
        assertTrue(b.imp().isEmpty()); assertFalse(b.contact(c.id).suspect)
        b.node.setVerified(a.id, true)
        net.runFor(1_000)
        assertEquals(c.id, b.imp().single().src); assertTrue(b.contact(c.id).suspect)
        assertTrue(a.imp().isEmpty() && c.imp().isEmpty())   // only B verified A
    }
}
