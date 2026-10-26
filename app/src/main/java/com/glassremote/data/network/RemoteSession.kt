package com.glassremote.data.network

import com.glassremote.data.security.CertificateManager
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.proto.remote.RemoteAppLinkLaunchRequest
import com.glassremote.proto.remote.RemoteConfigure
import com.glassremote.proto.remote.RemoteDeviceInfo
import com.glassremote.proto.remote.RemoteDirection
import com.glassremote.proto.remote.RemoteKeyInject
import com.glassremote.proto.remote.RemoteMessage
import com.glassremote.proto.remote.RemotePingResponse
import com.glassremote.proto.remote.RemoteSetActive
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * Control channel (TCP 6466). Thread-based, no coroutines.
 *
 * - connect() and every send method BLOCK: call them off the main thread.
 * - Status changes are reported through [onStatus] from the reader thread.
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

    /** Opens the TLS socket and starts the reader thread. Throws IOException on failure. */
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
                                .setModel("GlassRemote").setVendor("GlassRemote")
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

            m.hasRemoteError() -> publish { it.copy(error = "TV reported an error") }
        }
    }

    /** Blocking. Throws IOException if the link is down. */
    fun sendKey(keyCode: Int, direction: RemoteDirection = RemoteDirection.DIRECTION_SHORT) {
        send(
            RemoteMessage.newBuilder().setRemoteKeyInject(
                RemoteKeyInject.newBuilder().setKeyCode(keyCode).setDirection(direction)
            ).build()
        )
    }

    /** Blocking. [appLink] is a URI such as "market://launch?id=com.netflix.ninja". */
    fun launchApp(appLink: String) {
        send(
            RemoteMessage.newBuilder().setRemoteAppLinkLaunchRequest(
                RemoteAppLinkLaunchRequest.newBuilder().setAppLink(appLink)
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

    /** Safe to call any number of times, from any thread. */
    override fun close() {
        val wasRunning = isRunning.getAndSet(false)
        closeSocketQuietly()
        readerThread?.interrupt()
        if (wasRunning) publish { it.copy(phase = LinkPhase.Disconnected) }
    }

    private companion object {
        const val REMOTE_PORT = 6466
        const val READ_TIMEOUT_MS = 20_000 // TV pings regularly; silence means the link is dead
        const val FEATURES = 622
    }
}
