package com.glassremote.data.network

import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/** Finds the TV again by its network name when its IP address changes. */
@Singleton
class TvAddressResolver @Inject constructor(
    private val discovery: TvDiscovery,
    private val store: PairedDeviceStore,
) {
    suspend fun reachable(host: String, port: Int, timeoutMs: Int = 2_000): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                true
            } catch (_: Exception) {
                false
            }
        }

    /** Looks the TV up on mDNS by name; if it moved, saves and returns the new address. */
    suspend fun refresh(device: TvDevice, timeoutMs: Long = 4_000): TvDevice {
        val found = withTimeoutOrNull(timeoutMs) {
            discovery.discover().first { list -> list.any { it.id == device.id } }
        }?.firstOrNull { it.id == device.id } ?: return device
        if (found.host != device.host) {
            val updated = device.copy(host = found.host, port = found.port)
            store.save(updated)
            return updated
        }
        return device
    }
}
