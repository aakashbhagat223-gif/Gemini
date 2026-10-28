package com.glassremote.ui.remote

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.bluetooth.BluetoothHidController
import com.glassremote.data.macro.MacroIds
import com.glassremote.data.macro.MacroRunState
import com.glassremote.data.macro.MacroRunner
import com.glassremote.data.macro.MacroStore
import com.glassremote.domain.model.KeyDirection
import com.glassremote.domain.repository.TvRepository
import com.glassremote.ui.theme.LocalHaptics
import com.glassremote.ui.theme.glass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val ROUTE_CEC = "Via the TV (HDMI-CEC pass-through)"

@HiltViewModel
class JioViewModel @Inject constructor(
    private val repo: TvRepository,
    private val bt: BluetoothHidController,
    private val adb: AdbManager,
    private val actions: NativeActions,
    private val macros: MacroStore,
    private val runner: MacroRunner,
) : ViewModel() {

    val macroState: StateFlow<MacroRunState> = runner.state

    private val _note = MutableStateFlow("")
    val note: StateFlow<String> = _note.asStateFlow()

    private fun isJio(name: String?) = name?.contains("jio", ignoreCase = true) == true

    /** Where Jio keys currently go. */
    val route: StateFlow<String> = bt.state
        .map { st -> if (isJio(st.connectedName)) "Bluetooth → ${st.connectedName}" else ROUTE_CEC }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ROUTE_CEC)

    fun cancelMacro() = runner.cancel()

    /** Puts the TV on the HDMI 2 input the Jio box is plugged into. */
    fun selectJio() {
        viewModelScope.launch {
            _note.value = "Switching the TV to HDMI 2…"
            runCatching {
                val input = actions.savedHdmi2()
                if (adb.state.value.connected && input.isNotBlank()) {
                    actions.switchInput(input)
                    _note.value = "TV is on HDMI 2"
                } else {
                    runner.run(macros.get(MacroIds.HDMI2), label = "HDMI 2")
                    _note.value = "Ran the HDMI 2 macro"
                }
            }.onFailure { _note.value = "Couldn't switch input: ${it.message}" }
        }
    }

    /** Bluetooth to the Jio box when connected; otherwise the TV link (the TV passes keys on over CEC). */
    fun key(code: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            if (bt.isConnected && isJio(bt.state.value.connectedName) && bt.sendKey(code)) return@launch
            runCatching { repo.sendKey(code, KeyDirection.Short) }
        }
    }
}

/** Glass target bar on top: [Xiaomi TV] [Jio Box]. The Xiaomi side is the existing remote screen. */
@Composable
fun RemoteHost(remoteVm: RemoteViewModel, jioVm: JioViewModel = hiltViewModel()) {
    var target by rememberSaveable { mutableIntStateOf(0) }
    val macro by jioVm.macroState.collectAsStateWithLifecycle()
    val note by jioVm.note.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TargetBar(target, macro, onCancel = { jioVm.cancelMacro() }) { index ->
            if (index != target) {
                target = index
                if (index == 1) jioVm.selectJio()
            }
        }
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
            if (target == 0) RemoteScreen(remoteVm) else JioRemoteScreen(jioVm, note)
        }
    }
}

