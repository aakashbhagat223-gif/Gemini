package com.glassremote.ui.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.LinkPhase
import com.glassremote.domain.model.RemoteStatus
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.repository.TvRepository
import com.glassremote.ui.theme.glass
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

data class NetInfo(val wifi: Boolean = false, val phoneIp: String? = null, val pingMs: Int? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    repo: TvRepository,
    store: PairedDeviceStore,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    val status: StateFlow<RemoteStatus> = repo.status

    val device: StateFlow<TvDevice?> = store.lastDevice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Polls every 5 s, only while the Dashboard is on screen. */
    val net: StateFlow<NetInfo> = device
        .flatMapLatest { d ->
            flow {
                while (true) {
                    emit(measure(d))
                    delay(5_000)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetInfo())

    private fun measure(d: TvDevice?): NetInfo {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        val wifi = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ip = cm.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        var ping: Int? = null
        if (d != null) {
            val t0 = System.nanoTime()
            ping = try {
                Socket().use { it.connect(InetSocketAddress(d.host, 6466), 1_500) }
                ((System.nanoTime() - t0) / 1_000_000).toInt()
            } catch (_: Exception) {
                null
            }
        }
        return NetInfo(wifi, ip, ping)
    }
}

@Composable
fun DashboardScreen(vm: DashboardViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val net by vm.net.collectAsStateWithLifecycle()

    val linkColor = when (status.phase) {
        LinkPhase.Ready -> Color(0xFF34D399)
        LinkPhase.Connecting -> Color(0xFFFBBF24)
        else -> Color(0xFFF87171)
    }
    val linkText = when (status.phase) {
        LinkPhase.Ready -> "Connected"
        LinkPhase.Connecting -> "Connecting…"
        LinkPhase.Disconnected -> "Disconnected"
        LinkPhase.Idle -> "Idle"
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Dashboard", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        NowPlaying()

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile("Link", linkText, Modifier.weight(1f), linkColor)
            StatTile("Latency", net.pingMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile("TV address", device?.host ?: "—", Modifier.weight(1f))
            StatTile(
                "This phone", net.phoneIp ?: "—", Modifier.weight(1f),
                if (net.wifi) Color(0xFF34D399) else Color(0xFFF87171),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile(
                "Volume",
                status.volume?.let { v -> if (status.muted == true) "Muted" else "$v / ${status.volumeMax ?: "?"}" } ?: "—",
                Modifier.weight(1f),
            )
            StatTile(
                "Power",
                when (status.poweredOn) { true -> "On"; false -> "Standby"; null -> "—" },
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NowPlaying() {
    Box(Modifier.fillMaxWidth().height(170.dp).glass(RoundedCornerShape(28.dp))) {
        Box(
            Modifier.align(Alignment.CenterStart).padding(16.dp).size(110.dp)
                .blur(18.dp, BlurredEdgeTreatment.Unbounded)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF5B6CFF), Color(0xFFFF5FA2))),
                    RoundedCornerShape(24.dp),
                )
        )
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 148.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("NOW PLAYING", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            Text("Nothing reported", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "This TV link doesn't share media info yet",
                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, dot: Color? = null) {
    Column(
        modifier.height(92.dp).glass(RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
            }
            Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
