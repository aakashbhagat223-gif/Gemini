package com.glassremote.ui.remote

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassremote.data.macro.MacroIds
import com.glassremote.data.network.ImeInfo
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.ui.theme.LocalHaptics
import com.glassremote.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val STREMIO_KEY = "stremio"

private data class AppEntry(val label: String, val target: String)

private val APPS = listOf(
    AppEntry("YouTube", "https://www.youtube.com"),
    AppEntry("Stremio", STREMIO_KEY),
    AppEntry("Netflix", "market://launch?id=com.netflix.ninja"),
    AppEntry("Prime Video", "market://launch?id=com.amazon.amazonvideo.livingroom"),
    AppEntry("Spotify", "market://launch?id=com.spotify.tv.android"),
    AppEntry("Disney+", "market://launch?id=com.disney.disneyplus"),
)

@Composable
fun RemoteScreen(vm: RemoteViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val ime by vm.ime.collectAsStateWithLifecycle()
    val replaceMode by vm.replaceMode.collectAsStateWithLifecycle()
    val press: (Int) -> Unit = { vm.key(it) }
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 wheel, 1 trackpad, 2 keypad
    var showApps by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TopBar(name, status, onStatusTap = { vm.reconnect() }, onPower = { press(KeyEvent.KEYCODE_POWER) })
            ModeSwitch(mode) { mode = it }

            if (mode == 2) {
                NavRow(press)
                Keypad(
                    ime = ime,
                    replaceMode = replaceMode,
                    onToggleMode = { vm.toggleReplaceMode() },
                    onChar = { vm.typeChar(it) },
                    onBackspace = { vm.backspace() },
                    onGo = { vm.submitText() },
                    onArrow = press,
                )
            } else {
                if (mode == 1) Trackpad(press) else DPad(press)
                NavRow(press)

                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillControl("VOL", Icons.Rounded.Add, Icons.Rounded.Remove,
                        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, press)
                    KeyButton(Modifier.size(64.dp), CircleShape, onPressed = { press(KeyEvent.KEYCODE_VOLUME_MUTE) }) {
                        Glyph(Icons.Rounded.VolumeOff, 26.dp)
                    }
                    PillControl("CH", Icons.Rounded.ExpandLess, Icons.Rounded.ExpandMore,
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, press)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppTile("YouTube", Icons.Rounded.PlayArrow) { vm.launchApp("https://www.youtube.com") }
                    AppTile("Stremio", Icons.Rounded.Movie) { vm.openStremio() }
                    AppTile("HDMI 2", Icons.Rounded.Tv) { vm.runMacro(MacroIds.HDMI2) }
                    AppTile("Apps", Icons.Rounded.Apps) { showApps = true }
                }
            }
        }

        if (showApps) {
            AppPicker(
                onDismiss = { showApps = false },
                onLaunch = { target ->
                    if (target == STREMIO_KEY) vm.openStremio() else vm.launchApp(target)
                    showApps = false
                },
                onTvList = {
                    press(KeyEvent.KEYCODE_ALL_APPS)
                    showApps = false
                },
            )
        }
    }
}

@Composable
private fun TopBar(name: String, status: RemoteStatus, onStatusTap: () -> Unit, onPower: () -> Unit) {
    val dot = when (status.phase) {
        LinkPhase.Ready -> Color(0xFF34D399)
        LinkPhase.Connecting -> Color(0xFFFBBF24)
        else -> Color(0xFFF87171)
    }
    val subtitle = when (status.phase) {
        LinkPhase.Ready -> "Connected" + (status.volume?.let { " · Vol $it" } ?: "")
        LinkPhase.Connecting -> "Connecting…"
        LinkPhase.Disconnected -> "Disconnected" + (status.error?.let { " · $it" } ?: "") + " · tap to retry"
        LinkPhase.Idle -> "Not connected · tap to connect"
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .glass(RoundedCornerShape(26.dp))
                .clickable(onClick = onStatusTap)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).background(dot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    name.ifBlank { "Google TV" },
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 2)
            }
        }
        KeyButton(Modifier.size(52.dp), CircleShape, onPressed = onPower) {
            Glyph(Icons.Rounded.PowerSettingsNew, 24.dp, Color(0xFFFF6B6B))
        }
    }
}

