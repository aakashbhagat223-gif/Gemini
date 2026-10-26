package com.glassremote.data.security

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Client identity kept in app-private storage (software RSA key + self-signed cert)
 * instead of AndroidKeyStore, which fails TLS signing on some devices.
 */
@Singleton
class CertificateManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val keyFile get() = File(ctx.filesDir, "gr_client_key.p8")
    private val certFile get() = File(ctx.filesDir, "gr_client_cert.der")

    @Volatile private var cachedKey: PrivateKey? = null
    @Volatile private var cachedCert: X509Certificate? = null

    @Synchronized
    private fun identity(): Pair<PrivateKey, X509Certificate> {
        val k = cachedKey
        val c = cachedCert
        if (k != null && c != null) return k to c

        if (keyFile.exists() && certFile.exists()) {
            try {
                val key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                cachedKey = key; cachedCert = cert
                return key to cert
            } catch (_: Exception) {
                keyFile.delete(); certFile.delete()
            }
        }

        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = buildSelfSigned(pair.private, pair.public.encoded)
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        cachedKey = pair.private; cachedCert = cert
        return pair.private to cert
    }

    fun ensureIdentity() { identity() }

    fun clientCertificate(): X509Certificate = identity().second

    @Synchronized
    fun resetIdentity() {
        cachedKey = null; cachedCert = null
        keyFile.delete(); certFile.delete()
    }

    /** Opens + handshakes a mutual-TLS socket. Blocking: call from a background thread. */
    fun openSocket(host: String, port: Int, connectTimeoutMs: Int = 8_000, soTimeoutMs: Int = 15_000): SSLSocket {
        val (key, cert) = identity()
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("client", key, PASS, arrayOf<Certificate>(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, PASS) }
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

    // ---- minimal DER encoder for a self-signed X.509 v3 certificate ----

    private fun buildSelfSigned(privateKey: PrivateKey, publicKeyInfo: ByteArray): X509Certificate {
        val sigAlg = der(0x30, byteArrayOf(
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
            0x05, 0x00,
        ))
        val name = der(0x30, der(0x31, der(0x30,
            byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03) + der(0x0C, "GlassRemote".toByteArray()))))
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val validity = der(0x30,
            der(0x17, fmt.format(Date(now - 86_400_000L)).toByteArray()) +
                der(0x17, fmt.format(Date(now + 20L * 365 * 86_400_000L)).toByteArray()))
        val serial = der(0x02, BigInteger.valueOf(now).toByteArray())

        val tbs = der(0x30,
            byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02) + // version v3
                serial + sigAlg + name + validity + name + publicKeyInfo)

        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey); update(tbs); sign()
        }
        val certDer = der(0x30, tbs + sigAlg + der(0x03, byteArrayOf(0x00) + sig))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun der(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val len = when {
            n < 128 -> byteArrayOf(n.toByte())
            n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + body
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAnyTv : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object { val PASS = "glassremote".toCharArray() }
}
