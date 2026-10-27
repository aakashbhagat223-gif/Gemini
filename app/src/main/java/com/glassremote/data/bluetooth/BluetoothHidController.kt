package com.glassremote.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

data class BtState(
    val registered: Boolean = false,
    val connectedName: String? = null,
    val message: String = "Bluetooth backup is off",
)

/**
 * Registers the phone as a Bluetooth keyboard + media remote (HID). The state message always says
 * what the phone's Bluetooth stack answered, so a refusal is visible.
 */
@SuppressLint("MissingPermission")
@Singleton
class BluetoothHidController @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val _state = MutableStateFlow(BtState())
    val state: StateFlow<BtState> = _state.asStateFlow()

    private val _prefer = MutableStateFlow(false)
    val prefer: StateFlow<Boolean> = _prefer.asStateFlow()
    fun setPrefer(v: Boolean) { _prefer.value = v }

    private val prefs = ctx.getSharedPreferences("bt_backup", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var retried = false

    val isConnected: Boolean get() = host != null && hid != null

    private val adapter: BluetoothAdapter?
        get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private fun setMessage(msg: String) { _state.update { it.copy(message = msg) } }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            _state.update { it.copy(registered = false, connectedName = null, message = "Bluetooth service stopped") }
        }
    }

    fun start() {
        val a = adapter
        if (a == null) { setMessage("This phone has no Bluetooth"); return }
        if (!a.isEnabled) { setMessage("Turn Bluetooth on first"); return }
        if (!hasPermission()) { setMessage("Bluetooth permission needed"); return }
        if (hid != null) {
            if (!_state.value.registered) register()
            return
        }
        retried = false
        val ok = a.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)
        setMessage(
            if (ok) "Starting…"
            else "This phone doesn't offer the Bluetooth keyboard (HID device) role"
        )
    }

    fun stop() {
        runCatching { hid?.unregisterApp() }
        hid?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
        hid = null
        host = null
        _state.value = BtState()
    }

    fun restart() {
        stop()
        start()
    }

    private fun register() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "HomeRemote+", "TV remote", "HomeRemote",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR,
        )
        val accepted = hid?.registerApp(sdp, null, null, executor, callback) == true
        setMessage(if (accepted) "Registering…" else "Bluetooth refused the keyboard registration request")
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                _state.update {
                    it.copy(
                        registered = true,
                        message = "Registered. Pair the phone from the TV's accessory screen, then tap the TV below.",
                    )
                }
                val last = prefs.getString("last", null)
                val device = adapter?.bondedDevices?.firstOrNull { it.address == last }
                if (device != null) hid?.connect(device)
            } else {
                _state.update { it.copy(registered = false) }
                if (!retried) {
                    retried = true
                    setMessage("Registration failed, retrying…")
                    main.postDelayed({
                        runCatching { hid?.unregisterApp() }
                        register()
                    }, 1_500)
                } else {
                    setMessage(
                        "The phone's Bluetooth rejected the keyboard registration. Some phones disable " +
                            "this role, so Bluetooth backup may not be possible on this phone."
                    )
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> setMessage("Connecting to ${nameOf(device)}…")
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    prefs.edit().putString("last", device.address).apply()
                    _state.update { it.copy(connectedName = nameOf(device), message = "Connected to ${nameOf(device)}") }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address == device.address) host = null
                    _state.update { it.copy(connectedName = null, message = "Disconnected from ${nameOf(device)}") }
                }
            }
        }
    }

    private fun nameOf(d: BluetoothDevice): String = runCatching { d.name }.getOrNull() ?: d.address

    fun bondedInfo(): List<Pair<String, String>> {
        if (!hasPermission()) return emptyList()
        return adapter?.bondedDevices.orEmpty().map { nameOf(it) to it.address }
    }

    fun connectTo(address: String) {
        val h = hid
        if (h == null) { setMessage("Bluetooth service isn't ready. Tap Enable."); return }
        if (!_state.value.registered) {
            setMessage("Keyboard not registered yet. " + _state.value.message)
            return
        }
        val device = adapter?.bondedDevices?.firstOrNull { it.address == address }
        if (device == null) { setMessage("That device isn't paired with this phone"); return }
        setMessage(if (h.connect(device)) "Connecting to ${nameOf(device)}…" else "The connect request was rejected")
    }

    fun disconnectHost() {
        host?.let { hid?.disconnect(it) }
    }

    fun sendKey(code: Int): Boolean {
        val h = hid ?: return false
        val dev = host ?: return false
        val usage = usageFor(code) ?: return false
        return try {
            val ok: Boolean
            if (usage.consumer) {
                ok = h.sendReport(dev, 2, byteArrayOf((usage.id and 0xFF).toByte(), ((usage.id shr 8) and 0xFF).toByte()))
                Thread.sleep(25)
                h.sendReport(dev, 2, ByteArray(2))
            } else {
                val down = ByteArray(8)
                down[2] = usage.id.toByte()
                ok = h.sendReport(dev, 1, down)
                Thread.sleep(25)
                h.sendReport(dev, 1, ByteArray(8))
            }
            ok
        } catch (_: SecurityException) {
            false
        }
    }

    private data class Usage(val id: Int, val consumer: Boolean)

    private fun usageFor(code: Int): Usage? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Usage(0x52, false)
        KeyEvent.KEYCODE_DPAD_DOWN -> Usage(0x51, false)
        KeyEvent.KEYCODE_DPAD_LEFT -> Usage(0x50, false)
        KeyEvent.KEYCODE_DPAD_RIGHT -> Usage(0x4F, false)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Usage(0x28, false)
        KeyEvent.KEYCODE_DEL -> Usage(0x2A, false)
        KeyEvent.KEYCODE_SPACE -> Usage(0x2C, false)
        KeyEvent.KEYCODE_ESCAPE -> Usage(0x29, false)
        KeyEvent.KEYCODE_BACK -> Usage(0x0224, true)
        KeyEvent.KEYCODE_HOME -> Usage(0x0223, true)
        KeyEvent.KEYCODE_MENU -> Usage(0x0040, true)
        KeyEvent.KEYCODE_SEARCH -> Usage(0x0221, true)
        KeyEvent.KEYCODE_VOLUME_UP -> Usage(0x00E9, true)
        KeyEvent.KEYCODE_VOLUME_DOWN -> Usage(0x00EA, true)
        KeyEvent.KEYCODE_VOLUME_MUTE -> Usage(0x00E2, true)
        KeyEvent.KEYCODE_CHANNEL_UP -> Usage(0x009C, true)
        KeyEvent.KEYCODE_CHANNEL_DOWN -> Usage(0x009D, true)
        KeyEvent.KEYCODE_POWER -> Usage(0x0030, true)
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Usage(0x00CD, true)
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> Usage(0x04 + (code - KeyEvent.KEYCODE_A), false)
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> Usage(0x1E + (code - KeyEvent.KEYCODE_1), false)
        KeyEvent.KEYCODE_0 -> Usage(0x27, false)
        else -> null
    }

    private companion object {
        private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        val DESCRIPTOR: ByteArray = b(
            0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
            0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
            0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
            0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
            0xC0,
            0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
            0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
            0xC0,
        )
    }
}