@Composable
private fun ModeSwitch(mode: Int, onChange: (Int) -> Unit) {
    Row(Modifier.height(40.dp).glass(RoundedCornerShape(20.dp)).padding(3.dp)) {
        listOf("Wheel", "Trackpad", "Keypad").forEachIndexed { index, label ->
            val selected = mode == index
            Box(
                Modifier
                    .width(92.dp)
                    .fillMaxHeight()
                    .background(
                        if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                        RoundedCornerShape(17.dp),
                    )
                    .clickable { onChange(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun NavRow(press: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        KeyButton(Modifier.size(92.dp, 58.dp), RoundedCornerShape(29.dp), onPressed = { press(KeyEvent.KEYCODE_BACK) }) {
            Glyph(Icons.Rounded.ArrowBack, 26.dp)
        }
        KeyButton(Modifier.size(92.dp, 58.dp), RoundedCornerShape(29.dp), onPressed = { press(KeyEvent.KEYCODE_HOME) }) {
            Glyph(Icons.Rounded.Home, 26.dp)
        }
        KeyButton(Modifier.size(92.dp, 58.dp), RoundedCornerShape(29.dp), onPressed = { press(KeyEvent.KEYCODE_ASSIST) }) {
            Glyph(Icons.Rounded.Mic, 26.dp)
        }
    }
}

@Composable
private fun DPad(press: (Int) -> Unit) {
    Box(Modifier.size(272.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
        DPadArrow(Icons.Rounded.KeyboardArrowUp, Alignment.TopCenter, KeyEvent.KEYCODE_DPAD_UP, press)
        DPadArrow(Icons.Rounded.KeyboardArrowDown, Alignment.BottomCenter, KeyEvent.KEYCODE_DPAD_DOWN, press)
        DPadArrow(Icons.Rounded.KeyboardArrowLeft, Alignment.CenterStart, KeyEvent.KEYCODE_DPAD_LEFT, press)
        DPadArrow(Icons.Rounded.KeyboardArrowRight, Alignment.CenterEnd, KeyEvent.KEYCODE_DPAD_RIGHT, press)
        KeyButton(Modifier.size(104.dp), CircleShape, onPressed = { press(KeyEvent.KEYCODE_DPAD_CENTER) }) {
            Text("OK", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BoxScope.DPadArrow(icon: ImageVector, align: Alignment, code: Int, press: (Int) -> Unit) {
    Box(
        Modifier
            .align(align)
            .padding(10.dp)
            .size(72.dp)
            .holdKey(repeat = true, onPressed = { press(code) }),
        contentAlignment = Alignment.Center,
    ) { Glyph(icon, 36.dp) }
}

/** Swipe to move (one key per ~40dp of travel), tap to select. */
@Composable
private fun Trackpad(press: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val send by rememberUpdatedState<(Int) -> Unit> { code ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        press(code)
    }
    Box(
        Modifier
            .size(300.dp, 272.dp)
            .glass(RoundedCornerShape(40.dp))
            .pointerInput(Unit) {
                val threshold = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var ax = 0f
                    var ay = 0f
                    var moved = false
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            finished = true
                        } else {
                            val d = change.positionChange()
                            ax += d.x
                            ay += d.y
                            if (abs(ax) > threshold || abs(ay) > threshold) {
                                moved = true
                                val code = if (abs(ax) > abs(ay)) {
                                    if (ax > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                                } else {
                                    if (ay > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                                }
                                send(code)
                                ax = 0f
                                ay = 0f
                            }
                            change.consume()
                        }
                    }
                    if (!moved) send(KeyEvent.KEYCODE_DPAD_CENTER)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Swipe to move · tap to select", color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp)
    }
}

/**
 * Text goes over the TV's text-input channel when the TV reports a focused text field;
 * otherwise each key is sent as a normal key press.
 */
@Composable
private fun Keypad(
    ime: ImeInfo,
    replaceMode: Boolean,
    onToggleMode: () -> Unit,
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onGo: () -> Unit,
    onArrow: (Int) -> Unit,
) {
    val rows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (ime.active) "TV field: " + ime.label.ifBlank { "text input" } + " · \"" + ime.value + "\""
                else "No TV text field reported · sending key presses",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (ime.active) {
                Text(
                    if (replaceMode) "Replace" else "Insert",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(onClick = onToggleMode)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        rows.forEachIndexed { index, row ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (index == 2) 14.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { ch -> Cap(ch.toString(), Modifier.weight(1f)) { onChar(ch) } }
                if (index == 3) Cap("⌫", Modifier.weight(1.6f), repeat = true) { onBackspace() }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Cap("←", Modifier.weight(1f)) { onArrow(KeyEvent.KEYCODE_DPAD_LEFT) }
            Cap("↑", Modifier.weight(1f)) { onArrow(KeyEvent.KEYCODE_DPAD_UP) }
            Cap("↓", Modifier.weight(1f)) { onArrow(KeyEvent.KEYCODE_DPAD_DOWN) }
            Cap("→", Modifier.weight(1f)) { onArrow(KeyEvent.KEYCODE_DPAD_RIGHT) }
            Cap("space", Modifier.weight(2.2f)) { onChar(' ') }
            Cap("Go", Modifier.weight(1.3f)) { onGo() }
        }
    }
}

@Composable
private fun Cap(label: String, modifier: Modifier, repeat: Boolean = false, onTap: () -> Unit) {
    KeyButton(modifier.height(46.dp), RoundedCornerShape(12.dp), repeat = repeat, crisp = true, onPressed = onTap) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PillControl(
    label: String, upIcon: ImageVector, downIcon: ImageVector,
    upCode: Int, downCode: Int, press: (Int) -> Unit,
) {
    Column(
        Modifier.width(84.dp).height(196.dp).glass(RoundedCornerShape(42.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth().holdKey(repeat = true, onPressed = { press(upCode) }),
            contentAlignment = Alignment.Center,
        ) { Glyph(upIcon, 28.dp) }
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier.weight(1f).fillMaxWidth().holdKey(repeat = true, onPressed = { press(downCode) }),
            contentAlignment = Alignment.Center,
        ) { Glyph(downIcon, 28.dp) }
    }
}

@Composable
private fun AppTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    KeyButton(Modifier.size(76.dp), RoundedCornerShape(24.dp), onPressed = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(icon, 24.dp)
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        }
    }
}

/** Solid (opaque) dialog so nothing from the remote shows through. */
@Composable
private fun AppPicker(onDismiss: () -> Unit, onLaunch: (String) -> Unit, onTvList: () -> Unit) {
    val panel = RoundedCornerShape(28.dp)
    val chip = RoundedCornerShape(18.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(panel)
                .background(Color(0xFF1A1C23))
                .border(0.8.dp, Color.White.copy(alpha = 0.18f), panel)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Open an app", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            APPS.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { app ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(54.dp)
                                .clip(chip)
                                .background(Color(0xFF2A2D37))
                                .clickable { onLaunch(app.target) },
                            contentAlignment = Alignment.Center,
                        ) { Text(app.label, color = Color.White, fontSize = 14.sp) }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(chip)
                    .background(Color(0xFF2A2D37))
                    .clickable(onClick = onTvList),
                contentAlignment = Alignment.Center,
            ) { Text("Show the TV's own app list", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp) }
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector, size: Dp, tint: Color = Color.White.copy(alpha = 0.92f)) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun KeyButton(
    modifier: Modifier,
    shape: Shape,
    repeat: Boolean = false,
    crisp: Boolean = false,
    onPressed: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    Box(
        modifier
            .scale(scale)
            .glass(shape, strong = pressed)
            .holdKey(repeat, onPressed, crisp) { pressed = it },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * Fires on touch-down; if [repeat], keeps firing while held.
 * [crisp] uses the short vibration tick (keyboard); otherwise the standard system haptic.
 */
@Composable
private fun Modifier.holdKey(
    repeat: Boolean,
    onPressed: () -> Unit,
    crisp: Boolean = false,
    onDown: (Boolean) -> Unit = {},
): Modifier {
    val scope = rememberCoroutineScope()
    val systemHaptic = LocalHapticFeedback.current
    val haptics = LocalHaptics.current
    val current by rememberUpdatedState(onPressed)
    val downCallback by rememberUpdatedState(onDown)
    return pointerInput(repeat, crisp) {
        detectTapGestures(onPress = {
            downCallback(true)
            if (crisp && haptics != null) haptics.tick()
            else systemHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            current()
            val job = if (repeat) scope.launch {
                delay(450)
                while (true) { current(); delay(110) }
            } else null
            tryAwaitRelease()
            job?.cancel()
            downCallback(false)
        })
    }
}
