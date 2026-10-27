package com.glassremote.data.adb

import android.content.Context
import android.net.Uri
import com.glassremote.data.macro.MacroStore
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
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

/** ADB over TCP (port 5555). The TV asks you to accept this phone's key the first time. */
@Singleton
class AdbManager @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val _state = MutableStateFlow(AdbState())
    val state: StateFlow<AdbState> = _state.asStateFlow()

    private val lock = Mutex()

    @Volatile
    private var dadb: Dadb? = null

    private fun keyPair(): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                dadb?.close()
                dadb = null
                _state.value = AdbState(false, host, "Connecting… accept the prompt on the TV")
                val d = Dadb.create(host, port, keyPair())
                d.shell("echo ok")
                dadb = d
                _state.value = AdbState(true, host, "Connected to $host:$port")
            }.onFailure {
                _state.value = AdbState(false, host, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val d = dadb ?: error("ADB not connected")
            try {
                val r = d.shell(command)
                r.output + r.errorOutput
            } catch (t: Throwable) {
                dadb = null
                _state.value = AdbState(false, _state.value.host, t.message ?: "ADB error")
                throw t
            }
        }
    }

    fun disconnect() {
        runCatching { dadb?.close() }
        dadb = null
        _state.value = AdbState()
    }
}

data class TvInput(val id: String, val type: Int)

/** One-click native actions that go through ADB instead of key macros. */
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

    /** HDMI inputs known to the TV framework (ids look like com.vendor.tvinput/.hdmi.Service/HW5). */
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

    /** Lists TV settings whose names mention backlight / brightness / picture. */
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

    companion object { const val KEY_HDMI2 = "adb_hdmi2_input" }
}

data class NowPlaying(val pkg: String, val title: String, val state: Int)

private val STATE_RE = Regex("""state=PlaybackState \{state=(?:\w+\()?(\d+)""")

/** Picks the playing (state 3) session, else a paused/buffering one, from `dumpsys media_session`. */
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
    /** Polls every 3 s, only while something (the Dashboard) is collecting. */
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
