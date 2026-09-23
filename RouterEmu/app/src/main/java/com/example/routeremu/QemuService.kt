package com.example.routeremu

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Runs qemu-system-mips(el) as a child OS process for the lifetime of this
 * foreground service. The service itself never touches Compose or an Activity —
 * it assembles the command line, runs the process, and publishes state/log
 * lines onto QemuLogBus so the UI can attach, detach and reattach freely.
 */
class QemuService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var qemuProcess: Process? = null
    private var supervisorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION") // getSerializableExtra: fine on every API we support
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopQemu()
            return START_NOT_STICKY
        }

        val kernelPath = intent?.getStringExtra(EXTRA_KERNEL_PATH)
        val diskPath = intent?.getStringExtra(EXTRA_DISK_PATH)
        val config = intent?.getSerializableExtra(EXTRA_CONFIG) as? EmuConfig

        if (kernelPath.isNullOrBlank() || diskPath.isNullOrBlank() || config == null) {
            QemuLogBus.log("[service] missing kernel/disk/config extras — aborting start")
            QemuLogBus.setStatus(QemuStatus.ERROR)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Preparing assets…"))
        launchQemu(kernelPath, diskPath, config)

        // START_NOT_STICKY: if the system kills this service under memory
        // pressure, we don't want Android silently respawning it with a null
        // intent (we'd have no kernel/disk/config to relaunch with). The user
        // re-taps "Boot" instead, which is the correct recovery.
        return START_NOT_STICKY
    }

    private fun launchQemu(kernelPath: String, diskPath: String, config: EmuConfig) {
        // Guard against double-launch if the UI double-taps Boot.
        if (qemuProcess != null) {
            QemuLogBus.log("[service] QEMU already running, ignoring duplicate start")
            return
        }

        supervisorJob = serviceScope.launch {
            try {
                QemuLogBus.setStatus(QemuStatus.PREPARING_ASSETS)
                updateNotification("Preparing assets…")

                val binary = AssetInstaller.ensure(
                    applicationContext, AssetSlot.QEMU, config.endianness
                )
                QemuLogBus.log(
                    "[service] device=${config.device.label} " +
                        "endianness=${config.endianness.name} cpu=${config.cpu ?: "default"}"
                )

                val initrd = buildInitramfsOrNull(config)

                val args = buildArgs(binary, kernelPath, diskPath, initrd, config)
                QemuLogBus.log("[service] launching: ${args.joinToString(" ")}")
                QemuLogBus.setStatus(QemuStatus.BOOTING)
                updateNotification("Booting ${config.device.label}…")

                val process = ProcessBuilder(args)
                    .directory(filesDir)
                    .redirectErrorStream(false) // keep stdout/stderr separate in the log
                    .start()
                qemuProcess = process

                // Two independent reader coroutines so a slow/blocked stderr
                // reader can't starve stdout (and vice versa) — both pipes must
                // be drained concurrently or the child can block on a full pipe.
                val stdoutJob = launch { pumpStream(process.inputStream, "stdout") }
                val stderrJob = launch { pumpStream(process.errorStream, "stderr") }

                // Watch for the hostfwd port coming up in parallel with the log pump.
                launch {
                    val ready = PortUtils.waitForPortOpen(port = config.hostPort)
                    if (ready) {
                        QemuLogBus.log("[service] port ${config.hostPort} is open — router UI should be reachable")
                        QemuLogBus.setStatus(QemuStatus.RUNNING)
                        QemuLogBus.setWebPortReady(true)
                        QemuLogBus.setWebUrl("http://localhost:${config.hostPort}")
                        updateNotification("Router UI running on port ${config.hostPort}")
                    } else {
                        QemuLogBus.log("[service] timed out waiting for port ${config.hostPort}")
                    }
                }

                val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
                stdoutJob.cancel()
                stderrJob.cancel()
                QemuLogBus.log("[service] qemu exited with code $exitCode")
                QemuLogBus.setStatus(if (exitCode == 0) QemuStatus.STOPPED else QemuStatus.ERROR)
            } catch (t: Throwable) {
                QemuLogBus.log("[service] error: ${t.message}")
                QemuLogBus.setStatus(QemuStatus.ERROR)
            } finally {
                qemuProcess = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Assembles the initramfs that injects libnvram and brings up networking.
     *
     * Failures here are deliberately non-fatal: a missing busybox or libnvram
     * downgrades to a plain boot, which still works for firmwares that do not
     * depend on nvram. The log says so loudly rather than failing silently.
     */
    private suspend fun buildInitramfsOrNull(config: EmuConfig): File? {
        if (!config.useInitramfs) {
            QemuLogBus.log("[service] initramfs disabled by config — booting firmware init directly")
            return null
        }
        return try {
            withContext(Dispatchers.IO) {
                val busybox = AssetInstaller.resolve(
                    applicationContext, AssetSlot.BUSYBOX, config.endianness
                )
                if (busybox == null) {
                    QemuLogBus.log(
                        "[service] no busybox for ${config.endianness.name} — " +
                            "skipping initramfs (no libnvram injection, no network bring-up)"
                    )
                    return@withContext null
                }
                val libnvram = if (config.injectLibnvram) {
                    AssetInstaller.resolve(
                        applicationContext, AssetSlot.LIBNVRAM, config.endianness
                    ).also {
                        if (it == null) {
                            QemuLogBus.log(
                                "[service] WARNING: no libnvram for ${config.endianness.name}; " +
                                    "nvram_get() calls in the firmware will fail"
                            )
                        }
                    }
                } else null
                val libnvramIoctl = AssetInstaller.resolve(
                    applicationContext, AssetSlot.LIBNVRAM_IOCTL, config.endianness
                )

                InitramfsBuilder.build(
                    busybox = busybox,
                    libnvram = libnvram,
                    libnvramIoctl = libnvramIoctl,
                    config = config,
                    outFile = File(filesDir, INITRD_NAME)
                )
            }
        } catch (t: Throwable) {
            QemuLogBus.log("[service] initramfs build failed (${t.message}) — continuing without it")
            null
        }
    }

    /**
     * The full QEMU command line. Every element that used to be a literal here
     * now comes from [config], because stock firmware differs per model in
     * endianness, CPU core, RAM size and init path.
     */
    private fun buildArgs(
        binary: File,
        kernelPath: String,
        diskPath: String,
        initrd: File?,
        config: EmuConfig
    ): List<String> = buildList {
        add(binary.absolutePath)
        add("-M"); add(config.machine)
        config.cpu?.takeIf { it.isNotBlank() }?.let { add("-cpu"); add(it) }
        add("-m"); add(config.ramMb.toString())
        add("-kernel"); add(kernelPath)
        if (initrd != null) { add("-initrd"); add(initrd.absolutePath) }
        add("-drive"); add("file=$diskPath,format=raw,index=0,media=disk")
        add("-append"); add(config.kernelCmdline())
        add("-netdev")
        add("user,id=net0,hostfwd=tcp::${config.hostPort}-:${config.guestPort}")
        add("-device"); add("e1000,netdev=net0")
        addAll(config.extraArgsList())
        add("-nographic")
    }

    private suspend fun pumpStream(stream: java.io.InputStream, label: String) =
        withContext(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        QemuLogBus.log("[$label] $line")
                    }
                }
            } catch (_: Exception) {
                // Stream closes when the process dies — expected, not an error.
            }
        }

    private fun stopQemu() {
        QemuLogBus.log("[service] stop requested")
        qemuProcess?.destroy()
        supervisorJob?.cancel()
        qemuProcess = null
        QemuLogBus.setStatus(QemuStatus.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        qemuProcess?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, QemuService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.qemu_notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "qemu_service_channel"
        const val NOTIFICATION_ID = 42
        const val DEFAULT_HOST_PORT = 8080
        private const val INITRD_NAME = "routeremu-initrd.gz"

        const val EXTRA_KERNEL_PATH = "extra_kernel_path"
        const val EXTRA_DISK_PATH = "extra_disk_path"
        const val EXTRA_CONFIG = "extra_config"
        const val ACTION_STOP = "com.example.routeremu.action.STOP"
    }
}
