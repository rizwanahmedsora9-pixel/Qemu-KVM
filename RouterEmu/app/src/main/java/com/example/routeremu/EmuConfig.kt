package com.example.routeremu

import java.io.Serializable

/**
 * Which `qemu-system-*` binary to launch. The endianness of the guest firmware
 * is fixed at build time for MIPS, so picking the wrong one produces a kernel
 * that fails immediately with "Kernel panic - not syncing: Unexpected CPU
 * exception" or silent garbage on the serial console.
 *
 * Verified against OpenWrt's target Makefiles: `ath79` is `ARCH:=mips`
 * (big-endian, `mips_24kc` packages) while `ramips` is `ARCH:=mipsel`
 * (little-endian, `mipsel_24kc` packages).
 */
enum class Endianness(val qemuBinary: String, val label: String) : Serializable {
    BIG_ENDIAN("qemu-system-mips", "big-endian (qemu-system-mips)"),
    LITTLE_ENDIAN("qemu-system-mipsel", "little-endian (qemu-system-mipsel)")
}

/**
 * Which interface should hold the SLIRP guest address (10.0.2.15).
 *
 * Vendor firmwares disagree: TP-Link's stock scripts bring up a `br0` bridge,
 * OpenWrt uses `br-lan`, and simple firmwares just use `eth0`. Since QEMU's
 * `hostfwd` forwards to the guest address regardless of which interface owns
 * it, and almost every embedded web server binds 0.0.0.0, putting the address
 * on both interfaces maximises the chance the forwarded port is answered.
 */
enum class NetStrategy(val label: String) : Serializable {
    ETH0("eth0 only"),
    BR0("br0 bridge"),
    BOTH("br0 bridge + eth0")
}

/**
 * Everything that was previously hardcoded in [QemuService.launchQemu].
 *
 * Stock vendor firmware needs knobs the original argv did not have: a
 * substituted Malta kernel, a per-device `-cpu`, an `-initrd` to inject
 * libnvram without root, and an `init=` path that differs between vendors.
 */
data class EmuConfig(
    val deviceId: String = DeviceProfiles.DEFAULT_ID,
    val endianness: Endianness = Endianness.BIG_ENDIAN,
    val machine: String = "malta",
    /** null => let QEMU use the machine default (24Kf on malta). */
    val cpu: String? = null,
    val ramMb: Int = 128,
    val initPath: String = "/sbin/init",
    /** Appended verbatim after the standard cmdline, e.g. "mem=32M". */
    val kernelCmdlineExtra: String = "",
    /** Split on whitespace and appended before -nographic. */
    val extraQemuArgs: String = "",
    val hostPort: Int = 8080,
    val guestPort: Int = 80,
    val injectLibnvram: Boolean = true,
    /**
     * Whether to boot through a generated initramfs at all. Turning this off
     * makes QEMU exec the firmware's init directly, which is what you want when
     * debugging a rootfs you already patched on a PC.
     */
    val useInitramfs: Boolean = true,
    val netStrategy: NetStrategy = NetStrategy.BOTH,
    /**
     * How long the initramfs keeps re-asserting the guest IP after handing over
     * to the firmware's init. Vendor network scripts routinely wipe the address
     * we set, so without this the forwarded port goes dead mid-boot. 0 disables
     * it. This is the lightweight stand-in for FirmAE's network arbitration.
     */
    val netWatchdogSeconds: Int = 120,
    val rootDeviceHint: String = "/dev/sda"
) : Serializable {

    val device: DeviceProfile
        get() = DeviceProfiles.byId(deviceId)

    /**
     * The `-append` string. With an initrd present the kernel ignores `root=`
     * for mounting (the initramfs is rootfs) but our `/init` reads it back out
     * of `/proc/cmdline` as a hint, so it is still worth passing.
     */
    fun kernelCmdline(): String = buildString {
        append("root=").append(rootDeviceHint)
        append(" rootwait")
        append(" console=ttyS0")
        append(" init=").append(initPath)
        if (kernelCmdlineExtra.isNotBlank()) {
            append(' ').append(kernelCmdlineExtra.trim())
        }
    }

    fun extraArgsList(): List<String> =
        extraQemuArgs.split(Regex("\\s+")).filter { it.isNotBlank() }

    companion object {
        private const val serialVersionUID: Long = 1L

        /** A config pre-filled from a device profile's known-good defaults. */
        fun forDevice(profile: DeviceProfile): EmuConfig = EmuConfig(
            deviceId = profile.id,
            endianness = profile.endianness,
            cpu = profile.qemuCpu,
            ramMb = profile.emulatorRamMb,
            initPath = profile.initPath,
            netStrategy = profile.netStrategy
        )
    }
}
