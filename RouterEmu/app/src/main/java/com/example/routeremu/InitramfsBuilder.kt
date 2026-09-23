package com.example.routeremu

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Builds the initramfs that makes stock vendor firmware bootable.
 *
 * Stock firmware needs three things QEMU cannot provide on its own:
 *  1. **libnvram** — vendor daemons call `nvram_get()`/`nvram_set()` during
 *     startup and hang or abort when the call is unresolved. Firmadyne/FirmAE
 *     solve this with a shim `.so` loaded via `LD_PRELOAD`.
 *  2. **A configured NIC** — QEMU hands the guest an `e1000` that nobody
 *     brings up, so `hostfwd` forwards to a dead interface.
 *  3. **A writable place to put the shim** — an Android app cannot `mount` the
 *     ext rootfs image (no root), so the shim cannot be copied in ahead of time.
 *
 * The initramfs sidesteps (3) entirely: the kernel mounts it as rootfs first,
 * our `/init` mounts the real root underneath at `/new_root`, copies the shim
 * in, configures networking, and `switch_root`s into the firmware with
 * `LD_PRELOAD` still exported. No root, no image mutation, no host tooling.
 */
object InitramfsBuilder {

    private const val SLIRP_GUEST_IP = "10.0.2.15"
    private const val SLIRP_NETMASK = "255.255.255.0"
    private const val SLIRP_GATEWAY = "10.0.2.2"
    private const val WATCHDOG_INTERVAL_SECONDS = 2

    private val ROOT_CANDIDATES = listOf(
        "/dev/sda", "/dev/sda1", "/dev/sda2",
        "/dev/hda", "/dev/hda1",
        "/dev/vda", "/dev/vda1",
        "/dev/xvda", "/dev/xvda1"
    )

    /**
     * Writes a gzipped newc cpio to [outFile] and returns it.
     *
     * @param busybox a statically-linked MIPS busybox matching [config.endianness]
     * @param libnvram the libnvram shim, or null to skip injection
     */
    fun build(
        busybox: File,
        libnvram: File?,
        libnvramIoctl: File?,
        config: EmuConfig,
        outFile: File
    ): File {
        require(busybox.exists()) { "busybox asset missing: ${busybox.absolutePath}" }

        val entries = ArrayList<CpioBuilder.Entry>()

        // The kernel unpacker creates entries in archive order, so directories
        // must precede their contents.
        for (d in listOf(
            "/bin", "/sbin", "/lib", "/proc", "/sys", "/dev", "/dev/pts",
            "/new_root", "/routeremu", "/tmp", "/run", "/etc"
        )) {
            entries.add(CpioBuilder.dir(d))
        }

        entries.add(CpioBuilder.file("/bin/busybox", busybox.readBytes(), executable = true))

        if (libnvram != null && libnvram.exists()) {
            entries.add(CpioBuilder.file("/routeremu/libnvram.so", libnvram.readBytes()))
        }
        if (libnvramIoctl != null && libnvramIoctl.exists()) {
            entries.add(CpioBuilder.file("/routeremu/libnvram_ioctl.so", libnvramIoctl.readBytes()))
        }

        entries.add(CpioBuilder.text("/init", initScript(config), executable = true))

        val cpio = CpioBuilder.newc(entries)
        val gz = ByteArrayOutputStream(cpio.size / 2)
        GZIPOutputStream(gz).use { it.write(cpio) }
        outFile.writeBytes(gz.toByteArray())

        QemuLogBus.log(
            "[initramfs] wrote ${outFile.name}: cpio=${cpio.size}B " +
                "gz=${outFile.length()}B nvram=${libnvram?.name ?: "none"} " +
                "net=${config.netStrategy} watchdog=${config.netWatchdogSeconds}s"
        )
        return outFile
    }

