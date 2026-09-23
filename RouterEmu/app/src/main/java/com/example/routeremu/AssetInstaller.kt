package com.example.routeremu

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** The five payloads RouterEmu needs beyond the kernel and disk image. */
enum class AssetSlot(val label: String) {
    QEMU("QEMU emulator binary"),
    BUSYBOX("BusyBox for the initramfs"),
    LIBNVRAM("libnvram shim"),
    LIBNVRAM_IOCTL("libnvram ioctl shim"),
    KERNEL("MIPS kernel"),
    DISK("Firmware root filesystem")
}

/**
 * Gets every payload QemuService needs onto local disk.
 *
 * Resolution order for each slot is: **user-picked override in filesDir →
 * bundled asset in the APK → download**. Bundled wins over download so the app
 * works offline; the picker wins over both so a user can substitute their own
 * build without rebuilding the APK.
 */
object AssetInstaller {

    /** Names used both inside `assets/` and in `filesDir`. */
    fun fileName(slot: AssetSlot, e: Endianness): String {
        val be = e == Endianness.BIG_ENDIAN
        return when (slot) {
            AssetSlot.QEMU -> e.qemuBinary
            AssetSlot.BUSYBOX -> if (be) "busybox.mipseb" else "busybox.mipsel"
            AssetSlot.LIBNVRAM -> if (be) "libnvram.so.mipseb" else "libnvram.so.mipsel"
            AssetSlot.LIBNVRAM_IOCTL ->
                if (be) "libnvram_ioctl.so.mipseb" else "libnvram_ioctl.so.mipsel"
            // Kept stable regardless of endianness: these are user-supplied, so
            // the filename carries no meaning the app needs to interpret.
            AssetSlot.KERNEL -> "vmlinux.elf"
            AssetSlot.DISK -> "tplink_root.img"
        }
    }

    /** Slots that must be chmod'ed executable before QEMU will run them. */
    fun needsExec(slot: AssetSlot): Boolean = when (slot) {
        AssetSlot.QEMU, AssetSlot.BUSYBOX -> true
        else -> false
    }

    // Verified reachable on 2026-09-23: each of these returns HTTP 302 to a
    // signed GitHub release asset, while a deliberately bogus filename on the
    // same release returns 404. They come from FirmAE's own download.sh, so the
    // files are the ones FirmAE itself uses.
    private const val FIRMAE_RELEASE =
        "https://github.com/pr0v3rbs/FirmAE/releases/download/v1.0"
    private const val FIRMAE_KERNEL_41 =
        "https://github.com/pr0v3rbs/FirmAE_kernel-v4.1/releases/download/v1.0"

    /**
     * Where the "Download default" button points for a slot, or null when there
     * is nothing legitimate to fetch.
     *
     * QEMU returns null on purpose: there is no redistributable Android build of
     * `qemu-system-mips`, so it has to be cross-compiled with the NDK and
     * bundled (or imported). DISK returns null because firmware images are
     * model-specific and license-sensitive.
     */
    fun defaultUrl(slot: AssetSlot, e: Endianness): String? {
        val be = e == Endianness.BIG_ENDIAN
        return when (slot) {
            AssetSlot.QEMU -> null
            AssetSlot.BUSYBOX ->
                "$FIRMAE_RELEASE/" + if (be) "busybox.mipseb" else "busybox.mipsel"
            AssetSlot.LIBNVRAM ->
                "$FIRMAE_RELEASE/" + if (be) "libnvram.so.mipseb" else "libnvram.so.mipsel"
            AssetSlot.LIBNVRAM_IOCTL ->
                "$FIRMAE_RELEASE/" + if (be) "libnvram_ioctl.so.mipseb" else "libnvram_ioctl.so.mipsel"
            AssetSlot.KERNEL ->
                "$FIRMAE_KERNEL_41/" + if (be) "vmlinux.mipseb.4" else "vmlinux.mipsel.4"
            AssetSlot.DISK -> null
        }
    }

    fun fileFor(context: Context, slot: AssetSlot, e: Endianness): File =
        File(context.filesDir, fileName(slot, e))

    fun defaultKernelFile(context: Context): File =
        File(context.filesDir, fileName(AssetSlot.KERNEL, Endianness.BIG_ENDIAN))

