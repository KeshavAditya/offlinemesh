package mesh.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream

internal class Stored(val env: Envelope, val atMs: Long, val relayOnly: Boolean)

/** 5.11: POST/DM envelopes (for sync) and the append-only messages.log: u32 length, envelope, u64 receivedAtMs. */
internal class Store(private val file: File) {
    val envs = LinkedHashMap<String, Stored>()   // msgId hex, oldest first

    fun put(e: Envelope, at: Long, relayOnly: Boolean, log: Boolean) {
        envs[Crypto.hex(e.msgId)] = Stored(e, at, relayOnly)
        if (relayOnly && envs.values.count { it.relayOnly } > C.RELAY_STORE_CAP)
            envs.remove(envs.entries.first { it.value.relayOnly }.key)
        if (envs.size > KEEP_CAP + C.RELAY_STORE_CAP) envs.remove(envs.keys.first())
        if (log) append(e, at)
    }

    fun dropRelay(msgId: String) { if (envs[msgId]?.relayOnly == true) envs.remove(msgId) }

    // shortcut: the log is never compacted, fine for a demo; compact on start-up if it grows past a few MB.
    fun append(e: Envelope, at: Long) {
        val b = Codec.encodeEnvelope(e)
        DataOutputStream(FileOutputStream(file, true).buffered()).use { it.writeInt(b.size); it.write(b); it.writeLong(at) }
    }

    /** Reads the log in order; stops quietly at a truncated or corrupt tail. */
    fun replay(f: (Envelope, Long) -> Unit) {
        if (!file.exists()) return
        DataInputStream(file.inputStream().buffered()).use { inp ->
            try {
                while (true) {
                    val n = inp.readInt()
                    if (n !in 1..C.MAX_FRAME) return
                    val b = ByteArray(n).also { inp.readFully(it) }
                    val at = inp.readLong()
                    Codec.decodeEnvelope(b)?.let { f(it, at) }
                }
            } catch (e: EOFException) { }
        }
    }

    companion object { const val KEEP_CAP = 2_000 }
}
