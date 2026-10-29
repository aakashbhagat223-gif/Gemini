package com.glassremote.ui.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassremote.ui.theme.GlassBackground
import com.glassremote.ui.theme.Haptics

@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF13192B),
                        Color(0xFF0C0E14),
                        Color(0xFF160914)
                    )
                )
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TV Header Status Pill
            TvStatusCard(
                tvName = uiState.tvName,
                volume = uiState.volume,
                isConnected = uiState.isConnected,
                onPowerClick = {
                    Haptics.click()
                    viewModel.power()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Wheel / Trackpad / Keypad Switcher
            ModeSelector(
                selectedMode = uiState.controlMode,
                onModeSelected = { mode ->
                    Haptics.selection()
                    viewModel.setControlMode(mode)
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // D-Pad / Control Center
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF181A20).copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                // Up
                IconButton(
                    onClick = {
                        Haptics.click()
                        viewModel.dpadUp()
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .size(52.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up", tint = Color.White)
                }

                // Down
                IconButton(
                    onClick = {
                        Haptics.click()
                        viewModel.dpadDown()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .size(52.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down", tint = Color.White)
                }

                // Left
                IconButton(
                    onClick = {
                        Haptics.click()
                        viewModel.dpadLeft()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                        .size(52.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Left", tint = Color.White)
                }

                // Right
                IconButton(
                    onClick = {
                        Haptics.click()
                        viewModel.dpadRight()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .size(52.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Right", tint = Color.White)
                }

                // OK Button
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF232732))
                        .clickable {
                            Haptics.heavyClick()
                            viewModel.dpadCenter()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OK",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Navigation Row (Back, Home, Voice)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                GlassCircularButton(icon = Icons.Default.ArrowBack) {
                    Haptics.click()
                    viewModel.back()
                }
                GlassCircularButton(icon = Icons.Default.Home) {
                    Haptics.click()
                    viewModel.home()
                }
                GlassCircularButton(icon = Icons.Default.Mic) {
                    Haptics.click()
                    viewModel.voice()
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sliders / Pill Controls for Volume, Mute, Channel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Volume Pillar
                PillControl(
                    label = "VOL",
                    onUp = { viewModel.volumeUp() },
                    onDown = { viewModel.volumeDown() }
                )

                // Mute
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E212B))
                        .clickable {
                            Haptics.click()
                            viewModel.mute()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeMute,
                        contentDescription = "Mute",
                        tint = Color.White
                    )
                }

                // Channel Pillar
                PillControl(
                    label = "CH",
                    onUp = { viewModel.channelUp() },
                    onDown = { viewModel.channelDown() }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Quick App Launchers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QuickAppPill("YouTube") { viewModel.launchApp("com.google.android.youtube.tv") }
                QuickAppPill("Stremio") { viewModel.launchApp("com.stremio.one") }
                QuickAppPill("HDMI 2") { viewModel.sendKey(243) }
                QuickAppPill("Apps") { viewModel.sendKey(284) }
            }
        }
    }
}

@Composable
private fun TvStatusCard(
    tvName: String,
    volume: Int,
    isConnected: Boolean,
    onPowerClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF1E2230).copy(alpha = 0.7f))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tvName,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                text = "${if (isConnected) "Connected" else "Disconnected"} · Vol $volume",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
        IconButton(
            onClick = onPowerClick,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF2D1822))
        ) {
            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Power", tint = Color(0xFFFF5252))
        }
    }
}

@Composable
private fun ModeSelector(
    selectedMode: ControlMode,
    onModeSelected: (ControlMode) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1A1D27).copy(alpha = 0.8f))
            .padding(4.dp)
    ) {
        listOf(
            "Wheel" to ControlMode.WHEEL,
            "Trackpad" to ControlMode.TRACKPAD,
            "Keypad" to ControlMode.KEYPAD
        ).forEach { (label, mode) ->
            val isSelected = selectedMode == mode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isSelected) Color(0xFF33384A) else Color.Transparent)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else Color.Gray,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun GlassCircularButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E212B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White)
    }
}

@Composable
private fun PillControl(
    label: String,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(Color(0xFF181B24))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = {
            Haptics.click()
            onUp()
        }) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.White)
        }
        Text(
            text = label,
            color = Color.LightGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        IconButton(onClick = {
            Haptics.click()
            onDown()
        }) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun QuickAppPill(
    name: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C202B).copy(alpha = 0.7f))
            .clickable {
                Haptics.click()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