    fun defaultDiskFile(context: Context): File =
        File(context.filesDir, fileName(AssetSlot.DISK, Endianness.BIG_ENDIAN))

    /**
     * Returns the file for [slot] if it is already available (picked or
     * bundled), without touching the network. Null means "not present".
     */
    suspend fun resolve(context: Context, slot: AssetSlot, e: Endianness): File? =
        withContext(Dispatchers.IO) {
            val name = fileName(slot, e)
            val target = File(context.filesDir, name)

            if (target.exists() && target.length() > 0L) {
                if (needsExec(slot)) makeExecutable(target, source = "picked")
                return@withContext target
            }
            if (assetExists(context, name)) {
                return@withContext stageAsset(context, name, target, needsExec(slot))
            }
            null
        }

    /**
     * Like [resolve] but falls back to downloading when nothing is present.
     * Throws if the slot has no download URL and no local copy.
     */
    suspend fun ensure(
        context: Context,
        slot: AssetSlot,
        e: Endianness,
        onProgress: (Int) -> Unit = {}
    ): File {
        resolve(context, slot, e)?.let { return it }

        val name = fileName(slot, e)
        val url = defaultUrl(slot, e) ?: error(
            "${slot.label} is not bundled in the APK and has no download URL. " +
                "Import a file named '$name' with the file picker." +
                if (slot == AssetSlot.QEMU) {
                    " Cross-compile QEMU's mips-softmmu target with the Android " +
                        "NDK (statically linked) for this device's ABI."
                } else ""
        )
        val target = File(context.filesDir, name)
        downloadToFilesDir(url, target, onProgress)
        if (needsExec(slot)) makeExecutable(target, source = "downloaded")
        return target
    }

    private fun assetExists(context: Context, name: String): Boolean = try {
        context.assets.open(name).use { true }
    } catch (_: Throwable) {
        false
    }

    /**
     * Copies a bundled asset out of the APK into filesDir.
     *
     * Uses `assets.open()` and NOT `assets.openFd()`: openFd() throws
     * FileNotFoundException on compressed assets, and AGP compresses every
     * asset whose extension is not on its default no-compress list. The qemu,
     * busybox and libnvram payloads have extensions like `.mipseb` (or none),
     * so openFd() would fail at runtime even though the file is in the APK.
     * `androidResources.noCompress` in build.gradle.kts is the belt to this
     * brace.
     */
    private fun stageAsset(context: Context, name: String, out: File, executable: Boolean): File {
        QemuLogBus.log("[installer] staging bundled asset $name -> ${out.name}")
        context.assets.open(name).use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        if (executable) makeExecutable(out, source = "bundled")
        return out
    }

    private fun makeExecutable(file: File, source: String) {
        val executable = file.setExecutable(true, /* ownerOnly = */ true)
        file.setReadable(true, /* ownerOnly = */ true)
        QemuLogBus.log(
            "[installer] chmod +x ${file.name} ($source): " +
                "executable=$executable canExecute=${file.canExecute()}"
        )
        if (!file.canExecute()) {
            error("Failed to mark ${file.absolutePath} executable — QEMU cannot launch it.")
        }
    }

    /**
     * Streams [downloadUrl] to [destination], reporting 0..100 progress.
     */
    suspend fun downloadToFilesDir(
        downloadUrl: String,
        destination: File,
        onProgress: (percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        QemuLogBus.log("[download] $downloadUrl -> ${destination.name}")
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
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
            QemuLogBus.log("[download] complete: ${destination.name} ($bytesRead bytes)")
        } finally {
            connection.disconnect()
        }
    }

    /** Copies a user-picked SAF Uri into filesDir under [destName]. */
    suspend fun importFromUri(context: Context, uri: Uri, destName: String): File =
        withContext(Dispatchers.IO) {
            val destination = File(context.filesDir, destName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            } ?: error("Could not open picked file: $uri")
            QemuLogBus.log("[installer] imported $uri -> ${destination.name} (${destination.length()} bytes)")
            destination
        }

    /** Deletes a staged copy so the bundled asset is used again on next resolve. */
    suspend fun clear(context: Context, slot: AssetSlot, e: Endianness) =
        withContext(Dispatchers.IO) {
            val f = fileFor(context, slot, e)
            if (f.delete()) QemuLogBus.log("[installer] removed ${f.name}")
        }
}
