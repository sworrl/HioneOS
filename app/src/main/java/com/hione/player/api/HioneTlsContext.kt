package com.hione.player.api

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * Creates and manages hardware-backed TLS SSLContext using AndroidKeyStore.
 */
object HioneTlsContext {
    private const val TAG = "HioneTlsContext"
    private const val KEYSTORE_TYPE = "AndroidKeyStore"
    private const val ALIAS = "hione_remote_tls_key"

    @Volatile
    private var cachedSslContext: SSLContext? = null

    @Synchronized
    fun getOrCreateSslContext(context: Context): SSLContext {
        cachedSslContext?.let { return it }

        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
            keyStore.load(null)

            if (!keyStore.containsAlias(ALIAS)) {
                val kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    KEYSTORE_TYPE
                )
                val spec = KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=HioneM500, O=HioneMusic, C=US"))
                    .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86400000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86400000L))
                    .build()

                kpg.initialize(spec)
                kpg.generateKeyPair()
                Log.i(TAG, "Generated hardware-backed self-signed TLS certificate in AndroidKeyStore")
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, null)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, SecureRandom())

            cachedSslContext = sslContext
            return sslContext
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize AndroidKeyStore TLS, generating fallback TLS context", e)
            val fallback = SSLContext.getInstance("TLS")
            fallback.init(null, null, SecureRandom())
            cachedSslContext = fallback
            return fallback
        }
    }
}
