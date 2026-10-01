package com.signalzero.security.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.*
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.signature.SignatureConfig
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * End-to-end crypto built ONLY on Google Tink (no home-made primitives):
 *  - Encryption: ECIES (P-256 ECDH + HKDF + AES-128-GCM). Context info binds ciphertext to packet/sender/receiver.
 *  - Signatures: Ed25519.
 *  - Private keysets are stored encrypted by an Android Keystore master key.
 */
class CryptoManager(private val ctx: Context) {
    init { AeadConfig.register(); HybridConfig.register(); SignatureConfig.register() }

    private val master = "android-keystore://signalzero_master"
    private val prefsFile = "sz_keysets"

    private fun keyset(name: String, template: String): KeysetHandle =
        AndroidKeysetManager.Builder().withSharedPref(ctx, name, prefsFile)
            .withKeyTemplate(KeyTemplates.get(template)).withMasterKeyUri(master).build().keysetHandle

    private val encPrivate by lazy { keyset("enc", "ECIES_P256_HKDF_HMAC_SHA256_AES128_GCM") }
    private val sigPrivate by lazy { keyset("sig", "ED25519") }

    private fun publicJson(h: KeysetHandle): String {
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(h.publicKeysetHandle, JsonKeysetWriter.withOutputStream(out))
        return out.toString("UTF-8")
    }
    private fun readPublic(json: String): KeysetHandle = CleartextKeysetHandle.read(JsonKeysetReader.withString(json))

    val encryptionPublicJson: String get() = publicJson(encPrivate)
    val signingPublicJson: String get() = publicJson(sigPrivate)
    val fingerprint: String get() = sha256Hex((encryptionPublicJson + signingPublicJson).toByteArray()).take(16)

    private fun context(packetId: String, sender: String, receiver: String) = "$packetId|$sender|$receiver".toByteArray()

    fun encrypt(plain: String, receiverEncPublicJson: String, packetId: String, sender: String, receiver: String): String {
        val enc = readPublic(receiverEncPublicJson).getPrimitive(HybridEncrypt::class.java)
        return b64(enc.encrypt(plain.toByteArray(Charsets.UTF_8), context(packetId, sender, receiver)))
    }

    /** Throws GeneralSecurityException if the payload was tampered with or is not addressed to us. */
    fun decrypt(payloadB64: String, packetId: String, sender: String, receiver: String): String {
        val dec = encPrivate.getPrimitive(HybridDecrypt::class.java)
        return String(dec.decrypt(unb64(payloadB64), context(packetId, sender, receiver)), Charsets.UTF_8)
    }

    fun sign(data: String): String = b64(sigPrivate.getPrimitive(PublicKeySign::class.java).sign(data.toByteArray()))

    fun verify(data: String, signatureB64: String, senderSigPublicJson: String): Boolean = try {
        readPublic(senderSigPublicJson).getPrimitive(PublicKeyVerify::class.java).verify(unb64(signatureB64), data.toByteArray()); true
    } catch (e: Exception) { false }

    companion object {
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
