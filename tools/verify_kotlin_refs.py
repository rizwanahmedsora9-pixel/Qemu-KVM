#!/usr/bin/env python3
"""
Cross-reference checker for the RouterEmu Kotlin sources.

There is no JDK, Android SDK or Gradle reachable in this sandbox (dl.google.com
and deb.debian.org both refuse connections), so the Kotlin cannot be compiled
here. This is the next best thing: it asserts that every symbol one file uses
is actually declared by the file that should declare it, that the enum/constant
that was renamed or removed has no dangling references, and that each file's
braces balance. It catches typos and stale references; it does NOT catch type
errors, overload resolution or Compose compiler rules.
"""
import os
import re
import sys

ROOT = "/home/user/Qemu-KVM/RouterEmu/app/src/main"
JAVA = os.path.join(ROOT, "java/com/example/routeremu")
FAIL = []


def check(cond, msg):
    print(("  PASS  " if cond else "  FAIL  ") + msg)
    if not cond:
        FAIL.append(msg)


def read(path):
    with open(path) as f:
        return f.read()


FILES = {n: read(os.path.join(JAVA, n)) for n in sorted(os.listdir(JAVA))
         if n.endswith(".kt")}


def used_symbols(text, receiver):
    """Distinct `Receiver.member` references in text."""
    return set(re.findall(r"\b%s\.([A-Za-z_][A-Za-z0-9_]*)" % re.escape(receiver), text))


