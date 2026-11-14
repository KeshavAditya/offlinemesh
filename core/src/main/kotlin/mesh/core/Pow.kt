package mesh.core

import java.security.MessageDigest

// Section 5.3: SHA-256(core || u64 powNonce) starts with C.powBits(...) zero bits.
object Pow {
    fun bits(e: Envelope) = C.powBits(e.body.size, e.initTtl)

    fun verify(e: Envelope) = ok(prefix(e.core), e.powNonce, bits(e))

    /** Smallest nonce from 0 up, so nonce - 1 never verifies. */
    fun mine(core: ByteArray, bits: Int): Long {
        val p = prefix(core)
        var n = 0L
        while (!ok(p, n, bits)) n++
        return n
    }

    // Hash core once and clone the state per nonce: a 40 KB DM needs ~4M tries at 22 bits.
    private fun prefix(core: ByteArray) = MessageDigest.getInstance("SHA-256").apply { update(core) }

    private fun ok(prefix: MessageDigest, nonce: Long, bits: Int): Boolean {
        val md = prefix.clone() as MessageDigest
        for (i in 7 downTo 0) md.update((nonce ushr (i * 8)).toByte())
        val h = md.digest()
        for (i in 0 until bits) if ((h[i / 8].toInt() shr (7 - i % 8)) and 1 != 0) return false
        return true
    }
}