    /**
     * The `/init` script. Placeholders are `%TOKEN%` rather than `${...}` so the
     * shell's own parameter expansion survives Kotlin string templating.
     */
    fun initScript(config: EmuConfig): String {
        val watchdogIters =
            if (config.netWatchdogSeconds <= 0) 0
            else config.netWatchdogSeconds / WATCHDOG_INTERVAL_SECONDS

        // Block substitutions must run FIRST: the blocks they insert contain
        // %GUEST_IP%/%NETMASK%/%GATEWAY% of their own, so the scalar pass has to
        // come afterwards or those tokens would survive into the emitted script.
        var script = INIT_TEMPLATE
        script = script.replace("%NVRAM_BLOCK%", nvramBlock(config))
        script = script.replace("%NET_BLOCK%", netBlock(config))
        script = script.replace("%WATCHDOG_BLOCK%", watchdogBlock(watchdogIters))

        script = script.replace("%DEVICE%", config.device.label)
        script = script.replace("%SOC%", config.device.soc)
        script = script.replace("%ROOT_CANDIDATES%", ROOT_CANDIDATES.joinToString(" "))
        script = script.replace("%INIT_PATH%", config.initPath.ifBlank { "/sbin/init" })
        script = script.replace("%GUEST_IP%", SLIRP_GUEST_IP)
        script = script.replace("%NETMASK%", SLIRP_NETMASK)
        script = script.replace("%GATEWAY%", SLIRP_GATEWAY)

        // Guard against the placeholder-ordering bug this function is prone to:
        // if any %TOKEN% survived, the emitted /init would be broken shell.
        val leftover = Regex("%[A-Z_]+%").findAll(script).map { it.value }.toSet()
        if (leftover.isNotEmpty()) {
            QemuLogBus.log("[initramfs] WARNING: unsubstituted placeholders $leftover")
        }
        return script
    }

    private fun nvramBlock(config: EmuConfig): String {
        if (!config.injectLibnvram) {
            return "say 'libnvram injection disabled by config'"
        }
        return """
            |if [ -f /routeremu/libnvram.so ]; then
            |  say 'injecting libnvram into the firmware rootfs'
            |  mkdir -p /new_root/lib /new_root/usr/lib
            |  cp -f /routeremu/libnvram.so /new_root/lib/libnvram.so && chmod 755 /new_root/lib/libnvram.so
            |  cp -f /routeremu/libnvram.so /new_root/usr/lib/libnvram.so 2>/dev/null
            |  if [ -f /routeremu/libnvram_ioctl.so ]; then
            |    cp -f /routeremu/libnvram_ioctl.so /new_root/lib/libnvram_ioctl.so 2>/dev/null
            |    chmod 755 /new_root/lib/libnvram_ioctl.so 2>/dev/null
            |  fi
            |  export LD_PRELOAD=/lib/libnvram.so
            |else
            |  say 'WARNING: no libnvram.so staged - nvram_get() calls will fail'
            |fi
        """.trimMargin()
    }

    private fun netBlock(config: EmuConfig): String {
        val header = """
            |say 'network: ${config.netStrategy.label}'
            |ifconfig lo 127.0.0.1 up 2>/dev/null
        """.trimMargin()

        val body = when (config.netStrategy) {
            NetStrategy.ETH0 -> """
                |ifconfig eth0 %GUEST_IP% netmask %NETMASK% up 2>/dev/null
                |route add default gw %GATEWAY% 2>/dev/null
            """.trimMargin()

            NetStrategy.BR0 -> """
                |ifconfig eth0 0.0.0.0 up 2>/dev/null
                |brctl addbr br0 2>/dev/null
                |brctl addif br0 eth0 2>/dev/null
                |ifconfig br0 %GUEST_IP% netmask %NETMASK% up 2>/dev/null
                |route add default gw %GATEWAY% 2>/dev/null
            """.trimMargin()

            NetStrategy.BOTH -> """
                |ifconfig eth0 %GUEST_IP% netmask %NETMASK% up 2>/dev/null
                |brctl addbr br0 2>/dev/null
                |brctl addif br0 eth0 2>/dev/null
                |ifconfig br0 %GUEST_IP% netmask %NETMASK% up 2>/dev/null
                |route add default gw %GATEWAY% 2>/dev/null
            """.trimMargin()
        }
        return "$header\n$body"
    }

