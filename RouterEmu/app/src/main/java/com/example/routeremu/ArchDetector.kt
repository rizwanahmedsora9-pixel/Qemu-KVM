package com.example.routeremu

import java.io.File
import java.io.InputStream

/** One ELF header found in a file. */
data class ElfHeader(
    val bits: Int,
    val bigEndian: Boolean,
    val machine: Int,
    val offset: Long
) {
    val machineName: String
        get() = when (machine) {
            EM_MIPS -> "MIPS"
            EM_ARM -> "ARM"
            EM_AARCH64 -> "AArch64"
            EM_386 -> "x86"
            EM_X86_64 -> "x86-64"
            EM_PPC -> "PowerPC"
            else -> "machine=$machine"
        }

    val endianness: Endianness
        get() = if (bigEndian) Endianness.BIG_ENDIAN else Endianness.LITTLE_ENDIAN

    override fun toString(): String =
        "$machineName ${bits}-bit ${endianness.name.lowercase()} @0x${offset.toString(16)}"

    companion object {
        const val EM_386 = 3
        const val EM_MIPS = 8
        const val EM_PPC = 20
        const val EM_ARM = 40
        const val EM_X86_64 = 62
        const val EM_AARCH64 = 183
    }
}

/**
 * What we could infer about a firmware image without mounting it.
 *
 * An Android app has no `file`, no `readelf`, no `binwalk` and no root, so this
 * is a byte scan: it finds ELF headers to establish ISA + endianness, and looks
 * for the path strings a firmware's init might live at. It is a hint for the
 * UI, never a hard requirement — the user can always override.
 */
data class ImageReport(
    val elfs: List<ElfHeader>,
    val endianness: Endianness?,
    val machine: Int?,
    val initCandidates: List<String>,
    val markers: List<String>,
    val bytesScanned: Long,
    val truncated: Boolean
) {
    fun summary(): String = buildString {
        append(endianness?.label ?: "endianness: unknown")
        machine?.let { append(", machine ").append(elfMachineName(it)) }
        append("; ").append(elfs.size).append(" ELF header(s)")
        if (initCandidates.isNotEmpty()) {
            append("; init: ").append(initCandidates.joinToString(", "))
        }
        if (markers.isNotEmpty()) {
            append("; markers: ").append(markers.joinToString(", "))
        }
        if (truncated) append(" (scan capped)")
    }

    private fun elfMachineName(m: Int): String =
        ElfHeader(32, true, m, 0).machineName
}

object ArchDetector {

    private const val CHUNK = 64 * 1024
    private const val MAX_ELF_SAMPLES = 24
    private const val DEFAULT_SCAN_LIMIT = 48L * 1024 * 1024

    private val INIT_NEEDLES = listOf(
        "/sbin/init", "/bin/init", "/etc/preinit",
        "/etc/init.d/rcS", "/etc/rc.d/rcS", "/sbin/procd", "/linuxrc"
    )

    private val MARKER_NEEDLES = listOf(
        "OpenWrt", "BusyBox", "libnvram", "nvram_get", "nvram_set",
        "uhttpd", "lighttpd", "mini_httpd", "goahead", "boa",
        "TP-LINK", "Atheros", "squashfs", "crAMFS"
    )

    /**
     * Reads the ELF header at offset 0. Returns null for raw or compressed
     * kernels, which is normal — QEMU's malta loader accepts both ELF and raw
     * binary images.
     */
    fun sniffKernel(file: File): ElfHeader? {
        if (!file.exists()) return null
        return file.inputStream().use { input ->
            val head = readExactly(input, 64) ?: return@use null
            parseElfAt(head, 0)
        }
    }

    /** True if [file] starts with the gzip magic. */
    fun isGzip(file: File): Boolean {
        if (!file.exists()) return false
        return file.inputStream().use { input ->
            val a = input.read()
            val b = input.read()
            a == 0x1F && b == 0x8B
        }
    }

