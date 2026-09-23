package com.example.routeremu

import java.io.Serializable

/**
 * Per-model defaults for TP-Link hardware.
 *
 * WHY THIS EXISTS: the guest firmware's SoC decides the CPU core QEMU must
 * present, the endianness of the `qemu-system-*` binary, and how much RAM the
 * firmware's own scripts assume. Getting any of those wrong looks like a
 * random boot failure, so the app ships a lookup table instead of making the
 * user guess.
 *
 * SOURCING: every `socVerified = true` row below was cross-checked against the
 * OpenWrt Table of Hardware, WikiDevi/DeviWiki teardown pages, or an actual
 * kernel boot log, on 2026-09-23. Rows marked `socVerified = false` carry a
 * plausible SoC that should be re-checked before you rely on it. The
 * *endianness* column is safe either way: it follows from the SoC family, and
 * Atheros AR7xxx/AR9xxx/QCA95xx are big-endian MIPS (`mips_24kc`) while
 * MediaTek MT76xx are little-endian MIPS (`mipsel_24kc`).
 *
 * QEMU CPU mapping — QEMU's MIPS32 model list offers 24Kc, 24KEc, 24Kf, 34Kf
 * and 74Kf. Real 24Kc/74Kc parts map onto 24Kc/74Kf (the FPU variant is a
 * superset and harmless); MT76xx 24KEc parts map onto 24KEc.
 */
