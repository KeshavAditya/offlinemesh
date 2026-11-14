package mesh.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ArgsTest {
    @Test fun parsesAll() {
        val a = parseArgs(arrayOf("--data", "d", "--ui-port", "8081", "--listen", "9001", "--peer", "127.0.0.1:9002", "--peer", "h:9003", "--no-discovery"))
        assertEquals(8081, a.uiPort); assertEquals(9001, a.listen)
        assertEquals(listOf("127.0.0.1:9002", "h:9003"), a.peers); assertFalse(a.discovery)
    }

    @Test fun rejectsBad() {
        for (bad in listOf(arrayOf(), arrayOf("--data"), arrayOf("--data", "d", "--ui-port", "x", "--listen", "1"),
            arrayOf("--data", "d", "--ui-port", "1", "--listen", "1", "--peer", "nohost"), arrayOf("--bogus")))
            assertFailsWith<IllegalArgumentException> { parseArgs(bad) }
    }
}
