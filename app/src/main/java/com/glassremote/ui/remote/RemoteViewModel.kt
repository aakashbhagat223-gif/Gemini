package com.glassremote.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val repo: TvRepository,
    private val store: PairedDeviceStore,
) : ViewModel() {

    val status: StateFlow<RemoteStatus> = repo.status

    /** null = still loading, true = a TV is saved, false = needs pairing. */
    val saved: StateFlow<Boolean?> = store.lastDevice
        .map<TvDevice?, Boolean?> { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val deviceName: StateFlow<String> = store.lastDevice
        .map { it?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init { reconnect() }

    fun reconnect() {
        viewModelScope.launch {
            val device = store.lastDevice.first() ?: return@launch
            runCatching { repo.connect(device) }
        }
    }

    fun key(code: Int) {
        viewModelScope.launch { runCatching { repo.sendKey(code, KeyDirection.Short) } }
    }

    fun launchApp(target: String) {
        viewModelScope.launch { runCatching { repo.launchApp(target) } }
    }

    /** Forgets the TV and this phone's identity; the app returns to the pairing screen. */
    fun forget() {
        viewModelScope.launch { repo.resetPairing() }
    }
}
