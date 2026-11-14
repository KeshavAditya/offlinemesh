package mesh.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import mesh.core.Attack
import mesh.core.DebugFlag
import mesh.core.MeshNode
import mesh.core.isJpeg
import java.util.Base64

/** Browser-to-server commands (section 6 "UI protocol"). */
sealed interface Cmd {
    data class Post(val text: String) : Cmd
    data class Dm(val to: String, val text: String) : Cmd
    class DmImg(val to: String, val jpeg: ByteArray) : Cmd
    data class Name(val name: String) : Cmd
    data class Mute(val id: String, val on: Boolean) : Cmd
    data class Verify(val id: String, val on: Boolean) : Cmd
    data class Debug(val flag: DebugFlag, val on: Boolean) : Cmd
    data class Attack(val kind: mesh.core.Attack) : Cmd
}

private val NODE_ID = Regex("[0-9a-f]{16}")

private fun jpeg(b64: String): ByteArray {
    require(b64.length <= 53_400) { "'img' too long" }     // base64 of 39,999 bytes is 53,332 chars
    val b = Base64.getDecoder().decode(b64)                   // throws IllegalArgumentException on bad base64
    require(b.size <= 39_999) { "'img' larger than 39,999 bytes" }
    require(isJpeg(b)) { "'img' is not a JPEG" }
    return b
}

/** Parses and validates one command. Throws IllegalArgumentException (incl. SerializationException) on anything bad. */
fun parseCmd(body: String): Cmd {
    val o = Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("not a JSON object")
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("missing string field '$k'")
    fun bool(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: throw IllegalArgumentException("missing boolean field '$k'")
    fun id(k: String) = str(k).also { require(NODE_ID.matches(it)) { "'$k' must be 16 lowercase hex chars" } }
    fun text(k: String, maxBytes: Int) = str(k).also {
        require(it.isNotBlank()) { "'$k' is empty" }
        require(it.toByteArray().size <= maxBytes) { "'$k' longer than $maxBytes bytes" }
    }
    return when (val t = str("t")) {
        "post" -> Cmd.Post(text("text", 1_000))           // MAX_POST_BODY
        // MAX_DM_PLAINTEXT 40,000 includes the 1-byte kind (5.3), so 39,999 for the text or JPEG
        "dm" -> if ("img" in o) Cmd.DmImg(id("to"), jpeg(str("img"))) else Cmd.Dm(id("to"), text("text", 39_999))
        "name" -> Cmd.Name(text("name", 32))              // MAX_NAME
        "mute" -> Cmd.Mute(id("id"), bool("on"))
        "verify" -> Cmd.Verify(id("id"), bool("on"))
        "debug" -> Cmd.Debug(
            when (str("flag")) { "tamper" -> DebugFlag.TAMPER_RELAY; "drop" -> DebugFlag.DROP_RELAY; else -> throw IllegalArgumentException("unknown flag") },
            bool("on"),
        )
        "attack" -> Cmd.Attack(
            when (str("kind")) { "flood" -> Attack.FLOOD; "spoof" -> Attack.SPOOF; "replay" -> Attack.REPLAY; else -> throw IllegalArgumentException("unknown attack") },
        )
        else -> throw IllegalArgumentException("unknown t '$t'")
    }
}

fun MeshNode.exec(c: Cmd) = when (c) {
    is Cmd.Post -> post(c.text)
    is Cmd.Dm -> sendDm(c.to, c.text)
    is Cmd.DmImg -> sendDmImage(c.to, c.jpeg)
    is Cmd.Name -> setName(c.name)
    is Cmd.Mute -> setMuted(c.id, c.on)
    is Cmd.Verify -> setVerified(c.id, c.on)
    is Cmd.Debug -> setDebug(c.flag, c.on)
    is Cmd.Attack -> attack(c.kind)
}
