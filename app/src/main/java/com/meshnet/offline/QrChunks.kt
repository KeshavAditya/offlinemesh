package com.meshnet.offline

import java.security.MessageDigest
import java.util.Base64

/**
 * Animated QR: bytes too big for one QR as numbered text frames `OMQ1:<hash>:<i>/<n>:<base64 chunk>`, i from 1.
 * <hash> is the first 8 bytes of SHA-256 of the whole payload in hex. The receiver still runs importEnvelope's full
 * checks; the hash only stops a mix of two sequences (or a corrupt chunk) from being handed on.
 */
object QrChunks {
    const val PREFIX = "OMQ1:"
    const val CHUNK = 600   // raw bytes per frame: 800 base64 chars, about QR version 18 at level L

    fun hash(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).take(8).joinToString("") { "%02x".format(it) }

    fun split(b: ByteArray, chunk: Int = CHUNK): List<String> {
        val h = hash(b); val parts = (b.indices step chunk).map { b.copyOfRange(it, minOf(it + chunk, b.size)) }
        return parts.mapIndexed { i, p -> "$PREFIX$h:${i + 1}/${parts.size}:${Base64.getEncoder().encodeToString(p)}" }
    }

    /** Collects frames in any order, duplicates ignored. A frame of another sequence starts over. */
    class Assembler {
        private var h: String? = null
        private var parts = arrayOfNulls<ByteArray>(0)
        val have get() = parts.count { it != null }
        val total get() = parts.size

        /** False if [s] is not a well-formed frame. Call [result] after a true. */
        fun add(s: String): Boolean {
            if (!s.startsWith(PREFIX)) return false
            val f = s.removePrefix(PREFIX).split(":", limit = 3).takeIf { it.size == 3 } ?: return false
            val (i, n) = f[1].split("/").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return false
            if (n !in 1..MAX_FRAMES || i !in 1..n || f[0].length != 16) return false
            val chunk = try { Base64.getDecoder().decode(f[2]) } catch (e: IllegalArgumentException) { return false }
            if (f[0] != h || n != parts.size) { h = f[0]; parts = arrayOfNulls(n) }
            parts[i - 1] = parts[i - 1] ?: chunk
            return true
        }

        /** The payload once every frame is in and the hash matches; null while incomplete. Throws on a hash mismatch (and starts over). */
        fun result(): ByteArray? {
            if (parts.isEmpty() || have < total) return null
            val b = parts.reduce { a, c -> a!! + c!! }!!
            if (hash(b) != h) { parts = arrayOfNulls(0); h = null; throw IllegalStateException("hash mismatch") }
            return b
        }
    }

    private const val MAX_FRAMES = 200   // 120 KB at CHUNK; above the 60 KB frame cap, below a memory problem
}
