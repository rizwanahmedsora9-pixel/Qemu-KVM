package com.example.routeremu

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
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
 * Runs qemu-system-mips as a child OS process for the lifetime of this
 * foreground service. The service itself never touches Compose or an
 * Activity — it just runs the process and publishes state/log lines onto
 * QemuLogBus, so the UI can attach, detach, and reattach freely.
 */
class QemuService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var qemuProcess: Process? = null
    private var supervisorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kernelPath = intent?.getStringExtra(EXTRA_KERNEL_PATH)
        val diskPath = intent?.getStringExtra(EXTRA_DISK_PATH)

        if (intent?.action == ACTION_STOP) {
            stopQemu()
            return START_NOT_STICKY
        }

        if (kernelPath.isNullOrBlank() || diskPath.isNullOrBlank()) {
            QemuLogBus.log("[service] missing kernel/disk path extras — aborting start")
            QemuLogBus.setStatus(QemuStatus.ERROR)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Preparing assets…"))
        launchQemu(kernelPath, diskPath)

        // START_NOT_STICKY: if the system kills this service under memory
        // pressure, we don't want Android silently respawning it with a
        // null intent (we'd have no kernel/disk paths to relaunch with).
        // The user re-taps "Boot" instead, which is the correct recovery.
        return START_NOT_STICKY
    }

    private fun launchQemu(kernelPath: String, diskPath: String) {
        // Guard against double-launch if the UI double-taps Boot.
        if (qemuProcess != null) {
            QemuLogBus.log("[service] QEMU already running, ignoring duplicate start")
            return
        }

        supervisorJob = serviceScope.launch {
            try {
                QemuLogBus.setStatus(QemuStatus.PREPARING_ASSETS)
                val binary = AssetInstaller.ensureQemuBinaryInstalled(applicationContext)

                val args = listOf(
                    binary.absolutePath,
                    "-M", "malta",
                    "-kernel", kernelPath,
                    "-drive", "file=$diskPath,format=raw,index=0,media=disk",
                    "-append", "root=/dev/sda console=ttyS0 init=/sbin/init",
                    "-netdev", "user,id=net0,hostfwd=tcp::$HOST_FWD_PORT-:80",
                    "-device", "e1000,netdev=net0",
                    "-nographic"
                )
                QemuLogBus.log("[service] launching: ${args.joinToString(" ")}")
                QemuLogBus.setStatus(QemuStatus.BOOTING)
                updateNotification("Booting emulated firmware…")

                val process = ProcessBuilder(args)
                    .directory(filesDir)
                    .redirectErrorStream(false) // keep stdout/stderr separate in the log
                    .start()
                qemuProcess = process

                // Two independent reader coroutines so a slow/blocked stderr
                // reader can't starve stdout (and vice versa) — both pipes
                // must be drained concurrently or the child process can
                // block on a full pipe buffer.
                val stdoutJob = launch { pumpStream(process.inputStream, "stdout") }
                val stderrJob = launch { pumpStream(process.errorStream, "stderr") }

                // Watch for the hostfwd port coming up in parallel with the log pump.
                launch {
                    val ready = PortUtils.waitForPortOpen(port = HOST_FWD_PORT)
                    if (ready) {
                        QemuLogBus.log("[service] port $HOST_FWD_PORT is open — router UI should be reachable")
                        QemuLogBus.setStatus(QemuStatus.RUNNING)
                        QemuLogBus.setWebPortReady(true)
                        updateNotification("Router UI running on port $HOST_FWD_PORT")
                    } else {
                        QemuLogBus.log("[service] timed out waiting for port $HOST_FWD_PORT")
                    }
                }

                val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
                stdoutJob.cancel()
                stderrJob.cancel()
                QemuLogBus.log("[service] qemu-system-mips exited with code $exitCode")
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
        const val HOST_FWD_PORT = 8080

        const val EXTRA_KERNEL_PATH = "extra_kernel_path"
        const val EXTRA_DISK_PATH = "extra_disk_path"
        const val ACTION_STOP = "com.example.routeremu.action.STOP"
    }
}
