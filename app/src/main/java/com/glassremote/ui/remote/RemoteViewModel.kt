package com.glassremote.ui.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glassremote.data.adb.Adb
import com.glassremote.data.macro.Macros
import com.glassremote.service.NowPlayingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteUiState(
    val tvName: String = "Living Room TV",
    val isConnected: Boolean = true,
    val volume: Int = 49,
    val isMuted: Boolean = false,
    val currentApp: String = "livetv",
    val isPlaying: Boolean = false,
    val controlMode: ControlMode = ControlMode.WHEEL,
    val adbStatus: String = "Connected",
    val statusMessage: String? = null
)

enum class ControlMode {
    WHEEL, TRACKPAD, KEYPAD
}

class RemoteViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    init {
        syncNowPlayingWithLockscreen()
    }

    fun setControlMode(mode: ControlMode) {
        _uiState.update { it.copy(controlMode = mode) }
    }

    /**
     * Primary: Sends ADB Keycode.
     * Fallback: Executes fallback Macro when ADB is unreachable or fails.
     */
    fun sendKey(keyCode: Int, macroFallbackName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val adbSuccess = Adb.sendKeyEvent(keyCode)
            if (!adbSuccess) {
                // Trigger Macro as fallback
                macroFallbackName?.let { macroName ->
                    Macros.executeFallback(macroName)
                }
            }
        }
    }

    fun dpadUp() = sendKey(19, "DPAD_UP")
    fun dpadDown() = sendKey(20, "DPAD_DOWN")
    fun dpadLeft() = sendKey(21, "DPAD_LEFT")
    fun dpadRight() = sendKey(22, "DPAD_RIGHT")
    fun dpadCenter() = sendKey(66, "DPAD_CENTER")

    fun back() = sendKey(4, "BACK")
    fun home() = sendKey(3, "HOME")
    fun voice() = sendKey(84, "VOICE")

    fun volumeUp() {
        sendKey(24, "VOL_UP")
        _uiState.update { it.copy(volume = (it.volume + 1).coerceAtMost(100)) }
    }

    fun volumeDown() {
        sendKey(25, "VOL_DOWN")
        _uiState.update { it.copy(volume = (it.volume - 1).coerceAtLeast(0)) }
    }

    fun mute() {
        sendKey(164, "MUTE")
        _uiState.update { it.copy(isMuted = !it.isMuted) }
    }

    fun channelUp() = sendKey(166, "CH_UP")
    fun channelDown() = sendKey(167, "CH_DOWN")

    fun power() = sendKey(26, "POWER")

    fun launchApp(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = Adb.launchPackage(packageName)
            if (!success) {
                Macros.executeFallback("LAUNCH_${packageName.uppercase()}")
            }
            updateNowPlaying(packageName, isPlaying = true)
        }
    }

    fun updateNowPlaying(appName: String, isPlaying: Boolean) {
        _uiState.update { it.copy(currentApp = appName, isPlaying = isPlaying) }
        syncNowPlayingWithLockscreen()
    }

    private fun syncNowPlayingWithLockscreen() {
        val state = _uiState.value
        NowPlayingService.update(
            context = getApplication(),
            title = state.currentApp,
            artist = state.tvName,
            isPlaying = state.isPlaying
        )
    }

    override fun onCleared() {
        super.onCleared()
    }
}
