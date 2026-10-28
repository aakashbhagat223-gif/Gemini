package com.glassremote.data.network

import android.content.Context
import com.glassremote.data.security.CertificateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class ProbeResult(
    val host: String,
    val openPorts: List<Int> = emptyList(),
    val castName: String? = null,
    val castModel: String? = null,
    val castMaker: String? = null,
    val pingMs: Int? = null,
    val message: String = "Not probed yet",
)

enum class PairPhase { Idle, Connecting, AwaitingCode, Verifying, Paired, Failed }

data class PairState(val phase: PairPhase = PairPhase.Idle, val message: String = "")

/**
 * Finds out what a box on the LAN offers (open ports, Cast name/model) and tries the Android TV
 * remote pairing handshake (port 6467). If the box shows a code, the protocol is supported.
 */
@Singleton
class StbProbe @Inject constructor(
    private val certs: CertificateManager,
    @ApplicationContext ctx: Context,
) {
    private val prefs = ctx.getSharedPreferences("stb", Context.MODE_PRIVATE)

    private val _result = MutableStateFlow(
        ProbeResult(host = prefs.getString("host", DEFAULT_HOST) ?: DEFAULT_HOST)
    )
    val result: StateFlow<ProbeResult> = _result.asStateFlow()

    private val _pair = MutableStateFlow(
        PairState(if (prefs.getBoolean("paired", false)) PairPhase.Paired else PairPhase.Idle)
    )
    val pair: StateFlow<PairState> = _pair.asStateFlow()

    @Volatile
    private var session: PairingSession? = null

    suspend fun probe(host: String): ProbeResult = withContext(Dispatchers.IO) {
        prefs.edit().putString("host", host).apply()
        _result.value = ProbeResult(host, message = "Probing…")

        val open = coroutineScope {
            PORTS.map { p -> async { if (connects(host, p, 700)) p else null } }.awaitAll().filterNotNull()
        }
        var ping: Int? = null
        val pingPort = open.firstOrNull { it in PING_PORTS }
        if (pingPort != null) {
            val t0 = System.nanoTime()
            if (connects(host, pingPort, 1_500)) ping = ((System.nanoTime() - t0) / 1_000_000).toInt()
        }
        val info = if (8008 in open) castInfo(host) else null
        val res = ProbeResult(
            host = host,
            openPorts = open.sorted(),
            castName = info?.name,
            castModel = info?.model,
            castMaker = info?.maker,
            pingMs = ping,
            message = if (open.isEmpty()) "Nothing answered. Is the box on and on this Wi-Fi?" else "Done",
        )
        _result.value = res
        res
    }

    suspend fun beginPairing(host: String) {
        runCatching { session?.close() }
        session = null
        _pair.value = PairState(PairPhase.Connecting, "Contacting $host:6467…")
        try {
            session = PairingClient(certs).begin(host)
            _pair.value = PairState(PairPhase.AwaitingCode, "Enter the code shown on the box's screen")
        } catch (t: Throwable) {
            _pair.value = PairState(PairPhase.Failed, friendly(t))
        }
    }

    suspend fun submitCode(code: String) {
        val s = session ?: return
        _pair.value = PairState(PairPhase.Verifying, "Checking the code…")
        try {
            s.submitCode(code)
            s.close()
            session = null
            prefs.edit().putBoolean("paired", true).apply()
            _pair.value = PairState(PairPhase.Paired, "Paired. The box accepts the Android TV remote protocol.")
        } catch (t: PairingException) {
            _pair.value = PairState(PairPhase.AwaitingCode, t.message ?: "That code didn't match")
        } catch (t: Throwable) {
            _pair.value = PairState(PairPhase.Failed, friendly(t))
        }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is ConnectException ->
            "Connection refused on port 6467: this box doesn't offer the Android TV remote protocol"
        is SocketTimeoutException -> "Timed out. The box didn't answer on port 6467"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun connects(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (_: Exception) {
        false
    }

    private data class CastInfo(val name: String?, val model: String?, val maker: String?)

    private fun castInfo(host: String): CastInfo? = try {
        val c = URL("http://$host:8008/setup/eureka_info?params=name,device_info")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 1_500
        c.readTimeout = 1_500
        try {
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val d = j.optJSONObject("device_info")
            CastInfo(
                j.optString("name").ifBlank { null },
                d?.optString("model_name")?.ifBlank { null },
                d?.optString("manufacturer")?.ifBlank { null },
            )
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val DEFAULT_HOST = "192.168.31.24"
        val PORTS = listOf(6466, 6467, 5555, 8009, 8008, 8080, 8443, 9000, 7000, 7676, 8060, 554, 80, 443)
        val PING_PORTS = listOf(8009, 8008, 6466, 80, 8080)
    }
}
