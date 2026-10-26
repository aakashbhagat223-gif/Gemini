package com.glassremote.data.repository

import android.view.KeyEvent
import com.glassremote.data.network.PairingClient
import com.glassremote.data.network.PairingSession
import com.glassremote.data.network.RemoteSession
import com.glassremote.data.network.TvDiscovery
import com.glassremote.data.security.CertificateManager
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import com.glassremote.proto.remote.RemoteDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvRepositoryImpl @Inject constructor(
    private val discovery: TvDiscovery,
    private val certs: CertificateManager,
    private val store: PairedDeviceStore,
) : TvRepository {

    private val _status = MutableStateFlow(RemoteStatus())
    override val status: StateFlow<RemoteStatus> = _status.asStateFlow()

    @Volatile private var pairing: PairingSession? = null
    @Volatile private var remote: RemoteSession? = null

    override fun discover(): Flow<List<TvDevice>> = discovery.discover()

    // ---------- pairing ----------

    override suspend fun beginPairing(device: TvDevice) {
        pairing?.close()
        pairing = PairingClient(certs).begin(device.host)
    }

    override suspend fun submitPairingCode(device: TvDevice, code: String) {
        val session = pairing ?: throw IllegalStateException("Pairing not started")
        session.submitCode(code)
        session.close()
        pairing = null
        store.save(device)
    }

    // ---------- control channel ----------

    override suspend fun connect(device: TvDevice) = withContext(Dispatchers.IO) {
        disconnect()
        _status.value = RemoteStatus(LinkPhase.Connecting)

        lateinit var session: RemoteSession
        session = RemoteSession(certs, device.host) { newStatus ->
            // Ignore late callbacks from a session that has been replaced.
            if (remote === session) _status.value = newStatus
        }
        remote = session
        try {
            session.connect()
        } catch (t: Throwable) {
            remote = null
            session.close()
            _status.value = RemoteStatus(LinkPhase.Disconnected, error = t.message ?: "Connection failed")
            throw t
        }
    }

    override suspend fun sendKey(keyCode: Int, direction: KeyDirection) {
        val session = remote ?: return
        val dir = when (direction) {
            KeyDirection.Short -> RemoteDirection.DIRECTION_SHORT
            KeyDirection.StartLong -> RemoteDirection.DIRECTION_START_LONG
            KeyDirection.EndLong -> RemoteDirection.DIRECTION_END_LONG
        }
        withContext(Dispatchers.IO) {
            try {
                session.sendKey(keyCode, dir)
            } catch (_: IOException) {
                session.close() // reader thread/close() publishes Disconnected
            }
        }
    }

    /** Convenience overload using readable names, e.g. sendKey("UP"), sendKey("VOL_UP"). */
    suspend fun sendKey(keyName: String, direction: KeyDirection = KeyDirection.Short) {
        val code = KEY_NAMES[keyName.trim().uppercase()] ?: return
        sendKey(code, direction)
    }

    /**
     * Accepts a full link ("https://www.youtube.com", "market://launch?id=...")
     * or a bare package name, which is converted to a market:// launch link.
     */
    override suspend fun launchApp(deepLink: String) {
        val session = remote ?: return
        val link = if (deepLink.contains("://")) deepLink else "market://launch?id=$deepLink"
        withContext(Dispatchers.IO) {
            try {
                session.launchApp(link)
            } catch (_: IOException) {
                session.close()
            }
        }
    }

    override fun disconnect() {
        val old = remote
        remote = null
        old?.close()
    }

    override suspend fun resetPairing() {
        disconnect()
        pairing?.close()
        pairing = null
        certs.resetIdentity()
        store.clear()
        _status.value = RemoteStatus()
    }

    /** Alias for callers that expect close(). */
    fun close() = disconnect()

    private companion object {
        val KEY_NAMES: Map<String, Int> = mapOf(
            "UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "OK" to KeyEvent.KEYCODE_DPAD_CENTER,
            "SELECT" to KeyEvent.KEYCODE_DPAD_CENTER,
            "BACK" to KeyEvent.KEYCODE_BACK,
            "HOME" to KeyEvent.KEYCODE_HOME,
            "POWER" to KeyEvent.KEYCODE_POWER,
            "VOL_UP" to KeyEvent.KEYCODE_VOLUME_UP,
            "VOL_DOWN" to KeyEvent.KEYCODE_VOLUME_DOWN,
            "MUTE" to KeyEvent.KEYCODE_VOLUME_MUTE,
            "CH_UP" to KeyEvent.KEYCODE_CHANNEL_UP,
            "CH_DOWN" to KeyEvent.KEYCODE_CHANNEL_DOWN,
            "ASSIST" to KeyEvent.KEYCODE_ASSIST,
            "SETTINGS" to KeyEvent.KEYCODE_SETTINGS,
        )
    }
}
