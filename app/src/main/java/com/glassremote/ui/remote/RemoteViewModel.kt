package com.glassremote.ui.remote

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.AdbState
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.macro.CecKeys
import com.glassremote.data.macro.MacroIds
import com.glassremote.data.macro.MacroRecorder
import com.glassremote.data.macro.MacroRunState
import com.glassremote.data.macro.MacroRunner
import com.glassremote.data.macro.MacroStore
import com.glassremote.data.network.ImeBridge
import com.glassremote.data.network.ImeInfo
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/** Which device the remote layout is currently driving. */
enum class ControlTarget { XIAOMI_TV, JIO_BOX }

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val repo: TvRepository,
    private val store: PairedDeviceStore,
    private val macros: MacroStore,
    private val runner: MacroRunner,
    private val recorder: MacroRecorder,
    private val adb: AdbManager,
    private val nativeActions: NativeActions,
) : ViewModel() {

    val status: StateFlow<RemoteStatus> = repo.status

    val saved: StateFlow<Boolean?> = store.lastDevice
        .map<TvDevice?, Boolean?> { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceName: StateFlow<String> = store.lastDevice
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val ime: StateFlow<ImeInfo> = ImeBridge.state
    val macroState: StateFlow<MacroRunState> = runner.state
    val adbState: StateFlow<AdbState> = adb.state

    private val _replaceMode = MutableStateFlow(true)
    val replaceMode: StateFlow<Boolean> = _replaceMode.asStateFlow()

    // ---- Control target (Xiaomi TV / Jio box) ----
    private val _target = MutableStateFlow(ControlTarget.XIAOMI_TV)
    val target: StateFlow<ControlTarget> = _target.asStateFlow()

    private val _jioNote = MutableStateFlow("")
    val jioNote: StateFlow<String> = _jioNote.asStateFlow()

    private val _cecTemplate = MutableStateFlow(NativeActions.DEFAULT_CEC_TEMPLATE)
    val cecTemplate: StateFlow<String> = _cecTemplate.asStateFlow()

    private val _cecOutput = MutableStateFlow("")
    val cecOutput: StateFlow<String> = _cecOutput.asStateFlow()

    private var typed = ""

    private val adbOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val adbQueued = AtomicInteger(0)

    init {
        reconnect()
        viewModelScope.launch { _cecTemplate.value = nativeActions.cecTemplate() }
        viewModelScope.launch { for (op in adbOps) runCatching { op() } }
        viewModelScope.launch {
            var retries = 0
            repo.status.collect { s ->
                when (s.phase) {
                    LinkPhase.Ready -> retries = 0
                    LinkPhase.Disconnected -> if (retries < 3) {
                        retries++
                        delay(1_500)
                        reconnect()
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            ImeBridge.state
                .map { it.active to it.label }
                .distinctUntilChanged()
                .collect { typed = ImeBridge.state.value.value }
        }
    }

    fun reconnect() {
        viewModelScope.launch {
            val device = store.lastDevice.first() ?: return@launch
            runCatching { repo.connect(device) }
        }
    }

    // ---- Target switching ----

    fun setTarget(t: ControlTarget) {
        if (_target.value == t) return
        _target.value = t
        if (t == ControlTarget.JIO_BOX) prepareJio()
    }

    fun cancelMacro() = runner.cancel()

    /** Puts the TV on the HDMI 2 input the Jio box is plugged into. */
    private fun prepareJio() {
        viewModelScope.launch {
            _jioNote.value = "Switching the TV to HDMI 2…"
            runCatching {
                if (adb.state.value.connected) {
                    nativeActions.switchInput(nativeActions.hdmi2OrDefault())
                    _jioNote.value = "TV is on HDMI 2"
                } else {
                    runner.run(macros.get(MacroIds.HDMI2), label = "HDMI 2")
                    _jioNote.value = "Ran the HDMI 2 macro (connect ADB for the instant switch)"
                }
            }.onFailure { _jioNote.value = "Couldn't switch input: ${it.message}" }
        }
    }

    /**
     * Jio box key. Goes only through the TV's CEC (ADB shell), never to the TV's own keys, so
     * nothing can disturb the TV. Volume and mute are the exception: they control the TV's audio.
     */
    fun jioKey(androidKey: Int) {
        if (androidKey == KeyEvent.KEYCODE_VOLUME_UP ||
            androidKey == KeyEvent.KEYCODE_VOLUME_DOWN ||
            androidKey == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            viewModelScope.launch { runCatching { repo.sendKey(androidKey, KeyDirection.Short) } }
            return
        }
        val cec = CecKeys.fromAndroidKey(androidKey)
        if (cec == null) {
            _jioNote.value = "That key has no CEC code"
            return
        }
        viewModelScope.launch {
            val r = nativeActions.sendJioCecKey(cec)
            _jioNote.value = if (r.ok) "" else "CEC failed: " + r.output.ifBlank { "no output" }.take(90)
        }
    }

    fun cecTest() {
        viewModelScope.launch {
            _cecOutput.value = "Sending SELECT (0x00)…"
            val r = nativeActions.sendJioCecKey(CecKeys.SELECT)
            _cecOutput.value = (if (r.ok) "OK: " else "FAILED: ") + r.output.ifBlank { "(no output)" }
        }
    }

    fun cecDiagnostics() {
        viewModelScope.launch {
            _cecOutput.value = "Running diagnostics…"
            _cecOutput.value = runCatching { nativeActions.cecDiagnostics() }
                .getOrElse { "Error: ${it.message}" }
        }
    }

    fun saveCecTemplate(template: String) {
        viewModelScope.launch {
            nativeActions.setCecTemplate(template)
            _cecTemplate.value = nativeActions.cecTemplate()
        }
    }

    fun resetCecTemplate() {
        viewModelScope.launch {
            nativeActions.resetCecTemplate()
            _cecTemplate.value = nativeActions.cecTemplate()
        }
    }

    // ---- TV keys ----

    fun key(code: Int) {
        if (_target.value == ControlTarget.JIO_BOX) {
            jioKey(code)
            return
        }
        recorder.onKey(code)
        viewModelScope.launch { runCatching { repo.sendKey(code, KeyDirection.Short) } }
    }

    fun launchApp(target: String) {
        viewModelScope.launch { runCatching { repo.launchApp(target) } }
    }

    fun runMacro(id: String) {
        viewModelScope.launch {
            runCatching {
                if (id == MacroIds.HDMI2 && adb.state.value.connected) {
                    nativeActions.switchInput(nativeActions.hdmi2OrDefault())
                    return@launch
                }
                runner.run(macros.get(id), label = MacroIds.title(id))
            }
        }
    }

    fun openStremio() {
        viewModelScope.launch {
            runCatching {
                if (adb.state.value.connected) {
                    nativeActions.launchPackage("com.stremio.one")
                    return@launch
                }
                val macro = macros.get(MacroIds.STREMIO).trim()
                when {
                    macro.isBlank() -> repo.launchApp("stremio://")
                    macro.contains("://") || macro.startsWith("intent:") -> repo.launchApp(macro)
                    else -> runner.run(macro, label = "Stremio")
                }
            }
        }
    }

    // ---- Keypad text input ----

    fun toggleReplaceMode() { _replaceMode.value = !_replaceMode.value }

    private fun viaAdb(block: suspend () -> Unit) {
        adbQueued.incrementAndGet()
        adbOps.trySend {
            try {
                block()
            } finally {
                adbQueued.decrementAndGet()
            }
        }
    }

    fun typeChar(ch: Char) {
        val c = ch.lowercaseChar()
        val info = ImeBridge.state.value
        val letter = c in 'a'..'z' || c == ' '
        when {
            info.active -> {
                if (_replaceMode.value) {
                    typed += c.toString()
                    sendText(typed)
                } else {
                    sendText(c.toString())
                }
            }
            letter && adb.state.value.connected -> viaAdb { nativeActions.typeText(c.toString()) }
            else -> keyForChar(c)?.let { key(it) }
        }
    }

    fun backspace() {
        when {
            ImeBridge.state.value.active && _replaceMode.value && typed.isNotEmpty() -> {
                typed = typed.dropLast(1)
                sendText(typed)
            }
            adbQueued.get() > 0 && adb.state.value.connected ->
                viaAdb { nativeActions.keyEvent(KeyEvent.KEYCODE_DEL) }
            else -> key(KeyEvent.KEYCODE_DEL)
        }
    }

    fun submitText() {
        typed = ""
        if (adbQueued.get() > 0 && adb.state.value.connected) {
            viaAdb { nativeActions.keyEvent(KeyEvent.KEYCODE_ENTER) }
        } else {
            key(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun keyForChar(c: Char): Int? = when (c) {
        ' ' -> KeyEvent.KEYCODE_SPACE
        in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
        in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
        else -> null
    }

    private fun sendText(text: String) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { ImeBridge.sendText(text) } }
    }

    fun forget() {
        viewModelScope.launch { repo.resetPairing() }
    }
}
