package com.glassremote.ui.remote

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.macro.MacroIds
import com.glassremote.data.macro.MacroRecorder
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

    private val _replaceMode = MutableStateFlow(true)
    val replaceMode: StateFlow<Boolean> = _replaceMode.asStateFlow()

    private var typed = ""

    // Typing over ADB goes through one ordered queue so letters, backspace and Go keep their order.
    private val adbOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val adbQueued = AtomicInteger(0)

    init {
        reconnect()
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

    fun key(code: Int) {
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
                    val input = nativeActions.savedHdmi2()
                    if (input.isNotBlank()) {
                        nativeActions.switchInput(input)
                        return@launch
                    }
                }
                runner.run(macros.get(id))
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
                    else -> runner.run(macro)
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
            // The TV's remote link drops letter keys; ADB's `input text` doesn't.
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
