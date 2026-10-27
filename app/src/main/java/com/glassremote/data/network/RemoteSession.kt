package com.glassremote.data.network

import com.glassremote.data.security.CertificateManager
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.proto.remote.RemoteAppLinkLaunchRequest
import com.glassremote.proto.remote.RemoteConfigure
import com.glassremote.proto.remote.RemoteDeviceInfo
import com.glassremote.proto.remote.RemoteDirection
import com.glassremote.proto.remote.RemoteEditInfo
import com.glassremote.proto.remote.RemoteImeBatchEdit
import com.glassremote.proto.remote.RemoteImeObject
import com.glassremote.proto.remote.RemoteKeyInject
import com.glassremote.proto.remote.RemoteMessage
import com.glassremote.proto.remote.RemotePingResponse
import com.glassremote.proto.remote.RemoteSetActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/** What the TV last told us about the foreground app and any focused text field. */
data class ImeInfo(
    val active: Boolean = false,
    val label: String = "",
    val value: String = "",
    val imeCounter: Int = 0,
    val fieldCounter: Int = 0,
    val currentApp: String = "",
)

/** Small shared hub so the UI can read TV text-field state and send text without extra plumbing. */
object ImeBridge {
    private val _state = MutableStateFlow(ImeInfo())
    val state: StateFlow<ImeInfo> = _state.asStateFlow()

    @Volatile
    private var session: RemoteSession? = null

    internal fun attach(s: RemoteSession) { session = s }

    internal fun detach(s: RemoteSession) {
        if (session === s) {
            session = null
            _state.value = ImeInfo()
        }
    }

    internal fun update(transform: (ImeInfo) -> ImeInfo) { _state.update(transform) }

    /** Blocking socket write: call from a background thread. */
    fun sendText(text: String) { session?.sendText(text) }
}

/**
 * Control channel (TCP 6466). Thread-based, no coroutines.
 * connect() and every send method BLOCK: call them off the main thread.
 */