def declared_members(text):
    """Crude declaration scan: fun/val/var/const val/nested type/enum entry names."""
    names = set(re.findall(r"\b(?:fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)", text))
    names |= set(re.findall(r"\bconst val\s+([A-Za-z_][A-Za-z0-9_]*)", text))
    # Nested classes/objects are legal members too (e.g. CpioBuilder.Entry).
    names |= set(re.findall(r"\b(?:class|object|interface|enum class)\s+([A-Za-z_][A-Za-z0-9_]*)", text))
    # enum entries: `NAME(...)` or `NAME,` on their own line inside an enum body
    names |= set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]+)\s*(?:\(|,)", text, re.M))
    return names


def main():
    print("[3] cross-references across the Kotlin sources")

    # --- 1. the constant removed from QemuService must have no references ---
    stale = [n for n, t in FILES.items() if "HOST_FWD_PORT" in t]
    check(not stale, "no dangling QemuService.HOST_FWD_PORT references (found in %s)"
          % (stale or "none"))

    # --- 2. QemuLogBus API ---
    logbus_members = declared_members(FILES["QemuLogBus.kt"])
    for fname, text in FILES.items():
        if fname == "QemuLogBus.kt":
            continue
        for m in sorted(used_symbols(text, "QemuLogBus")):
            check(m in logbus_members, "%s uses QemuLogBus.%s" % (fname, m))

    # --- 3. AssetSlot enum entries ---
    slot_entries = set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]*)\(", FILES["AssetInstaller.kt"], re.M))
    check({"QEMU", "BUSYBOX", "LIBNVRAM", "LIBNVRAM_IOCTL", "KERNEL", "DISK"} <= slot_entries,
          "AssetSlot declares all six slots (got %s)" % sorted(slot_entries))
    installer_members = declared_members(FILES["AssetInstaller.kt"])
    for fname, text in FILES.items():
        if fname == "AssetInstaller.kt":
            continue
        for m in sorted(used_symbols(text, "AssetInstaller")):
            check(m in installer_members, "%s uses AssetInstaller.%s" % (fname, m))
        for m in sorted(used_symbols(text, "AssetSlot")):
            check(m in slot_entries, "%s uses AssetSlot.%s" % (fname, m))

    # --- 4. EmuConfig fields actually exist ---
    cfg_fields = set(re.findall(r"^\s+val\s+([a-zA-Z_][A-Za-z0-9_]*)\s*:", FILES["EmuConfig.kt"], re.M))
    cfg_members = declared_members(FILES["EmuConfig.kt"]) | cfg_fields
    referenced = set()
    for fname, text in FILES.items():
        if fname == "EmuConfig.kt":
            continue
        referenced |= used_symbols(text, "config")
        referenced |= used_symbols(text, "it")  # conservative; filtered below
    for m in sorted(referenced & {
        "deviceId", "endianness", "machine", "cpu", "ramMb", "initPath",
        "kernelCmdlineExtra", "extraQemuArgs", "hostPort", "guestPort",
        "injectLibnvram", "useInitramfs", "netStrategy", "netWatchdogSeconds",
        "rootDeviceHint", "device",
    }):
        check(m in cfg_members, "config.%s is declared on EmuConfig" % m)

    # --- 5. DeviceProfiles / profile fields ---
    dp_members = declared_members(FILES["DeviceProfiles.kt"])
    for fname, text in FILES.items():
        if fname == "DeviceProfiles.kt":
            continue
        for m in sorted(used_symbols(text, "DeviceProfiles")):
            check(m in dp_members, "%s uses DeviceProfiles.%s" % (fname, m))
    profile_fields = set(re.findall(r"^\s+val\s+([a-zA-Z_][A-Za-z0-9_]*)\s*:",
                                    FILES["DeviceProfiles.kt"], re.M))
    for m in sorted(used_symbols(FILES["MainActivity.kt"], "profile") |
                    used_symbols(FILES["InitramfsBuilder.kt"], "config.device")):
        if m in ("label", "soc", "socVerified", "notes", "id", "initPath",
                 "qemuCpu", "ramMb", "flashMb", "netStrategy", "endianness",
                 "displayName", "emulatorRamMb"):
            check(m in profile_fields or m in declared_members(FILES["DeviceProfiles.kt"]),
                  "DeviceProfile.%s is declared" % m)

    # --- 6. CpioBuilder / ArchDetector / InitramfsBuilder surface ---
    for recv in ("CpioBuilder", "ArchDetector", "InitramfsBuilder"):
        owner = {"CpioBuilder": "CpioBuilder.kt", "ArchDetector": "ArchDetector.kt",
                 "InitramfsBuilder": "InitramfsBuilder.kt"}[recv]
        members = declared_members(FILES[owner])
        for fname, text in FILES.items():
            if fname == owner:
                continue
            for m in sorted(used_symbols(text, recv)):
                check(m in members, "%s uses %s.%s" % (fname, recv, m))

    # --- 7. resource referenced by the notification exists ---
    strings = read(os.path.join(ROOT, "res/values/strings.xml"))
    check("qemu_notification_title" in strings,
          "R.string.qemu_notification_title exists in strings.xml")

    # --- 8. brace / paren balance per file ---
    for fname, text in sorted(FILES.items()):
        # strip strings and comments so braces inside them don't count
        stripped = re.sub(r'"""(?:.|\n)*?"""', '""', text)
        stripped = re.sub(r'"(?:\\.|[^"\\])*"', '""', stripped)
        stripped = re.sub(r"//[^\n]*", "", stripped)
        stripped = re.sub(r"/\*(?:.|\n)*?\*/", "", stripped)
        check(stripped.count("{") == stripped.count("}"),
              "%s braces balance (%d/%d)" % (fname, stripped.count("{"), stripped.count("}")))
        check(stripped.count("(") == stripped.count(")"),
              "%s parens balance (%d/%d)" % (fname, stripped.count("("), stripped.count(")")))

    # --- 9. Compose/Material3 symbols used in MainActivity are imported ---
    main = FILES["MainActivity.kt"]
    imported = set(re.findall(r"^import\s+(?:androidx|android|kotlinx|java)[\w.]*\.(\w+)$",
                              main, re.M))
    for sym in ("Switch", "ExperimentalMaterial3Api", "ExposedDropdownMenuBox",
                "ExposedDropdownMenuDefaults", "DropdownMenuItem", "OutlinedTextField",
                "LinearProgressIndicator", "CircularProgressIndicator", "Tab", "TabRow",
                "Card", "Button", "OutlinedButton", "Surface", "MaterialTheme",
                "withContext", "Dispatchers", "itemsIndexed", "CircleShape"):
        check(sym in imported, "MainActivity imports %s" % sym)

    # --- 10. bundled-asset placeholders exist for every downloadable slot ---
    assets = os.path.join(ROOT, "assets")
    listed = sorted(os.listdir(assets))
    print("      assets/: %s" % ", ".join(listed))
    check(any("PUT_" in n for n in listed), "assets/ documents what must be supplied")

    print("\n" + ("ALL CHECKS PASSED" if not FAIL else "%d CHECK(S) FAILED" % len(FAIL)))
    for f in FAIL:
        print("  - " + f)
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
