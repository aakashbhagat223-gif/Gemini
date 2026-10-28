package com.glassremote.data.adb

import android.content.Context
import android.net.Uri
import com.glassremote.data.macro.MacroStore
import com.glassremote.data.network.TvAddressResolver
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.di.AppScope
import dadb.AdbKeyPair
import dadb.Dadb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AdbState(
    val connected: Boolean = false,
    val host: String = "",
    val message: String = "Not connected",
)

data class ShellResult(val output: String, val exitCode: Int)

/**
 * ADB over TCP to the Xiaomi TV (port 5555). Once you connect, it stays wanted: failed commands retry
 * once, a keepalive runs every 15 s, and if the TV's IP changed it is looked up again by name.
 */
@Singleton
class AdbManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val store: PairedDeviceStore,
    private val resolver: TvAddressResolver,
    @AppScope private val scope: CoroutineScope,
) {
    private val prefs = ctx.getSharedPreferences("adb", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(AdbState())
    val state: StateFlow<AdbState> = _state.asStateFlow()

    private val lock = Mutex()

    @Volatile private var dadb: Dadb? = null
    @Volatile private var wanted = prefs.getBoolean("wanted", false)
    @Volatile private var lastHost = prefs.getString("host", "") ?: ""
    @Volatile private var lastPort = prefs.getInt("port", 5555)

    init {
        scope.launch {
            while (true) {
                if (wanted) runCatching { shell("echo 1") }
                delay(15_000)
            }
        }
    }

    private fun keyPair(): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Must be called with [lock] held. */
    private suspend fun openLocked(): Dadb {
        val saved = store.lastDevice.first()
        var host = lastHost.ifBlank { saved?.host ?: throw IllegalStateException("No TV address") }
        if (!resolver.reachable(host, lastPort)) {
            if (saved != null) {
                val fresh = resolver.refresh(saved)
                if (fresh.host != host) host = fresh.host
            }
            if (!resolver.reachable(host, lastPort)) {
                throw IllegalStateException("TV not reachable at $host:$lastPort")
            }
        }
        lastHost = host
        prefs.edit().putString("host", host).apply()
        val d = Dadb.create(host, lastPort, keyPair())
        d.shell("echo ok")
        dadb = d
        _state.value = AdbState(true, host, "Connected to $host:$lastPort")
        return d
    }

    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                runCatching { dadb?.close() }
                dadb = null
                lastHost = host
                lastPort = port
                wanted = true
                prefs.edit().putBoolean("wanted", true).putString("host", host).putInt("port", port).apply()
                _state.value = AdbState(false, host, "Connecting… accept the prompt on the TV and tick Always allow")
                openLocked()
                Unit
            }.onFailure {
                _state.value = AdbState(false, host, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /** Runs a shell command on the TV and returns its output and exit code. */
    suspend fun shellResult(command: String): ShellResult = withContext(Dispatchers.IO) {
        lock.withLock {
            if (dadb == null && !wanted) throw IllegalStateException("ADB not connected")
            var last: Throwable? = null
            repeat(2) {
                try {
                    val d = dadb ?: openLocked()
                    val r = d.shell(command)
                    return@withContext ShellResult(r.output + r.errorOutput, r.exitCode)
                } catch (t: Throwable) {
                    last = t
                    runCatching { dadb?.close() }
                    dadb = null
                    _state.value = AdbState(false, _state.value.host, "Reconnecting… (${t.message})")
                }
            }
            throw last ?: IllegalStateException("ADB error")
        }
    }

    suspend fun shell(command: String): String = shellResult(command).output

    fun disconnect() {
        wanted = false
        prefs.edit().putBoolean("wanted", false).apply()
        runCatching { dadb?.close() }
        dadb = null
        _state.value = AdbState()
    }
}

data class TvInput(val id: String, val type: Int)

data class CecResult(val ok: Boolean, val output: String)

private val CEC_FAIL = Regex(
    "unknown command|no shell command|not found|exception|error|usage:|invalid|unsupported",
    RegexOption.IGNORE_CASE,
)

/** One-click native actions that go through the TV's ADB shell. */
@Singleton
class NativeActions @Inject constructor(
    private val adb: AdbManager,
    private val store: MacroStore,
) {
    val connected: Boolean get() = adb.state.value.connected

    suspend fun launchPackage(pkg: String): String {
        val out = adb.shell("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1")
        return if (out.contains("No activities found")) {
            adb.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        } else {
            out
        }
    }

    suspend fun hdmiInputs(): List<TvInput> {
        val dump = adb.shell("dumpsys tv_input")
        val idRe = Regex("""TvInputInfo\{id=([^,\s}]+)""")
        val typeRe = Regex("""type=(\d+)""")
        return dump.lines().mapNotNull { line ->
            val id = idRe.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val type = typeRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (type == 1007 || id.contains("HDMI", ignoreCase = true)) TvInput(id, type) else null
        }.distinctBy { it.id }
    }

    suspend fun switchInput(inputId: String): String =
        adb.shell(
            "am start -a android.intent.action.VIEW -d " +
                "\"content://android.media.tv/passthrough/${Uri.encode(inputId)}\""
        )

    suspend fun savedHdmi2(): String = store.get(KEY_HDMI2)
    suspend fun saveHdmi2(id: String) = store.set(KEY_HDMI2, id)

    /** The saved HDMI 2 input, or the MediaTek input id from your scan if none is saved. */
    suspend fun hdmi2OrDefault(): String = savedHdmi2().ifBlank { DEFAULT_HDMI2 }

    suspend fun backlightSettings(): List<String> {
        val re = Regex("backlight|brightness|picture", RegexOption.IGNORE_CASE)
        val rows = mutableListOf<String>()
        for (ns in listOf("system", "global", "secure")) {
            adb.shell("settings list $ns").lines()
                .filter { re.containsMatchIn(it) }
                .forEach { rows.add("$ns: $it") }
        }
        return rows
    }

    // ---- typing (letters and space only) ----
    suspend fun typeText(text: String): String = adb.shell("input text ${text.replace(" ", "%s")}")
    suspend fun keyEvent(code: Int): String = adb.shell("input keyevent $code")

    // ---- backlight ----
    suspend fun readBacklight(): Int? = adb.shell("settings get global $BACKLIGHT_KEY").trim().toIntOrNull()

    suspend fun writeBacklight(value: Int) {
        adb.shell("settings put global $BACKLIGHT_KEY ${value.coerceIn(0, 100)}")
    }

    suspend fun nativeBacklightEnabled(): Boolean = store.get(KEY_BACKLIGHT_NATIVE) != "0"
    suspend fun setNativeBacklight(on: Boolean) = store.set(KEY_BACKLIGHT_NATIVE, if (on) "1" else "0")

    // ---- Jio box: HDMI-CEC keys sent by the TV, injected through the TV's ADB shell ----

    suspend fun cecTemplate(): String = store.get(KEY_CEC_TEMPLATE).ifBlank { DEFAULT_CEC_TEMPLATE }
    suspend fun setCecTemplate(template: String) = store.set(KEY_CEC_TEMPLATE, template)
    suspend fun resetCecTemplate() = store.reset(KEY_CEC_TEMPLATE)

    /**
     * Sends one CEC user-control key from the TV to the device at [logicalAddress]
     * (4 = playback device 1, normally the first box on HDMI). The shell command comes from the
     * template: {hex} = 0x41-style, {dec} = decimal, {la} = logical address.
     * Never falls back to the TV's own keys; a failure is returned, not hidden.
     */
    suspend fun sendJioCecKey(cecKeyCode: Int, logicalAddress: Int = 4): CecResult {
        if (!connected) return CecResult(false, "ADB isn't connected")
        val command = cecTemplate()
            .replace("{hex}", "0x%02X".format(cecKeyCode))
            .replace("{dec}", cecKeyCode.toString())
            .replace("{la}", logicalAddress.toString())
        return try {
            val r = adb.shellResult(command)
            val failed = r.exitCode != 0 || CEC_FAIL.containsMatchIn(r.output)
            CecResult(!failed, r.output.trim())
        } catch (t: Throwable) {
            CecResult(false, t.message ?: "ADB error")
        }
    }

    /** What this TV's CEC layer supports and which devices it can see on HDMI. */
    suspend fun cecDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("hdmi_control_enabled = " + adb.shell("settings get global hdmi_control_enabled").trim())
        sb.appendLine()
        sb.appendLine("--- cmd hdmi_control help ---")
        sb.appendLine(adb.shell("cmd hdmi_control help").lines().take(30).joinToString("\n"))
        sb.appendLine()
        sb.appendLine("--- dumpsys hdmi_control (filtered) ---")
        val keep = Regex(
            "logical|physical|vendor|power status|cec version|devicetype|active source|port",
            RegexOption.IGNORE_CASE,
        )
        sb.append(
            adb.shell("dumpsys hdmi_control").lines()
                .filter { keep.containsMatchIn(it) }
                .take(40)
                .joinToString("\n") { it.trim() }
        )
        return sb.toString()
    }

    companion object {
        const val KEY_HDMI2 = "adb_hdmi2_input"
        const val KEY_BACKLIGHT_NATIVE = "adb_backlight_native"
        const val KEY_CEC_TEMPLATE = "adb_cec_template"
        const val BACKLIGHT_KEY = "picture_backlight"
        const val DEFAULT_HDMI2 = "com.mediatek.tis/.HdmiInputService/HW3"
        const val DEFAULT_CEC_TEMPLATE = "cmd hdmi_control cec_key -t {la} -a {hex}"
    }
}

data class NowPlaying(val pkg: String, val title: String, val state: Int)

private val STATE_RE = Regex("""state=PlaybackState \{state=(?:\w+\()?(\d+)""")

fun parseMediaSessions(dump: String): NowPlaying? {
    val sessions = mutableListOf<NowPlaying>()
    var pkg: String? = null
    var state = -1
    var title = ""

    fun flush() {
        val p = pkg
        if (p != null && state >= 0) sessions.add(NowPlaying(p, title, state))
    }

    for (line in dump.lines()) {
        val t = line.trim()
        if (t.startsWith("package=")) {
            flush()
            pkg = t.removePrefix("package=").trim()
            state = -1
            title = ""
        } else if (pkg != null) {
            STATE_RE.find(t)?.let { if (state < 0) state = it.groupValues[1].toInt() }
            if (t.startsWith("metadata:")) {
                val d = t.substringAfter("description=", "").replace(", null", "").trim()
                title = if (d == "null") "" else d
            }
        }
    }
    flush()
    return sessions.firstOrNull { it.state == 3 } ?: sessions.firstOrNull { it.state == 2 || it.state == 6 }
}

@Singleton
class NowPlayingRepository @Inject constructor(
    private val adb: AdbManager,
    @AppScope scope: CoroutineScope,
) {
    val state: StateFlow<NowPlaying?> = flow {
        while (true) {
            val np = if (adb.state.value.connected) {
                runCatching { parseMediaSessions(adb.shell("dumpsys media_session")) }.getOrNull()
            } else {
                null
            }
            emit(np)
            delay(3_000)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
}
