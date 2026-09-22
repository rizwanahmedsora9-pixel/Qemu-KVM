package com.example.routeremu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles getting the three things QemuService needs onto local disk:
 *   1. The qemu-system-mips executable (bundled in assets/, copied to
 *      filesDir once and chmod'ed +x — assets/ itself is inside the APK's
 *      read-only zip and can't be exec'd in place).
 *   2. The MIPS kernel (vmlinux.elf), either picked by the user via SAF or
 *      downloaded to filesDir.
 *   3. The extracted firmware root disk image (tplink_root.img), same deal.
 */
object AssetInstaller {

    private const val QEMU_BINARY_ASSET_NAME = "qemu-system-mips"

    fun qemuBinaryFile(context: Context): File =
        File(context.filesDir, QEMU_BINARY_ASSET_NAME)

    fun defaultKernelFile(context: Context): File =
        File(context.filesDir, "vmlinux.elf")

    fun defaultDiskFile(context: Context): File =
        File(context.filesDir, "tplink_root.img")

    /**
     * Copies qemu-system-mips out of assets/ into filesDir and marks it
     * executable. Safe to call every boot — skips the copy if a file of the
     * same size is already staged, but always re-asserts the exec bit since
     * some OEM filesystems / backup-restore flows silently drop it.
     */
    suspend fun ensureQemuBinaryInstalled(context: Context): File = withContext(Dispatchers.IO) {
        val outFile = qemuBinaryFile(context)
        val assetManager = context.assets

        val assetSize = assetManager.openFd(QEMU_BINARY_ASSET_NAME).use { it.length }
        val needsCopy = !outFile.exists() || outFile.length() != assetSize

        if (needsCopy) {
            QemuLogBus.log("[installer] staging $QEMU_BINARY_ASSET_NAME -> ${outFile.absolutePath}")
            assetManager.open(QEMU_BINARY_ASSET_NAME).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        val executable = outFile.setExecutable(true, /* ownerOnly = */ true)
        val readable = outFile.setReadable(true, true)
        QemuLogBus.log("[installer] chmod +x ${outFile.name}: executable=$executable readable=$readable")

        if (!outFile.canExecute()) {
            error("Failed to mark ${outFile.absolutePath} executable — QEMU cannot be launched.")
        }
        outFile
    }

    /**
     * Streams [downloadUrl] to [destination] inside filesDir, reporting
     * 0..100 progress via [onProgress]. Used for the "download default
     * assets" path as an alternative to the user picking their own files.
     */
    suspend fun downloadToFilesDir(
        downloadUrl: String,
        destination: File,
        onProgress: (percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        QemuLogBus.log("[download] $downloadUrl -> ${destination.name}")
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Download failed: HTTP ${connection.responseCode} for $downloadUrl")
            }
            val totalBytes = connection.contentLengthLong
            var bytesRead = 0L

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        bytesRead += n
                        if (totalBytes > 0) {
                            onProgress(((bytesRead * 100) / totalBytes).toInt())
                        }
                    }
                }
            }
            onProgress(100)
            QemuLogBus.log("[download] complete: ${destination.name} (${bytesRead} bytes)")
        } finally {
            connection.disconnect()
        }
    }

    /** Copies content from a user-picked SAF Uri into filesDir under [destName]. */
    suspend fun importFromUri(context: Context, uri: android.net.Uri, destName: String): File =
        withContext(Dispatchers.IO) {
            val destination = File(context.filesDir, destName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            } ?: error("Could not open picked file: $uri")
            QemuLogBus.log("[installer] imported $uri -> ${destination.name}")
            destination
        }
}
