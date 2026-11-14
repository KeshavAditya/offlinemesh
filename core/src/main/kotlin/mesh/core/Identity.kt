package mesh.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

// Section 5.1: one P-256 key pair for ECDSA and ECDH, saved as identity.bin (u16 len + PKCS#8, u16 len + X.509).
class Identity(val private: PrivateKey, val publicDer: ByteArray) {
    val nodeId: ByteArray = Crypto.nodeId(publicDer)
    val idHex: String = Crypto.hex(nodeId)

    fun save(file: File) {
        val tmp = File(file.path + ".tmp")
        // Owner-only before the private key goes in (best effort: no-ops on file systems without POSIX bits).
        tmp.delete(); tmp.createNewFile()
        tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true)
        DataOutputStream(tmp.outputStream()).use { out ->
            val priv = private.encoded
            out.writeShort(priv.size); out.write(priv)
            out.writeShort(publicDer.size); out.write(publicDer)
        }
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "cannot write $file" } }
    }

    companion object {
        fun generate(): Identity {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), Crypto.random) }.generateKeyPair()
            return Identity(kp.private, kp.public.encoded)
        }

        fun load(file: File): Identity = DataInputStream(file.inputStream()).use { inp ->
            val priv = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
            val pub = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
            Identity(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(priv)), pub)
        }

        /** Loads dataDir/identity.bin, or creates and saves a new identity. */
        fun loadOrCreate(dataDir: File): Identity {
            val f = File(dataDir, "identity.bin")
            if (f.exists()) return load(f)
            dataDir.mkdirs()
            return generate().also { it.save(f) }
        }
    }
}
