package mesh.core

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Sections 5.1, 5.4, 5.5. P-256 only: ECDSA/ECDH/AES-GCM are all in java.security / javax.crypto on Android API 29.
object Crypto {
    val random = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    fun nodeId(pubDer: ByteArray): ByteArray = sha256(pubDer).copyOf(8)

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** "abcd ef01 2345 6789" */
    fun fingerprint(id: ByteArray): String = hex(id).chunked(4).joinToString(" ")

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun publicKey(der: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))

    fun sign(priv: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() }

    /** False for any bad key, bad DER or wrong signature; never throws. */
    fun verify(pubDer: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey(pubDer)); update(data); verify(sig) }
    } catch (e: Exception) { false }

    fun ecdh(priv: PrivateKey, peerPubDer: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(publicKey(peerPubDer), true); generateSecret() }

    fun dmKey(priv: PrivateKey, peerPubDer: ByteArray): ByteArray =
        sha256(ecdh(priv, peerPubDer), "offlinemesh-dm-v1".toByteArray())

    /** Returns IV || ciphertext || tag. */
    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray, iv: ByteArray = randomBytes(12)): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    /** Null if the body is too short, or key, IV, AAD or data do not authenticate. */
    fun open(key: ByteArray, aad: ByteArray, body: ByteArray): ByteArray? = try {
        if (body.size < 12 + 16) null else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, body, 0, 12))
            updateAAD(aad)
            doFinal(body, 12, body.size - 12)
        }
    } catch (e: Exception) { null }

    private fun helloData(peerNonce: ByteArray, signerId: ByteArray, otherId: ByteArray) =
        "offlinemesh-hello-v1".toByteArray() + peerNonce + signerId + otherId

    /** 5.5 step 2: sig over "offlinemesh-hello-v1" || peerNonce || myNodeId || peerNodeId. */
    fun helloSign(me: Identity, peerNonce: ByteArray, peerId: ByteArray): ByteArray =
        sign(me.private, helloData(peerNonce, me.nodeId, peerId))

    /** Checks the peer's HELLO_AUTH: it signed our nonce, its id, then our id. */
    fun helloVerify(peerPubDer: ByteArray, myNonce: ByteArray, myId: ByteArray, sig: ByteArray): Boolean =
        verify(peerPubDer, helloData(myNonce, nodeId(peerPubDer), myId), sig)
}
