package com.glassremote.data.security

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

@Singleton
class CertificateManager @Inject constructor() {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensureIdentity() {
        if (keyStore.containsAlias(ALIAS)) return
        val validUntil = Date(System.currentTimeMillis() + 20L * 365 * 24 * 3600 * 1000)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(
                KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1,
                KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512,
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
            )
            .setCertificateSubject(X500Principal("CN=GlassRemote"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(Date())
            .setCertificateNotAfter(validUntil)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    fun clientCertificate(): X509Certificate {
        ensureIdentity()
        return keyStore.getCertificate(ALIAS) as X509Certificate
    }

    fun resetIdentity() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    fun openSocket(host: String, port: Int, connectTimeoutMs: Int = 8_000, soTimeoutMs: Int = 15_000): SSLSocket {
        ensureIdentity()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, null) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(TrustAnyTv), null) }
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        socket.enabledProtocols = arrayOf("TLSv1.2")
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = soTimeoutMs
            socket.startHandshake()
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
        return socket
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAnyTv : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object { const val ALIAS = "glassremote_client_v2" }
}
