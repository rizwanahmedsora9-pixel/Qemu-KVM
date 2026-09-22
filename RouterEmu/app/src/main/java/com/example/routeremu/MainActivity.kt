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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
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

@Composable
fun RouterEmuScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kernelFile by remember { mutableStateOf<File?>(null) }
    var diskFile by remember { mutableStateOf<File?>(null) }
    var downloadProgressLabel by remember { mutableStateOf<String?>(null) }

    val status by QemuLogBus.status.collectAsState()
    val webPortReady by QemuLogBus.webPortReady.collectAsState()

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
    val kernelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                kernelFile = AssetInstaller.importFromUri(context, uri, "vmlinux.elf")
            }
        }
    }
    val diskPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                diskFile = AssetInstaller.importFromUri(context, uri, "tplink_root.img")
            }
        }
    }

    // Pick up files that already exist in filesDir from a previous session/download.
    LaunchedEffect(Unit) {
        AssetInstaller.defaultKernelFile(context).let { if (it.exists()) kernelFile = it }
        AssetInstaller.defaultDiskFile(context).let { if (it.exists()) diskFile = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Router Firmware Emulator", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        StatusBadge(status)
        Spacer(Modifier.height(16.dp))

        // --- Asset selection ------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("1. Kernel & Disk Image", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                AssetRow(
                    label = "Kernel (vmlinux.elf)",
                    file = kernelFile,
                    onPick = { kernelPicker.launch(arrayOf("*/*")) },
                    onDownload = {
                        scope.launch {
                            downloadProgressLabel = "Downloading kernel… 0%"
                            try {
                                val dest = AssetInstaller.defaultKernelFile(context)
                                AssetInstaller.downloadToFilesDir(
                                    downloadUrl = DEFAULT_KERNEL_URL,
                                    destination = dest,
                                    onProgress = { pct -> downloadProgressLabel = "Downloading kernel… $pct%" }
                                )
                                kernelFile = dest
                            } catch (t: Throwable) {
                                QemuLogBus.log("[ui] kernel download failed: ${t.message}")
                            } finally {
                                downloadProgressLabel = null
                            }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
                AssetRow(
                    label = "Disk image (tplink_root.img)",
                    file = diskFile,
                    onPick = { diskPicker.launch(arrayOf("*/*")) },
                    onDownload = {
                        scope.launch {
                            downloadProgressLabel = "Downloading disk image… 0%"
                            try {
                                val dest = AssetInstaller.defaultDiskFile(context)
                                AssetInstaller.downloadToFilesDir(
                                    downloadUrl = DEFAULT_DISK_URL,
                                    destination = dest,
                                    onProgress = { pct -> downloadProgressLabel = "Downloading disk image… $pct%" }
                                )
                                diskFile = dest
                            } catch (t: Throwable) {
                                QemuLogBus.log("[ui] disk download failed: ${t.message}")
                            } finally {
                                downloadProgressLabel = null
                            }
                        }
                    }
                )

                downloadProgressLabel?.let {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- Boot / stop controls --------------------------------------------
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

        // --- Tabs: Terminal log <-> Router web UI ----------------------------
        var selectedTab by remember { mutableStateOf(0) }
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Terminal") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Router UI") })
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (selectedTab) {
                0 -> TerminalLogView()
                1 -> RouterWebView(ready = webPortReady)
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
                .background(color, shape = androidx.compose.foundation.shape.CircleShape)
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
    onDownload: () -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = file?.absolutePath ?: "Not set",
            style = MaterialTheme.typography.bodySmall,
            color = if (file != null) Color(0xFF2E7D32) else Color.Gray
        )
        Row(modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onPick) { Text("Choose file…") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onDownload) { Text("Download default") }
        }
    }
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
private fun RouterWebView(ready: Boolean) {
    if (!ready) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Waiting for the router web UI on port ${QemuService.HOST_FWD_PORT}…")
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
                loadUrl("http://localhost:${QemuService.HOST_FWD_PORT}")
            }
        },
        update = { webView ->
            // Re-navigate if the composable recomposes after the emulator restarts.
            if (webView.url == null) {
                webView.loadUrl("http://localhost:${QemuService.HOST_FWD_PORT}")
            }
        }
    )
}

// Replace these with real hosts you control before shipping — QEMU needs a
// MIPS "malta"-machine kernel and a raw ext-formatted root filesystem image
// extracted from the target firmware (e.g. via binwalk/firmware-mod-kit).
private const val DEFAULT_KERNEL_URL = "https://example.com/router-emu/vmlinux.elf"
private const val DEFAULT_DISK_URL = "https://example.com/router-emu/tplink_root.img"
