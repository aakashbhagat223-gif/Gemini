package com.glassremote.ui.remote

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.ui.theme.GlassBackground
import com.glassremote.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RemoteScreen(vm: RemoteViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val press: (Int) -> Unit = { vm.key(it) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            TopBar(name, status, onStatusTap = { vm.reconnect() }, onPower = { press(KeyEvent.KEYCODE_POWER) })
            DPad(press)

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
                AppTile("Netflix", Icons.Rounded.Movie) { vm.launchApp("com.netflix.ninja") }
                AppTile("Prime", Icons.Rounded.Tv) { vm.launchApp("com.amazon.amazonvideo.livingroom") }
                AppTile("YouTube", Icons.Rounded.PlayArrow) { vm.launchApp("https://www.youtube.com") }
                AppTile("Apps", Icons.Rounded.Apps) { press(KeyEvent.KEYCODE_ALL_APPS) }
            }

            TextButton(onClick = { vm.forget() }) {
                Text("Forget this TV", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
            }
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

@Composable
private fun Glyph(icon: ImageVector, size: Dp, tint: Color = Color.White.copy(alpha = 0.92f)) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
private fun KeyButton(
    modifier: Modifier,
    shape: Shape,
    repeat: Boolean = false,
    onPressed: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    Box(
        modifier
            .scale(scale)
            .glass(shape, strong = pressed)
            .holdKey(repeat, onPressed) { pressed = it },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Fires on touch-down; if [repeat], keeps firing while held (volume, d-pad). */
@Composable
private fun Modifier.holdKey(
    repeat: Boolean,
    onPressed: () -> Unit,
    onDown: (Boolean) -> Unit = {},
): Modifier {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onPressed)
    val downCallback by rememberUpdatedState(onDown)
    return pointerInput(repeat) {
        detectTapGestures(onPress = {
            downCallback(true)
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