    fun scanImage(
        file: File,
        scanLimit: Long = DEFAULT_SCAN_LIMIT,
        onProgress: (Int) -> Unit = {}
    ): ImageReport {
        val elfs = ArrayList<ElfHeader>()
        val inits = LinkedHashSet<String>()
        val markers = LinkedHashSet<String>()
        val needles = INIT_NEEDLES + MARKER_NEEDLES

        val limit = minOf(file.length(), scanLimit)
        var scanned = 0L
        var tail = ByteArray(0)
        // Needle matches can straddle a chunk boundary, so carry a small tail
        // of the previous window forward.
        val overlap = needles.maxOf { it.length } + 8

        file.inputStream().buffered(CHUNK).use { input ->
            while (scanned < limit) {
                val chunk = readExactly(input, CHUNK) ?: break
                if (chunk.isEmpty()) break

                val window = if (tail.isEmpty()) chunk else tail + chunk
                val windowBase = scanned - tail.size

                if (elfs.size < MAX_ELF_SAMPLES) {
                    var i = 0
                    while (i + 20 <= window.size) {
                        if (isElfMagic(window, i)) {
                            parseElfAt(window, i)?.let { e ->
                                elfs.add(e.copy(offset = windowBase + i))
                            }
                            i += 64 // skip the header we just read
                        } else {
                            i++
                        }
                    }
                }

                // Decode as ISO-8859-1 so every byte maps 1:1 to a char and
                // String.indexOf gets to do the searching — a naive per-needle
                // byte loop over 48 MiB is far too slow.
                val text = String(window, Charsets.ISO_8859_1)
                for (n in needles) {
                    if (text.contains(n)) {
                        if (INIT_NEEDLES.contains(n)) inits.add(n) else markers.add(n)
                    }
                }

                scanned += chunk.size
                onProgress(((scanned * 100) / limit.coerceAtLeast(1)).toInt().coerceIn(0, 100))

                tail = if (window.size > overlap) {
                    window.copyOfRange(window.size - overlap, window.size)
                } else {
                    window
                }
            }
        }

        val endianness = elfs.groupingBy { it.endianness }.eachCount()
            .maxByOrNull { it.value }?.key
        val machine = elfs.groupingBy { it.machine }.eachCount()
            .maxByOrNull { it.value }?.key

        return ImageReport(
            elfs = elfs,
            endianness = endianness,
            machine = machine,
            initCandidates = inits.toList(),
            markers = markers.toList(),
            bytesScanned = scanned,
            truncated = scanned >= limit && file.length() > limit
        )
    }

    private fun isElfMagic(buf: ByteArray, i: Int): Boolean =
        buf[i] == 0x7F.toByte() &&
            buf[i + 1] == 'E'.code.toByte() &&
            buf[i + 2] == 'L'.code.toByte() &&
            buf[i + 3] == 'F'.code.toByte()

    private fun parseElfAt(buf: ByteArray, i: Int): ElfHeader? {
        if (i + 20 > buf.size) return null
        if (!isElfMagic(buf, i)) return null
        val eiClass = buf[i + 4].toInt() and 0xFF
        val eiData = buf[i + 5].toInt() and 0xFF
        // 1 = ELFCLASS32, 2 = ELFCLASS64; 1 = ELFDATA2LSB, 2 = ELFDATA2MSB.
        if (eiClass !in 1..2 || eiData !in 1..2) return null
        val bigEndian = eiData == 2
        return ElfHeader(
            bits = if (eiClass == 2) 64 else 32,
            bigEndian = bigEndian,
            machine = u16(buf, i + 18, bigEndian),
            offset = 0L
        )
    }

    private fun u16(buf: ByteArray, off: Int, bigEndian: Boolean): Int {
        val b0 = buf[off].toInt() and 0xFF
        val b1 = buf[off + 1].toInt() and 0xFF
        return if (bigEndian) (b0 shl 8) or b1 else (b1 shl 8) or b0
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) return if (off == 0) null else buf.copyOf(off)
            off += r
        }
        return buf
    }
}
