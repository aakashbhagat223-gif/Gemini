package com.glassremote.ui.macros

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.glassremote.data.macro.MacroIds
import com.glassremote.data.macro.MacroRecorder
import com.glassremote.data.macro.MacroRunner
import com.glassremote.data.macro.MacroStore
import com.glassremote.ui.theme.glass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MacrosViewModel @Inject constructor(
    private val store: MacroStore,
    private val runner: MacroRunner,
    private val recorder: MacroRecorder,
) : ViewModel() {

    val recording: StateFlow<String?> = recorder.target
    val units: StateFlow<Int> = store.unitsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    fun text(id: String): Flow<String> = store.observe(id)
    fun save(id: String, text: String) { viewModelScope.launch { store.set(id, text) } }
    fun reset(id: String) { viewModelScope.launch { store.reset(id) } }
    fun test(id: String) { viewModelScope.launch { runCatching { runner.run(store.get(id)) } } }
    fun record(id: String) { recorder.start(id) }
    fun setUnits(v: Int) { viewModelScope.launch { store.setUnits(v) } }

    fun stopRecording() {
        val result = recorder.stop() ?: return
        viewModelScope.launch { store.set(result.first, result.second) }
    }
}

private fun hint(id: String): String = when (id) {
    MacroIds.STREMIO -> "Leave empty to open Stremio with a direct link. If that fails, record: Home, move to Stremio, OK."
    MacroIds.HDMI2 -> "Runs when you tap the HDMI 2 tile."
    MacroIds.BACKLIGHT_OPEN -> "Runs when you start moving the Dashboard brightness slider. Should end on the Backlight value."
    MacroIds.BACKLIGHT_CLOSE -> "Runs about 3 seconds after you stop moving the slider."
    else -> ""
}

@Composable
fun MacrosScreen(vm: MacrosViewModel) {
    val recording by vm.recording.collectAsStateWithLifecycle()
    val units by vm.units.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Macros", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("How macros work", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Keys: UP DOWN LEFT RIGHT OK BACK HOME SETTINGS MENU SEARCH ALL_APPS TV_INPUT, plus letters and digits. " +
                    "WAIT 800 pauses 800 ms. DOWN*3 repeats a key. There is 200 ms between presses. " +
                    "Record: tap Record, use the Remote tab on the TV as normal, then tap Stop.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
            )
        }

        MacroIds.all.forEach { id -> MacroCard(id, vm, recording) }

        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Backlight slider", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Slider units per key press. If one press changes the TV backlight by 5, enter 5.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
            )
            var u by remember(units) { mutableStateOf(units.toString()) }
            OutlinedTextField(
                value = u,
                onValueChange = { v ->
                    u = v
                    v.toIntOrNull()?.let { vm.setUnits(it) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors(),
            )
        }
    }
}

@Composable
private fun MacroCard(id: String, vm: MacrosViewModel, recording: String?) {
    val flow = remember(id) { vm.text(id) }
    val saved by flow.collectAsStateWithLifecycle(initialValue = "")
    var text by remember(saved) { mutableStateOf(saved) }

    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(MacroIds.title(id), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(hint(id), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            colors = fieldColors(),
        )
        Row {
            MiniButton("Save", true) { vm.save(id, text) }
            MiniButton("Test", true) { vm.test(id) }
            MiniButton(if (recording == id) "Recording…" else "Record", recording == null) { vm.record(id) }
            MiniButton("Reset", true) { vm.reset(id) }
        }
    }
}

@Composable
private fun MiniButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.35f),
        ),
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.6f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
)
