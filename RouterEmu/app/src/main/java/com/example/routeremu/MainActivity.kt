package com.example.routeremu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RouterEmuScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterEmuScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(EmuConfig()) }
    var kernelFile by remember { mutableStateOf<File?>(null) }
    var diskFile by remember { mutableStateOf<File?>(null) }
    var busyboxFile by remember { mutableStateOf<File?>(null) }
    var libnvramFile by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<ImageReport?>(null) }
    var downloadProgressLabel by remember { mutableStateOf<String?>(null) }
    var deviceMenuExpanded by remember { mutableStateOf(false) }

    val status by QemuLogBus.status.collectAsState()
    val webPortReady by QemuLogBus.webPortReady.collectAsState()
    val webUrl by QemuLogBus.webUrl.collectAsState()

    // --- Runtime notification permission (Android 13+) -------------------
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: service still runs, just may not show a heads-up banner */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // --- File pickers (SAF) ------------------------------------------------
    // Must be @Composable: rememberLauncherForActivityResult is composable, and
    // Kotlin will not let a plain local function call it.
    @Composable
    fun pickerFor(slot: AssetSlot, onPicked: (File) -> Unit) =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                scope.launch {
                    try {
                        onPicked(
                            AssetInstaller.importFromUri(
                                context, uri, AssetInstaller.fileName(slot, config.endianness)
                            )
                        )
                    } catch (t: Throwable) {
                        QemuLogBus.log("[ui] import failed: ${t.message}")
                    }
                }
            }
        }

    val kernelPicker = pickerFor(AssetSlot.KERNEL) { kernelFile = it }
    val diskPicker = pickerFor(AssetSlot.DISK) { diskFile = it }
    val busyboxPicker = pickerFor(AssetSlot.BUSYBOX) { busyboxFile = it }
    val libnvramPicker = pickerFor(AssetSlot.LIBNVRAM) { libnvramFile = it }

    /** Stages a bundled asset if present, otherwise downloads the default. */
    fun acquire(slot: AssetSlot, onDone: (File) -> Unit) {
        scope.launch {
            downloadProgressLabel = "Getting ${slot.label}… 0%"
            try {
                val file = AssetInstaller.ensure(context, slot, config.endianness) { pct ->
                    downloadProgressLabel = "Getting ${slot.label}… $pct%"
                }
                onDone(file)
            } catch (t: Throwable) {
                QemuLogBus.log("[ui] ${slot.label} unavailable: ${t.message}")
            } finally {
                downloadProgressLabel = null
            }
        }
    }

    // Pick up files already staged from a previous session.
    LaunchedEffect(config.endianness) {
        kernelFile = AssetInstaller.resolve(context, AssetSlot.KERNEL, config.endianness)
        diskFile = AssetInstaller.resolve(context, AssetSlot.DISK, config.endianness)
        busyboxFile = AssetInstaller.resolve(context, AssetSlot.BUSYBOX, config.endianness)
        libnvramFile = AssetInstaller.resolve(context, AssetSlot.LIBNVRAM, config.endianness)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Router Firmware Emulator", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        StatusBadge(status)
        Spacer(Modifier.height(16.dp))

        // --- Target device --------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("1. Target device", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = deviceMenuExpanded,
                    onExpandedChange = { deviceMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = config.device.displayName,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text("Device profile") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = deviceMenuExpanded,
                        onDismissRequest = { deviceMenuExpanded = false }
                    ) {
                        DeviceProfiles.all.forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.displayName) },
                                onClick = {
                                    config = EmuConfig.forDevice(profile).copy(
                                        hostPort = config.hostPort,
                                        guestPort = config.guestPort,
                                        useInitramfs = config.useInitramfs,
                                        injectLibnvram = config.injectLibnvram
                                    )
                                    report = null
                                    deviceMenuExpanded = false
                                    QemuLogBus.log(
                                        "[ui] profile ${profile.id}: ${profile.endianness.name}, " +
                                            "cpu=${profile.qemuCpu ?: "default"}, init=${profile.initPath}"
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${config.device.soc} · ${config.device.ramMb} MiB RAM · " +
                        "${config.device.flashMb} MiB flash · ${config.endianness.label}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!config.device.socVerified) {
                    Text(
                        text = "SoC for this profile was not cross-checked — verify before relying on it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100)
                    )
                }
                if (config.device.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(config.device.notes, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- Assets ---------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("2. Kernel, disk & support files", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                AssetRow(
                    label = "Kernel (Malta ${config.endianness.name}, ELF)",
                    file = kernelFile,
                    onPick = { kernelPicker.launch(arrayOf("*/*")) },
                    onAcquire = { acquire(AssetSlot.KERNEL) { kernelFile = it } }
                )
                AssetRow(
                    label = "Root filesystem (raw ext image)",
                    file = diskFile,
                    onPick = { diskPicker.launch(arrayOf("*/*")) },
                    onAcquire = null,
                    hint = "Extract a firmware .bin with binwalk and repack as ext4 — " +
                        "a raw firmware image will not mount."
                )
                AssetRow(
                    label = "BusyBox (${config.endianness.name}) — for the initramfs",
                    file = busyboxFile,
                    onPick = { busyboxPicker.launch(arrayOf("*/*")) },
                    onAcquire = { acquire(AssetSlot.BUSYBOX) { busyboxFile = it } }
                )
                AssetRow(
                    label = "libnvram shim (${config.endianness.name})",
                    file = libnvramFile,
                    onPick = { libnvramPicker.launch(arrayOf("*/*")) },
                    onAcquire = { acquire(AssetSlot.LIBNVRAM) { libnvramFile = it } },
                    hint = "Required for stock vendor firmware: their daemons call " +
                        "nvram_get() and hang without it."
                )

                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(
                        enabled = diskFile != null,
                        onClick = {
                            val target = diskFile ?: return@OutlinedButton
                            scope.launch {
                                downloadProgressLabel = "Scanning image…"
                                try {
                                    val r = withContext(Dispatchers.IO) {
                                        ArchDetector.scanImage(target)
                                    }
                                    report = r
                                    QemuLogBus.log("[ui] image scan: ${r.summary()}")
                                } catch (t: Throwable) {
                                    QemuLogBus.log("[ui] scan failed: ${t.message}")
                                } finally {
                                    downloadProgressLabel = null
                                }
                            }
                        }
                    ) { Text("Detect from image") }
                }

                report?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text(r.summary(), style = MaterialTheme.typography.bodySmall)
                    val detected = r.endianness
                    if (detected != null && detected != config.endianness) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Image looks ${detected.name} but the profile is ${config.endianness.name}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC62828)
                        )
                        OutlinedButton(onClick = {
                            config = config.copy(endianness = detected)
                            QemuLogBus.log("[ui] endianness set to ${detected.name} from image scan")
                        }) { Text("Use ${detected.name}") }
                    }
                    val firstInit = r.initCandidates.firstOrNull()
                    if (firstInit != null && firstInit != config.initPath) {
                        OutlinedButton(onClick = {
                            config = config.copy(initPath = firstInit)
                            QemuLogBus.log("[ui] init path set to $firstInit from image scan")
                        }) { Text("Use init=$firstInit") }
                    }
                }

                downloadProgressLabel?.let {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- Emulation options ---------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("3. Emulation options", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                SwitchRow("Use initramfs", config.useInitramfs) {
                    config = config.copy(useInitramfs = it)
                }
                SwitchRow("Inject libnvram", config.injectLibnvram) {
                    config = config.copy(injectLibnvram = it)
                }

                TextFieldRow("QEMU machine", config.machine) {
                    config = config.copy(machine = it.trim())
                }
                TextFieldRow("CPU model (blank = machine default)", config.cpu.orEmpty()) {
                    config = config.copy(cpu = it.trim().ifBlank { null })
                }
                TextFieldRow("RAM (MiB)", config.ramMb.toString()) { v ->
                    v.trim().toIntOrNull()?.takeIf { it > 0 }?.let {
                        config = config.copy(ramMb = it)
                    }
                }
                TextFieldRow("init= path", config.initPath) {
                    config = config.copy(initPath = it.trim())
                }
                ButtonRow("Network strategy", config.netStrategy.label) {
                    val next = when (config.netStrategy) {
                        NetStrategy.ETH0 -> NetStrategy.BR0
                        NetStrategy.BR0 -> NetStrategy.BOTH
                        NetStrategy.BOTH -> NetStrategy.ETH0
                    }
                    config = config.copy(netStrategy = next)
                }
                TextFieldRow("Host port", config.hostPort.toString()) { v ->
                    v.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                        config = config.copy(hostPort = it)
                    }
                }
                TextFieldRow("Guest port", config.guestPort.toString()) { v ->
                    v.trim().toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                        config = config.copy(guestPort = it)
                    }
                }
                TextFieldRow("Extra kernel cmdline", config.kernelCmdlineExtra) {
                    config = config.copy(kernelCmdlineExtra = it)
                }
                TextFieldRow("Extra QEMU args", config.extraQemuArgs) {
                    config = config.copy(extraQemuArgs = it)
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "-append: " + config.kernelCmdline(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- Boot / stop controls -------------------------------------------
        val canBoot = kernelFile != null && diskFile != null &&
            status != QemuStatus.BOOTING && status != QemuStatus.RUNNING &&
            status != QemuStatus.PREPARING_ASSETS

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = canBoot,
                onClick = {
                    val intent = Intent(context, QemuService::class.java).apply {
                        putExtra(QemuService.EXTRA_KERNEL_PATH, kernelFile!!.absolutePath)
                        putExtra(QemuService.EXTRA_DISK_PATH, diskFile!!.absolutePath)
                        putExtra(QemuService.EXTRA_CONFIG, config)
                    }
                    ContextCompat.startForegroundService(context, intent)
                }
            ) { Text("Boot Firmware") }

            Spacer(Modifier.width(12.dp))

            OutlinedButton(
                enabled = status == QemuStatus.RUNNING || status == QemuStatus.BOOTING,
                onClick = {
                    val intent = Intent(context, QemuService::class.java).apply {
                        action = QemuService.ACTION_STOP
                    }
                    context.startService(intent)
                }
            ) { Text("Stop") }
        }

        Spacer(Modifier.height(16.dp))

        // --- Tabs: Terminal log <-> Router web UI ---------------------------
        var selectedTab by remember { mutableStateOf(0) }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Terminal") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Router UI") })
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
        ) {
            when (selectedTab) {
                0 -> TerminalLogView()
                1 -> RouterWebView(ready = webPortReady, url = webUrl, port = config.hostPort)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: QemuStatus) {
    val (label, color) = when (status) {
        QemuStatus.IDLE -> "Idle" to Color.Gray
        QemuStatus.PREPARING_ASSETS -> "Preparing assets…" to Color(0xFFFFA000)
        QemuStatus.BOOTING -> "Booting…" to Color(0xFFFFA000)
        QemuStatus.RUNNING -> "Running" to Color(0xFF2E7D32)
        QemuStatus.STOPPED -> "Stopped" to Color.Gray
        QemuStatus.ERROR -> "Error" to Color(0xFFC62828)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, shape = CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AssetRow(
    label: String,
    file: File?,
    onPick: () -> Unit,
    onAcquire: (() -> Unit)?,
    hint: String? = null
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = file?.let { "${it.name} (${it.length()} bytes)" } ?: "Not set",
            style = MaterialTheme.typography.bodySmall,
            color = if (file != null) Color(0xFF2E7D32) else Color.Gray
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Row(modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onPick) { Text("Choose file…") }
            if (onAcquire != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onAcquire) { Text("Get default") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Label + current value + a button that cycles to the next option. */
@Composable
private fun ButtonRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onClick) { Text("Change") }
    }
}

@Composable
private fun TextFieldRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}

@Composable
private fun TerminalLogView() {
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        QemuLogBus.logLines.collect { line ->
            lines.add(line)
            if (lines.size > 2000) lines.removeAt(0) // bound memory for very long sessions
            scope.launch { listState.animateScrollToItem(lines.size - 1) }
        }
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(8.dp)
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    text = line,
                    color = Color(0xFF33FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun RouterWebView(ready: Boolean, url: String?, port: Int) {
    val target = url ?: "http://localhost:$port"

    if (!ready) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Waiting for the router web UI on port $port…")
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(target)
            }
        },
        update = { webView ->
            // Re-navigate if the composable recomposes after the emulator restarts.
            if (webView.url == null) {
                webView.loadUrl(target)
            }
        }
    )
}