class RemoteSession(
    private val certs: CertificateManager,
    private val host: String,
    private val onStatus: (RemoteStatus) -> Unit,
) : Closeable {

    val isRunning = AtomicBoolean(false)

    @Volatile private var socket: SSLSocket? = null
    private var readerThread: Thread? = null
    private val writeLock = Any()
    private val statusLock = Any()
    private var status = RemoteStatus(LinkPhase.Connecting)

    fun connect() {
        if (!isRunning.compareAndSet(false, true)) return
        val s: SSLSocket
        try {
            s = certs.openSocket(host, REMOTE_PORT, soTimeoutMs = READ_TIMEOUT_MS)
        } catch (t: Throwable) {
            isRunning.set(false)
            throw t
        }
        socket = s
        ImeBridge.attach(this)
        readerThread = Thread({ readLoop(s) }, "glassremote-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(s: SSLSocket) {
        var reason: String? = null
        try {
            val input = s.inputStream
            while (isRunning.get()) {
                handle(Framing.read(input, RemoteMessage::parseFrom))
            }
        } catch (t: Throwable) {
            if (isRunning.get()) reason = t.message ?: t.javaClass.simpleName
        } finally {
            val wasRunning = isRunning.getAndSet(false)
            closeSocketQuietly()
            ImeBridge.detach(this)
            if (wasRunning) publish { it.copy(phase = LinkPhase.Disconnected, error = reason) }
        }
    }

    private fun handle(m: RemoteMessage) {
        when {
            m.hasRemoteConfigure() -> send(
                RemoteMessage.newBuilder().setRemoteConfigure(
                    RemoteConfigure.newBuilder()
                        .setCode1(FEATURES)
                        .setDeviceInfo(
                            RemoteDeviceInfo.newBuilder()
                                .setModel("HomeRemote+").setVendor("HomeRemote")
                                .setUnknown1(1).setUnknown2("1")
                                .setPackageName("com.glassremote").setAppVersion("1.0.0")
                        )
                ).build()
            )

            m.hasRemoteSetActive() -> {
                send(
                    RemoteMessage.newBuilder()
                        .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(FEATURES))
                        .build()
                )
                publish { it.copy(phase = LinkPhase.Ready, error = null) }
            }

            m.hasRemotePingRequest() -> send(
                RemoteMessage.newBuilder().setRemotePingResponse(
                    RemotePingResponse.newBuilder().setVal1(m.remotePingRequest.val1)
                ).build()
            )

            m.hasRemoteStart() -> publish { it.copy(poweredOn = m.remoteStart.started) }

            m.hasRemoteSetVolumeLevel() -> {
                val v = m.remoteSetVolumeLevel
                publish { it.copy(volume = v.volumeLevel, volumeMax = v.volumeMax, muted = v.volumeMuted) }
            }

            m.hasRemoteImeKeyInject() -> {
                val inj = m.remoteImeKeyInject
                ImeBridge.update { cur ->
                    cur.copy(
                        currentApp = if (inj.hasAppInfo() && inj.appInfo.appPackage.isNotEmpty())
                            inj.appInfo.appPackage else cur.currentApp,
                        imeCounter = if (inj.hasAppInfo()) inj.appInfo.counter else cur.imeCounter,
                        active = inj.hasTextFieldStatus(),
                        fieldCounter = if (inj.hasTextFieldStatus()) inj.textFieldStatus.counterField else cur.fieldCounter,
                        label = if (inj.hasTextFieldStatus()) inj.textFieldStatus.label else "",
                        value = if (inj.hasTextFieldStatus()) inj.textFieldStatus.value else "",
                    )
                }
            }

            m.hasRemoteImeShowRequest() -> {
                val f = m.remoteImeShowRequest.remoteTextFieldStatus
                ImeBridge.update {
                    it.copy(active = true, fieldCounter = f.counterField, label = f.label, value = f.value)
                }
            }

            m.hasRemoteError() -> publish { it.copy(error = "TV reported an error") }
        }
    }

    fun sendKey(keyCode: Int, direction: RemoteDirection = RemoteDirection.DIRECTION_SHORT) {
        send(
            RemoteMessage.newBuilder().setRemoteKeyInject(
                RemoteKeyInject.newBuilder().setKeyCode(keyCode).setDirection(direction)
            ).build()
        )
    }

    fun launchApp(appLink: String) {
        send(
            RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink(appLink)
            ).build()
        )
    }

    /** Sends [text] to the TV's focused text field over the IME channel. */
    fun sendText(text: String) {
        val info = ImeBridge.state.value
        val cursor = (text.length - 1).coerceAtLeast(0)
        send(
            RemoteMessage.newBuilder().setRemoteImeBatchEdit(
                RemoteImeBatchEdit.newBuilder()
                    .setImeCounter(info.imeCounter)
                    .setFieldCounter(info.fieldCounter)
                    .addEditInfo(
                        RemoteEditInfo.newBuilder()
                            .setInsert(1)
                            .setTextFieldStatus(
                                RemoteImeObject.newBuilder().setStart(cursor).setEnd(cursor).setValue(text)
                            )
                    )
            ).build()
        )
    }

    private fun send(m: RemoteMessage) {
        val s = socket
        if (s == null || !isRunning.get()) throw IOException("Not connected")
        synchronized(writeLock) { Framing.write(s.outputStream, m) }
    }

    private fun publish(transform: (RemoteStatus) -> RemoteStatus) {
        val snapshot = synchronized(statusLock) {
            status = transform(status)
            status
        }
        onStatus(snapshot)
    }

    private fun closeSocketQuietly() {
        try { socket?.close() } catch (_: IOException) { }
    }

    override fun close() {
        val wasRunning = isRunning.getAndSet(false)
        closeSocketQuietly()
        readerThread?.interrupt()
        ImeBridge.detach(this)
        if (wasRunning) publish { it.copy(phase = LinkPhase.Disconnected) }
    }

    private companion object {
        const val REMOTE_PORT = 6466
        const val READ_TIMEOUT_MS = 20_000
        const val FEATURES = 622
    }
}
