package com.glassremote.ui.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.glassremote.data.adb.AdbManager
import com.glassremote.data.adb.NativeActions
import com.glassremote.data.adb.TvInput
import com.glassremote.data.bluetooth.BluetoothHidController
import com.glassremote.data.repository.PairedDeviceStore
import com.glassremote.domain.model.TvDevice
import com.glassremote.ui.theme.glass
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

@HiltViewModel
class LinkViewModel @Inject constructor(
    val bt: BluetoothHidController,
    val adb: AdbManager,
    private val actions: NativeActions,
    store: PairedDeviceStore,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    val device: StateFlow<TvDevice?> = store.lastDevice
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _inputs = MutableStateFlow<List<TvInput>>(emptyList())
    val inputs: StateFlow<List<TvInput>> = _inputs.asStateFlow()

    private val _hdmi2 = MutableStateFlow("")
    val hdmi2: StateFlow<String> = _hdmi2.asStateFlow()

    private val _btDevices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val btDevices: StateFlow<List<Pair<String, String>>> = _btDevices.asStateFlow()

    private val _scan = MutableStateFlow<List<String>>(emptyList())
    val scan: StateFlow<List<String>> = _scan.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    init {
        viewModelScope.launch { _hdmi2.value = actions.savedHdmi2() }
        refreshBt()
    }

    fun refreshBt() { _btDevices.value = bt.bondedInfo() }

    fun connectBt(address: String) {
        viewModelScope.launch(Dispatchers.IO) { bt.connectTo(address) }
    }

    fun connectAdb(host: String, port: Int) {
        viewModelScope.launch {
            adb.connect(host, port)
            _output.value = adb.state.value.message
        }
    }

    private fun run(label: String, block: suspend () -> String) {
        viewModelScope.launch {
            _output.value = "$label…"
            _output.value = runCatching { block() }
                .fold({ it.ifBlank { "Done" } }, { "Error: ${it.message}" })
        }
    }

    fun testAdb() = run("Testing") { adb.shell("getprop ro.product.model") }

    fun launchStremio() = run("Launching Stremio") { actions.launchPackage("com.stremio.one") }

    fun scanInputs() {
        viewModelScope.launch {
            _output.value = "Scanning inputs…"
            runCatching { actions.hdmiInputs() }
                .onSuccess {
                    _inputs.value = it
                    _output.value = if (it.isEmpty()) "No HDMI inputs found" else "Found ${it.size} input(s)"
                }
                .onFailure { _output.value = "Error: ${it.message}" }
        }
    }

    fun switchInput(id: String) = run("Switching") { actions.switchInput(id) }

    fun useAsHdmi2(id: String) {
        viewModelScope.launch {
            actions.saveHdmi2(id)
            _hdmi2.value = id
            _output.value = "Saved as HDMI 2"
        }
    }

    fun probeBacklight() = run("Searching settings") {
        actions.backlightSettings().joinToString("\n").ifBlank { "No matching settings found" }
    }

    fun scanNetwork() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _scan.value = emptyList()
            _scan.value = withContext(Dispatchers.IO) { scanSubnet() }
            _scanning.value = false
        }
    }

    /** Probes the phone's /24 for the Android TV remote (6466), ADB (5555) and Cast (8009/8008) ports. */
    private suspend fun scanSubnet(): List<String> = coroutineScope {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val ip = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
            ?: return@coroutineScope emptyList()
        val prefix = ip.substringBeforeLast('.')
        val ports = listOf(6466, 5555, 8009, 8008)
        val gate = Semaphore(48)
        (1..254).map { n ->
            async {
                gate.withPermit {
                    val host = "$prefix.$n"
                    val open = ports.filter { p ->
                        runCatching {
                            Socket().use { it.connect(InetSocketAddress(host, p), 350) }
                            true
                        }.getOrDefault(false)
                    }
                    if (open.isEmpty()) null else "$host  ports ${open.joinToString()}"
                }
            }
        }.awaitAll().filterNotNull()
    }
}

@Composable
fun LinkScreen(vm: LinkViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val btState by vm.bt.state.collectAsStateWithLifecycle()
    val prefer by vm.bt.prefer.collectAsStateWithLifecycle()
    val adbState by vm.adb.state.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val btDevices by vm.btDevices.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val hdmi2 by vm.hdmi2.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    var host by remember(device) { mutableStateOf(device?.host ?: "") }
    var port by remember { mutableStateOf("5555") }

    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.bt.start()
            vm.refreshBt()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Link", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Section("Bluetooth backup") {
            Text(
                (btState.connectedName?.let { "Connected to $it" } ?: "Not connected") + " · " + btState.message,
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(checked = prefer, onCheckedChange = { vm.bt.setPrefer(it) })
                Text("Prefer Bluetooth (use when Wi-Fi is unstable)", color = Color.White, fontSize = 13.sp)
            }
            Row {
                LinkButton("Enable") {
                    if (Build.VERSION.SDK_INT >= 31 && !vm.bt.hasPermission()) {
                        askBt.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        vm.bt.start()
                        vm.refreshBt()
                    }
                }
                LinkButton("Make discoverable") {
                    ctx.startActivity(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180)
                    )
                }
                LinkButton("Refresh") { vm.refreshBt() }
            }
            Text(
                "Setup: tap Enable, then Make discoverable. On the TV open Settings → Remotes & accessories → " +
                    "Add accessory and pick this phone. Then tap the TV below to connect.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
            )
            btDevices.forEach { (name, address) ->
                Text(
                    name,
                    color = Color.White, fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.connectBt(address) }
                        .padding(vertical = 8.dp),
                )
            }
        }

        Section("ADB (native actions + Now Playing)") {
            Text(adbState.message, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    label = { Text("TV IP") }, modifier = Modifier.weight(2f), colors = fieldColors(),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it }, singleLine = true,
                    label = { Text("Port") }, modifier = Modifier.weight(1f), colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row {
                LinkButton("Connect") { vm.connectAdb(host.trim(), port.toIntOrNull() ?: 5555) }
                LinkButton("Test") { vm.testAdb() }
                LinkButton("Disconnect") { vm.adb.disconnect() }
            }
            Text(
                "Turn on USB debugging in the TV's Developer options first. Accept the prompt on the TV " +
                    "the first time.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
            )
        }

        Section("Native actions (needs ADB)") {
            Row {
                LinkButton("Launch Stremio") { vm.launchStremio() }
                LinkButton("Scan HDMI inputs") { vm.scanInputs() }
                LinkButton("Find backlight") { vm.probeBacklight() }
            }
            Text(
                if (hdmi2.isBlank()) "HDMI 2 input: not set" else "HDMI 2 input: $hdmi2",
                color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
            )
            inputs.forEach { input ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(input.id, color = Color.White, fontSize = 12.sp)
                    Row {
                        LinkButton("Switch") { vm.switchInput(input.id) }
                        LinkButton("Use as HDMI 2") { vm.useAsHdmi2(input.id) }
                    }
                }
            }
            if (output.isNotBlank()) {
                Text(output, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }

        Section("Find other devices (Jio set-top box)") {
            Row {
                LinkButton(if (scanning) "Scanning…" else "Scan network") { vm.scanNetwork() }
            }
            Text(
                "Lists devices on your Wi-Fi with open remote (6466), ADB (5555) or Cast (8009/8008) ports.",
                color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
            )
            scan.forEach { Text(it, color = Color.White, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.6f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
    focusedLabelColor = Color.White.copy(alpha = 0.7f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
)
