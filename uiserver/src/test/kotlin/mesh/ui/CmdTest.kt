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
        assertEquals(Cmd.Group("G", listOf(id)), parseCmd("""{"t":"group","name":"G","members":["$id","$id"]}"""))
        assertEquals(Cmd.GroupMembers(id, listOf()), parseCmd("""{"t":"group-members","id":"$id","members":[]}"""))
        assertEquals(Cmd.GroupMsg(id, "hi"), parseCmd("""{"t":"gmsg","group":"$id","text":"hi"}"""))
        assertEquals(Cmd.Profile(mesh.core.Profile.TUNNEL), parseCmd("""{"t":"profile","name":"TUNNEL"}"""))
    }

    @Test fun rejectsBadInput() {
        val bad = listOf(
            "", "nope", "[]", "{}", """{"t":"launch"}""", """{"t":1}""",
            """{"t":"post"}""", """{"t":"post","text":"  "}""", """{"t":"post","text":5}""",
            """{"t":"post","text":"${"x".repeat(1001)}"}""",
            """{"t":"dm","to":"zz","text":"hi"}""", """{"t":"dm","to":"${id.uppercase()}","text":"hi"}""",
            """{"t":"name","name":"${"n".repeat(33)}"}""",
            """{"t":"mute","id":"$id","on":"true"}""", """{"t":"mute","id":"$id"}""",
            """{"t":"group","name":"G"}""", """{"t":"group","name":"G","members":["zz"]}""", """{"t":"group","name":"G","members":"$id"}""",
            """{"t":"group","name":"G","members":[${(0..16).joinToString(",") { "\"%016x\"".format(it) }}]}""",
            """{"t":"group-members","id":"x","members":[]}""", """{"t":"gmsg","group":"$id","text":"${"x".repeat(1001)}"}""",
            """{"t":"debug","flag":"evil","on":true}""", """{"t":"attack","kind":"nuke"}""",
            """{"t":"profile","name":"tunnel"}""", """{"t":"profile","name":"FAST"}""", """{"t":"profile"}""",
        )
        for (b in bad) assertFailsWith<IllegalArgumentException>(b) { parseCmd(b) }
    }

    @Test fun photoDm() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(39_996)
        fun cmd(b: ByteArray) = """{"t":"dm","to":"$id","img":"${java.util.Base64.getEncoder().encodeToString(b)}"}"""
        val c = parseCmd(cmd(jpeg)) as Cmd.DmImg
        assertEquals(id, c.to); assertTrue(c.jpeg.contentEquals(jpeg))
        val bad = listOf(
            cmd(jpeg + 0),                                            // 40,000 bytes: no room for the kind byte
            cmd(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),        // PNG
            cmd(byteArrayOf()),
            """{"t":"dm","to":"$id","img":"not base64!"}""",
            """{"t":"dm","to":"$id","img":5}""",
            """{"t":"dm","to":"zz","img":"${java.util.Base64.getEncoder().encodeToString(jpeg)}"}""",
            """{"t":"dm","to":"$id","text":"${"x".repeat(40_000)}"}""",
        )
        for (b in bad) assertFailsWith<IllegalArgumentException>(b.take(80)) { parseCmd(b) }
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