data class DeviceProfile(
    val id: String,
    val label: String,
    val soc: String,
    val endianness: Endianness,
    val qemuCpu: String?,
    val ramMb: Int,
    val flashMb: Int,
    val initPath: String = "/sbin/init",
    val netStrategy: NetStrategy = NetStrategy.BOTH,
    val socVerified: Boolean = false,
    val notes: String = ""
) : Serializable {

    /**
     * RAM to hand the emulator. The real device's RAM is tiny (16-128 MiB) but
     * the emulated rootfs is a repacked ext image, not the original squashfs
     * plus JFFS2 overlay, so it needs headroom. Malta has no `default_ram_size`,
     * so QEMU's documented `-m` default of 128 MiB would apply anyway.
     */
    val emulatorRamMb: Int get() = if (ramMb < 64) 128 else ramMb

    val displayName: String
        get() = "$label — $soc${if (socVerified) "" else " (SoC unverified)"}"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

object DeviceProfiles {

    const val DEFAULT_ID = "tl-wr720n-v1"

    private val PROFILES: List<DeviceProfile> = listOf(
        // ---- The model this project started from -------------------------
        DeviceProfile(
            id = "tl-wr720n-v1",
            label = "TL-WR720N v1",
            soc = "Atheros AR9331 (Hornet) @400MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 16, flashMb = 2,
            socVerified = true,
            notes = "MIPS 24Kc, big-endian. Stock firmware is nvram-based and " +
                "expects an AR9331 machine model QEMU does not have, so the " +
                "kernel must be substituted with a Malta build."
        ),
        DeviceProfile(
            id = "tl-mr3020-v1",
            label = "TL-MR3020 v1",
            soc = "Atheros AR9331 (Hornet)",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4,
            socVerified = true,
            notes = "Same SoC family as the WR720N; boot logs show AR9330/AR9331 Hornet."
        ),
        // ---- TL-WR841ND family (SoC table from DeviWiki teardowns) -------
        DeviceProfile(
            id = "tl-wr841nd-v3",
            label = "TL-WR841ND v3",
            soc = "Atheros AR9130 @400MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4,
            socVerified = true
        ),
        DeviceProfile(
            id = "tl-wr841nd-v5",
            label = "TL-WR841ND v5",
            soc = "Atheros AR7240 @400MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4,
            socVerified = true
        ),
        DeviceProfile(
            id = "tl-wr841nd-v7",
            label = "TL-WR841ND v7",
            soc = "Atheros AR7241 @400MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4,
            socVerified = true
        ),
        DeviceProfile(
            id = "tl-wr841n-v8",
            label = "TL-WR841N/ND v8",
            soc = "Atheros AR9341 @535MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "74Kf",
            ramMb = 32, flashMb = 4,
            socVerified = true,
            notes = "MIPS 74Kc core — QEMU's closest model is 74Kf."
        ),
        DeviceProfile(
            id = "tl-wr841n-v9",
            label = "TL-WR841N v9",
            soc = "Qualcomm Atheros QCA9533 @550MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4,
            socVerified = true
        ),
        DeviceProfile(
            id = "tl-wr841n-v10",
            label = "TL-WR841N v10",
            soc = "Qualcomm Atheros QCA9533 @560MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4,
            socVerified = true
        ),
        // ---- TL-WR941ND family (SoC table from the OpenWrt wiki) ---------
        DeviceProfile(
            id = "tl-wr941nd-v1",
            label = "TL-WR941ND v1",
            soc = "Atheros AR9132 @400MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 8,
            socVerified = true
        ),
        DeviceProfile(
            id = "tl-wr941nd-v4",
            label = "TL-WR941ND v4",
            soc = "Atheros AR7240 @400MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4,
            socVerified = true
        ),
        DeviceProfile(
            id = "tl-wr941nd-v5",
            label = "TL-WR941ND v5",
            soc = "Atheros AR9341 @535MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "74Kf",
            ramMb = 32, flashMb = 4,
            socVerified = true,
            notes = "OpenWrt package architecture for this device is mips_24kc " +
                "(target ar71xx, subtarget tiny) — confirms big-endian."
        ),
        DeviceProfile(
            id = "tl-wr941nd-v6",
            label = "TL-WR941ND v6",
            soc = "Qualcomm TP9343 / QCA9561 @750MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "74Kf",
            ramMb = 32, flashMb = 4,
            socVerified = true
        ),
        // ---- Higher-end MIPS routers ------------------------------------
        DeviceProfile(
            id = "tl-wr1043nd-v1",
            label = "TL-WR1043ND v1",
            soc = "Atheros AR9132 @400MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 8,
            socVerified = true,
            notes = "Classic firmadyne-era test target. Best first candidate for " +
                "a stock TP-Link firmware that actually reaches its web UI."
        ),
        DeviceProfile(
            id = "tl-wr1043nd-v2",
            label = "TL-WR1043ND v2",
            soc = "Qualcomm Atheros QCA9558",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "74Kf",
            ramMb = 64, flashMb = 8,
            socVerified = true,
            notes = "Boot log reads 'MIPS: machine is TP-Link TL-WR1043ND v2 / " +
                "SoC: Qualcomm Atheros QCA9558'."
        ),
        DeviceProfile(
            id = "tl-wdr4300-v1",
            label = "TL-WDR4300 v1 (N750)",
            soc = "Qualcomm Atheros AR9344 @560MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "74Kf",
            ramMb = 128, flashMb = 8,
            socVerified = true,
            notes = "MIPS 74Kc, 128 MiB RAM — the most headroom of any device here."
        ),
        DeviceProfile(
            id = "tl-wr2543nd-v1",
            label = "TL-WR2543ND v1",
            soc = "Qualcomm Atheros AR7242 @400MHz",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 64, flashMb = 8,
            socVerified = true
        ),
        // ---- Little-endian TP-Link devices ------------------------------
        DeviceProfile(
            id = "archer-c20-v1",
            label = "Archer C20 v1 (AC750)",
            soc = "MediaTek MT7620A @580MHz",
            endianness = Endianness.LITTLE_ENDIAN,
            qemuCpu = "24KEc",
            ramMb = 64, flashMb = 8,
            socVerified = true,
            notes = "OpenWrt target ramips/mt7620, package architecture " +
                "mipsel_24kc. Needs qemu-system-mipsel and a little-endian " +
                "Malta kernel."
        ),
        DeviceProfile(
            id = "archer-c20-v4",
            label = "Archer C20 v4 (AC750)",
            soc = "MediaTek MT7628AN @580MHz",
            endianness = Endianness.LITTLE_ENDIAN,
            qemuCpu = "24KEc",
            ramMb = 64, flashMb = 8,
            socVerified = true,
            notes = "OpenWrt target ramips/mt76x8, package architecture mipsel_24kc."
        ),
        // ---- Unverified extras (kept because they are common) -----------
        DeviceProfile(
            id = "tl-wr703n-v1",
            label = "TL-WR703N v1",
            soc = "Atheros AR9331",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4
        ),
        DeviceProfile(
            id = "tl-wr740n-v4",
            label = "TL-WR740N v4",
            soc = "Atheros AR7240",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "24Kc",
            ramMb = 32, flashMb = 4,
            notes = "FCC ID for the v4.2x board is TE7/WR741NDV4, i.e. the " +
                "WR741ND v4 hardware; SoC inferred from that family."
        ),
        DeviceProfile(
            id = "tl-wr842nd-v2",
            label = "TL-WR842ND v2",
            soc = "Atheros AR9341",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = "74Kf",
            ramMb = 64, flashMb = 8
        ),
        DeviceProfile(
            id = "generic-mips-be",
            label = "Generic MIPS big-endian",
            soc = "unspecified",
            endianness = Endianness.BIG_ENDIAN,
            qemuCpu = null,
            ramMb = 128, flashMb = 8,
            notes = "No -cpu override; QEMU uses malta's default 24Kf. Use this " +
                "for anything not in the list."
        ),
        DeviceProfile(
            id = "generic-mipsel-le",
            label = "Generic MIPS little-endian",
            soc = "unspecified",
            endianness = Endianness.LITTLE_ENDIAN,
            qemuCpu = null,
            ramMb = 128, flashMb = 8,
            notes = "Requires the qemu-system-mipsel binary to be present."
        )
    )

    val all: List<DeviceProfile> get() = PROFILES

    fun byId(id: String): DeviceProfile =
        PROFILES.firstOrNull { it.id == id } ?: PROFILES.first { it.id == DEFAULT_ID }
}