@Composable
private fun TargetBar(target: Int, macro: MacroRunState, onCancel: () -> Unit, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.height(44.dp).glass(RoundedCornerShape(22.dp), strong = true).padding(3.dp)) {
            listOf("Xiaomi TV", "Jio Box").forEachIndexed { index, label ->
                val selected = target == index
                Box(
                    Modifier
                        .width(110.dp)
                        .fillMaxHeight()
                        .background(
                            if (selected) Color.White.copy(alpha = 0.20f) else Color.Transparent,
                            RoundedCornerShape(19.dp),
                        )
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        if (macro.running) {
            Text(
                "Macro ${macro.step}/${macro.total}  ✕",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun JioRemoteScreen(vm: JioViewModel, note: String) {
    val route by vm.route.collectAsStateWithLifecycle()
    var showDigits by rememberSaveable { mutableStateOf(false) }
    val send: (Int) -> Unit = { vm.key(it) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            route + if (note.isNotBlank()) " · $note" else "",
            color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            JioKey(Modifier.size(58.dp), CircleShape, { send(KeyEvent.KEYCODE_POWER) }) {
                JioGlyph(Icons.Rounded.PowerSettingsNew, 24.dp, Color(0xFFFF6B6B))
            }
            JioKey(Modifier.size(124.dp, 58.dp), RoundedCornerShape(29.dp), { send(KeyEvent.KEYCODE_HOME) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JioGlyph(Icons.Rounded.Home, 22.dp)
                    Text("JioHome", color = Color.White, fontSize = 13.sp)
                }
            }
            JioKey(Modifier.size(100.dp, 58.dp), RoundedCornerShape(29.dp), { send(KeyEvent.KEYCODE_GUIDE) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JioGlyph(Icons.Rounded.GridView, 20.dp)
                    Text("Guide", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        CompactDPad(send)

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            JioKey(Modifier.size(92.dp, 54.dp), RoundedCornerShape(27.dp), { send(KeyEvent.KEYCODE_BACK) }) {
                JioGlyph(Icons.Rounded.ArrowBack, 24.dp)
            }
            JioKey(Modifier.size(92.dp, 54.dp), RoundedCornerShape(27.dp), { send(KeyEvent.KEYCODE_MENU) }) {
                JioGlyph(Icons.Rounded.Menu, 24.dp)
            }
            JioKey(
                Modifier.size(92.dp, 54.dp), RoundedCornerShape(27.dp),
                { showDigits = !showDigits },
            ) { JioGlyph(Icons.Rounded.Dialpad, 24.dp, if (showDigits) Color.White else Color.White.copy(alpha = 0.7f)) }
        }

        AnimatedVisibility(visible = showDigits) { DigitPad(send) }

        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Rocker("VOL", Icons.Rounded.Add, Icons.Rounded.Remove,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, send)
            JioKey(Modifier.size(64.dp), CircleShape, { send(KeyEvent.KEYCODE_VOLUME_MUTE) }) {
                JioGlyph(Icons.Rounded.VolumeOff, 26.dp)
            }
            Rocker("CH", Icons.Rounded.ExpandLess, Icons.Rounded.ExpandMore,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, send)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CompactDPad(send: (Int) -> Unit) {
    Box(Modifier.size(224.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
        ArrowKey(Icons.Rounded.KeyboardArrowUp, Alignment.TopCenter, KeyEvent.KEYCODE_DPAD_UP, send)
        ArrowKey(Icons.Rounded.KeyboardArrowDown, Alignment.BottomCenter, KeyEvent.KEYCODE_DPAD_DOWN, send)
        ArrowKey(Icons.Rounded.KeyboardArrowLeft, Alignment.CenterStart, KeyEvent.KEYCODE_DPAD_LEFT, send)
        ArrowKey(Icons.Rounded.KeyboardArrowRight, Alignment.CenterEnd, KeyEvent.KEYCODE_DPAD_RIGHT, send)
        JioKey(Modifier.size(84.dp), CircleShape, { send(KeyEvent.KEYCODE_DPAD_CENTER) }) {
            Text("OK", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BoxScope.ArrowKey(icon: ImageVector, align: Alignment, code: Int, send: (Int) -> Unit) {
    val haptics = LocalHaptics.current
    Box(
        Modifier
            .align(align)
            .padding(8.dp)
            .size(60.dp)
            .clip(CircleShape)
            .clickable {
                haptics?.tick()
                send(code)
            },
        contentAlignment = Alignment.Center,
    ) { JioGlyph(icon, 32.dp) }
}

@Composable
private fun DigitPad(send: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { n -> DigitKey(n, send) }
            }
        }
        DigitKey(0, send)
    }
}

@Composable
private fun DigitKey(n: Int, send: (Int) -> Unit) {
    JioKey(Modifier.size(72.dp, 54.dp), RoundedCornerShape(18.dp), { send(KeyEvent.KEYCODE_0 + n) }) {
        Text(n.toString(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Rocker(
    label: String, up: ImageVector, down: ImageVector,
    upCode: Int, downCode: Int, send: (Int) -> Unit,
) {
    val haptics = LocalHaptics.current
    Column(
        Modifier.width(84.dp).height(196.dp).glass(RoundedCornerShape(42.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clickable {
                haptics?.tick()
                send(upCode)
            },
            contentAlignment = Alignment.Center,
        ) { JioGlyph(up, 28.dp) }
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier.weight(1f).fillMaxWidth().clickable {
                haptics?.tick()
                send(downCode)
            },
            contentAlignment = Alignment.Center,
        ) { JioGlyph(down, 28.dp) }
    }
}

@Composable
private fun JioKey(modifier: Modifier, shape: Shape, onTap: () -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHaptics.current
    Box(
        modifier
            .glass(shape)
            .clip(shape)
            .clickable {
                haptics?.tick()
                onTap()
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun JioGlyph(icon: ImageVector, size: Dp, tint: Color = Color.White.copy(alpha = 0.92f)) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}
