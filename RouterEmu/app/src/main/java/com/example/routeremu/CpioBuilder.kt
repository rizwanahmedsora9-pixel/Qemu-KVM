package com.example.routeremu

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Writes SVR4 "newc" cpio archives — the format the Linux kernel's initramfs
 * unpacker expects.
 *
 * Implemented in-process rather than by shelling out because an Android app has
 * no `cpio`, no `find`, and no root with which to `mount` an ext image. That is
 * exactly why the archive is built here: generating an initramfs is the only
 * way to get libnvram into the guest without mutating the disk image.
 *
 * Record layout:
 * ```
 *   6 bytes   magic "070701"
 *  104 bytes  13 x 8-char uppercase ASCII hex fields, in order:
 *             ino mode uid gid nlink mtime filesize
 *             devmajor devminor rdevmajor rdevminor namesize check
 *   name      + one NUL byte (counted in namesize)
 *   padding   so header+name is a multiple of 4
 *   data      file contents
 *   padding   so data is a multiple of 4
 * ```
 * The archive ends with an entry named `TRAILER!!!` of size 0.
 */
object CpioBuilder {

    private const val MAGIC = "070701"
    private const val TRAILER = "TRAILER!!!"
    private const val HEADER_LEN = 110
    private const val FIELD_COUNT = 13

    // c_mode values. The kernel unpacker checks the file-type nibble, so these
    // must be the real S_IF* bits OR'ed with the permission bits.
    const val DIR: Int = 0x41ED      // S_IFDIR | 0755
    const val REG_644: Int = 0x81A4  // S_IFREG | 0644
    const val REG_755: Int = 0x81ED  // S_IFREG | 0755
    const val SYMLINK: Int = 0xA1FF  // S_IFLNK | 0777

    class Entry(val name: String, val mode: Int, val data: ByteArray)

    fun file(name: String, data: ByteArray, executable: Boolean = false): Entry =
        Entry(name, if (executable) REG_755 else REG_644, data)

    fun text(name: String, content: String, executable: Boolean = false): Entry =
        Entry(name, if (executable) REG_755 else REG_644, content.toByteArray(Charsets.UTF_8))

    fun dir(name: String): Entry = Entry(name, DIR, ByteArray(0))

    /** `data` holds the link target path, as the cpio format requires. */
    fun symlink(name: String, target: String): Entry =
        Entry(name, SYMLINK, target.toByteArray(Charsets.US_ASCII))

    /**
     * Serialises [entries] to a newc archive. Parent directories are NOT
     * synthesised — callers must add explicit [dir] entries, because the kernel
     * unpacker creates files strictly in archive order.
     */
    fun newc(entries: List<Entry>, padToBlock: Int = 512): ByteArray {
        require(entries.none { it.name == TRAILER }) { "caller must not add the trailer" }
        val out = ByteArrayOutputStream()
        // Arbitrary but unique-looking starting inode; the unpacker only needs
        // hardlink groups to share an inode, and we emit none.
        var ino = 0x300000
        for (e in entries) {
            writeEntry(out, ino++, e)
        }
        writeEntry(out, ino, Entry(TRAILER, 0, ByteArray(0)))
        pad(out, padToBlock)
        return out.toByteArray()
    }

    private fun writeEntry(out: ByteArrayOutputStream, ino: Int, e: Entry) {
        val nameBytes = e.name.toByteArray(Charsets.US_ASCII)
        // namesize counts the NUL terminator.
        val nameSize = nameBytes.size + 1

        val header = StringBuilder(HEADER_LEN)
        header.append(MAGIC)
        appendField(header, ino)
        appendField(header, e.mode)
        appendField(header, 0)               // uid
        appendField(header, 0)               // gid
        appendField(header, 1)               // nlink
        appendField(header, 0)               // mtime
        appendField(header, e.data.size)     // filesize
        appendField(header, 0)               // devmajor
        appendField(header, 0)               // devminor
        appendField(header, 0)               // rdevmajor
        appendField(header, 0)               // rdevminor
        appendField(header, nameSize)
        appendField(header, 0)               // check
        check(header.length == HEADER_LEN) {
            "cpio header must be exactly $HEADER_LEN bytes, got ${header.length}"
        }

        out.write(header.toString().toByteArray(Charsets.US_ASCII))
        out.write(nameBytes)
        out.write(0)
        pad(out, 4, written = HEADER_LEN + nameSize)
        out.write(e.data)
        pad(out, 4, written = e.data.size)
    }

    /**
     * Appends [value] as 8 uppercase hex digits. Locale is pinned to US:
     * `String.format` digit substitution is locale-sensitive, and a device set
     * to a locale with non-ASCII digits would emit an archive the kernel
     * rejects.
     */
    private fun appendField(sb: StringBuilder, value: Int) {
        sb.append(String.format(Locale.US, "%08X", value))
    }

    /** Pads [out] with NULs to the next multiple of [block]. */
    private fun pad(out: ByteArrayOutputStream, block: Int, written: Int = out.size()) {
        val remainder = written % block
        if (remainder != 0) {
            repeat(block - remainder) { out.write(0) }
        }
    }

    /** Sanity helper for tests/logging: field count implied by the magic. */
    fun fieldCount(): Int = FIELD_COUNT
}