    /**
     * Vendor network scripts routinely wipe the address we just set, which is
     * what makes the forwarded port die halfway through boot. This re-asserts
     * it for a while after handover — the lightweight stand-in for FirmAE's
     * network arbitration. It runs from a busybox copied into the new root, so
     * it keeps working once switch_root has replaced the initramfs.
     */
    private fun watchdogBlock(iters: Int): String {
        if (iters <= 0) return "say 'network watchdog disabled'"
        return """
            |say 'starting network watchdog ($iters iterations)'
            |(
            |  i=0
            |  while [ "$i" -lt $iters ]; do
            |    BB=""
            |    for c in /new_root/bin/routeremu-busybox /bin/routeremu-busybox /bin/busybox; do
            |      if [ -x "$c" ]; then BB="$c"; break; fi
            |    done
            |    if [ -n "$BB" ]; then
            |      "$BB" ifconfig lo up 2>/dev/null
            |      "$BB" ifconfig eth0 %GUEST_IP% netmask %NETMASK% up 2>/dev/null
            |      "$BB" ifconfig br0 %GUEST_IP% netmask %NETMASK% up 2>/dev/null
            |      "$BB" sleep $WATCHDOG_INTERVAL_SECONDS
            |    else
            |      break
            |    fi
            |    i=$((i+1))
            |  done
            |) >/dev/null 2>&1 &
        """.trimMargin()
    }

    private val INIT_TEMPLATE = """
#!/bin/busybox sh
# RouterEmu initramfs /init - generated at runtime by InitramfsBuilder.kt.
# Mounts the firmware rootfs under /new_root, injects libnvram, brings up
# SLIRP networking, then hands over to the firmware's own init.

export PATH=/bin:/sbin:/usr/bin:/usr/sbin
export HOME=/root
export TERM=linux

say() { echo "[routeremu-init] ${'$'}*"; }

# Populate /bin with busybox applet symlinks.
/bin/busybox --install -s /bin >/dev/null 2>&1

mkdir -p /proc /sys /dev /dev/pts /new_root /tmp /run
mount -t proc     proc     /proc     2>/dev/null
mount -t sysfs    sysfs    /sys      2>/dev/null
mount -t devtmpfs devtmpfs /dev      2>/dev/null || mdev -s
mount -t devpts   devpts   /dev/pts  2>/dev/null

say 'target: %DEVICE% (%SOC%)'
say "cmdline: ${'$'}(cat /proc/cmdline)"

# ---- mount the firmware rootfs ------------------------------------------
CMD_ROOT=""
for a in ${'$'}(cat /proc/cmdline); do
  case "$a" in root=*) CMD_ROOT="${'$'}{a#root=}" ;; esac
done

ROOTDEV=""
for d in "$CMD_ROOT" %ROOT_CANDIDATES%; do
  [ -z "$d" ] && continue
  [ -e "$d" ] || continue
  for t in ext4 ext3 ext2; do
    if mount -t "$t" -o rw "$d" /new_root 2>/dev/null; then
      ROOTDEV="$d"
      break
    fi
  done
  [ -n "$ROOTDEV" ] && break
done

if [ -z "$ROOTDEV" ]; then
  say 'FATAL: could not mount a firmware root filesystem'
  say 'the disk image must be a raw ext2/3/4 image, not a firmware .bin'
  exec /bin/busybox sh
fi
say "firmware root mounted from $ROOTDEV"

%NVRAM_BLOCK%

%NET_BLOCK%

# ---- hand over ----------------------------------------------------------
for m in /proc /sys /dev; do
  mkdir -p "/new_root$m"
  mount --move "$m" "/new_root$m" 2>/dev/null
done

# Leave a busybox inside the new root so the watchdog survives switch_root.
cp -f /bin/busybox /new_root/bin/routeremu-busybox 2>/dev/null && chmod 755 /new_root/bin/routeremu-busybox

%WATCHDOG_BLOCK%

INIT="%INIT_PATH%"
[ -x "/new_root$INIT" ] || INIT=/sbin/init
[ -x "/new_root$INIT" ] || INIT=/etc/preinit
[ -x "/new_root$INIT" ] || INIT=/bin/init
[ -x "/new_root$INIT" ] || INIT=/bin/sh
say "switching root, init=$INIT"
exec switch_root /new_root "$INIT"
"""
}
