package com.hione.update.ota

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Checks manifest.json against manifest.json.sig.
 *
 * Format (hioneos/ota/README.md): the .sig file is the base64 text of the DER-encoded ECDSA
 * signature that `openssl dgst -sha512 -sign <P-521 key>` writes, over the exact bytes of
 * manifest.json as served. Whitespace in the .sig file is ignored. The signature must verify
 * under one of the embedded public keys (primary, or backup during a rotation).
 */
class SignatureVerifier(private val keys: List<Pair<String, PublicKey>>) {

    /** Returns the name of the key that signed [data] ("primary" or "backup"), or null. */
    fun verify(data: ByteArray, sigText: ByteArray): String? {
        val der = try {
            Base64.decode(String(sigText, Charsets.US_ASCII).filterNot { it.isWhitespace() }, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (der.isEmpty()) return null
        for ((name, key) in keys) {
            val ok = try {
                Signature.getInstance("SHA512withECDSA").run {
                    initVerify(key)
                    update(data)
                    verify(der)
                }
            } catch (_: Exception) {
                false
            }
            if (ok) return name
        }
        return null
    }

    fun keyInfo(): List<Pair<String, String>> = keys.map { (name, key) -> name to fingerprint(key) }

    companion object {
        fun load(ctx: Context): SignatureVerifier {
            val kf = KeyFactory.getInstance("EC")
            val keys = OtaConfig.KEY_ASSETS.map { (name, asset) ->
                val pem = ctx.assets.open(asset).use { it.readBytes().toString(Charsets.US_ASCII) }
                val b64 = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()
                val key = kf.generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT)))
                val bits = (key as? ECPublicKey)?.params?.order?.bitLength() ?: 0
                check(bits == 521) { "OTA key $name is not a P-521 key" }
                name to key
            }
            return SignatureVerifier(keys)
        }

        /** SHA-256 of the SubjectPublicKeyInfo, first 16 hex chars. Shown in the About card. */
        fun fingerprint(key: PublicKey): String =
            MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it) }.take(16)
    }
}
