package mesh.ui

import mesh.core.Attack
import mesh.core.DebugFlag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CmdTest {
    private val id = "a3f29c01d4e5b6f7"

    @Test fun parsesEveryCommand() {
        assertEquals(Cmd.Post("hi"), parseCmd("""{"t":"post","text":"hi"}"""))
        assertEquals(Cmd.Dm(id, "yo"), parseCmd("""{"t":"dm","to":"$id","text":"yo"}"""))
        assertEquals(Cmd.Name("A"), parseCmd("""{"t":"name","name":"A"}"""))
        assertEquals(Cmd.Mute(id, true), parseCmd("""{"t":"mute","id":"$id","on":true}"""))
        assertEquals(Cmd.Verify(id, false), parseCmd("""{"t":"verify","id":"$id","on":false}"""))
        assertEquals(Cmd.Debug(DebugFlag.DROP_RELAY, true), parseCmd("""{"t":"debug","flag":"drop","on":true}"""))
        assertEquals(Cmd.Attack(Attack.REPLAY), parseCmd("""{"t":"attack","kind":"replay"}"""))
    }

    @Test fun rejectsBadInput() {
        val bad = listOf(
            "", "nope", "[]", "{}", """{"t":"launch"}""", """{"t":1}""",
            """{"t":"post"}""", """{"t":"post","text":"  "}""", """{"t":"post","text":5}""",
            """{"t":"post","text":"${"x".repeat(1001)}"}""",
            """{"t":"dm","to":"zz","text":"hi"}""", """{"t":"dm","to":"${id.uppercase()}","text":"hi"}""",
            """{"t":"name","name":"${"n".repeat(33)}"}""",
            """{"t":"mute","id":"$id","on":"true"}""", """{"t":"mute","id":"$id"}""",
            """{"t":"debug","flag":"evil","on":true}""", """{"t":"attack","kind":"nuke"}""",
        )
        for (b in bad) assertFailsWith<IllegalArgumentException>(b) { parseCmd(b) }
    }

    @Test fun onlyLoopbackHostsAndOrigins() {
        assertTrue(localOnly("127.0.0.1:8081", null))
        assertTrue(localOnly("localhost:8081", "http://localhost:8081"))
        assertFalse(localOnly("evil.com:8081", null))
        assertFalse(localOnly("127.0.0.1:8081", "http://evil.com"))
        assertFalse(localOnly("127.0.0.1:8081", "null"))
        assertFalse(localOnly(null, null))
    }
}
