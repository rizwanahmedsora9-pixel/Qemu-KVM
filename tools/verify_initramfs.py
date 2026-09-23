#!/usr/bin/env python3
"""
Verification harness for the RouterEmu initramfs pipeline.

What this DOES verify, for real:
  1. The SVR4 "newc" cpio layout implemented by CpioBuilder.kt — by
     transcribing its exact field order/padding and feeding the result to
     `busybox cpio -idmv -H newc`, the same applet the guest's busybox will
     have. Names, modes, symlink targets and contents are asserted.
  2. The /init script emitted by InitramfsBuilder.kt — the template and the
     three block builders are read out of the real Kotlin source, the same
     substitution order is applied, and the result is syntax-checked with
     `busybox sh -n`. Leftover %TOKEN% placeholders are asserted absent.

What this does NOT verify: the Kotlin itself. There is no JDK, Android SDK or
Gradle reachable in this sandbox, so nothing here was compiled. A transcription
error in the Python would not be caught.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

SRC = "/home/user/Qemu-KVM/RouterEmu/app/src/main/java/com/example/routeremu"
FAIL = []


def check(cond, msg):
    print(("  PASS  " if cond else "  FAIL  ") + msg)
    if not cond:
        FAIL.append(msg)


# ---------------------------------------------------------------- cpio ------
MAGIC = b"070701"
TRAILER = "TRAILER!!!"
DIR, REG_644, REG_755, SYMLINK = 0x41ED, 0x81A4, 0x81ED, 0xA1FF


def hex8(v):
    return ("%08X" % (v & 0xFFFFFFFF)).encode()


def cpio_entry(ino, name, mode, data):
    nb = name.encode()
    namesize = len(nb) + 1
    hdr = MAGIC + b"".join([
        hex8(ino), hex8(mode), hex8(0), hex8(0), hex8(1), hex8(0),
        hex8(len(data)), hex8(0), hex8(0), hex8(0), hex8(0),
        hex8(namesize), hex8(0),
    ])
    assert len(hdr) == 110, len(hdr)
    out = hdr + nb + b"\0"
    out += b"\0" * ((4 - ((110 + namesize) % 4)) % 4)
    out += data
    out += b"\0" * ((4 - (len(data) % 4)) % 4)
    return out


def cpio_newc(entries, pad_to_block=512):
    out = b""
    ino = 0x300000
    for name, mode, data in entries:
        out += cpio_entry(ino, name, mode, data)
        ino += 1
    out += cpio_entry(ino, TRAILER, 0, b"")
    rem = len(out) % pad_to_block
    if rem:
        out += b"\0" * (pad_to_block - rem)
    return out


def test_cpio():
    print("\n[1] cpio newc round-trip through `busybox cpio -H newc`")
    # Odd-length names and data exercise the 4-byte padding on both halves.
    entries = [
        ("bin", DIR, b""),
        ("lib", DIR, b""),
        ("init", REG_755, b"#!/bin/sh\necho hi\n"),
        ("lib/libnvram.so", REG_755, b"ELFfake" * 3),
        ("etc", DIR, b""),
        ("etc/config", REG_644, b"x"),
        ("bin/sh", SYMLINK, b"/bin/busybox"),
    ]
    arch = cpio_newc(entries)
    tmp = tempfile.mkdtemp()
    arc = os.path.join(tmp, "a.cpio")
    with open(arc, "wb") as f:
        f.write(arch)

    ext = os.path.join(tmp, "root")
    os.makedirs(ext)
    p = subprocess.run(
        ["busybox", "cpio", "-idmv", "-H", "newc", "-F", arc],
        cwd=ext, capture_output=True, text=True,
    )
    check(p.returncode == 0, "busybox cpio extracted cleanly (rc=%d) %s"
          % (p.returncode, p.stderr.strip()[:200]))
    check("TRAILER!!!" not in (p.stdout + p.stderr), "trailer not materialised as a file")

    for name, mode, data in entries:
        path = os.path.join(ext, name)
        exists = os.path.lexists(path)
        check(exists, "entry exists: %s" % name)
        if not exists:
            continue
        st = os.lstat(path)
        check((st.st_mode & 0o7777) == (mode & 0o7777),
              "mode %s: want %o got %o" % (name, mode & 0o7777, st.st_mode & 0o7777))
        if mode == SYMLINK:
            tgt = os.readlink(path)
            check(tgt == data.decode(), "symlink %s -> %s" % (name, tgt))
        elif mode != DIR:
            with open(path, "rb") as f:
                got = f.read()
            check(got == data, "content of %s (%d bytes)" % (name, len(data)))

    # The kernel also needs the archive to be recognisable when gzipped.
    import gzip
    with gzip.open(os.path.join(tmp, "a.cpio.gz"), "wb") as f:
        f.write(arch)
    ext2 = os.path.join(tmp, "root2")
    os.makedirs(ext2)
    gunz = subprocess.run(["busybox", "gunzip", "-c", os.path.join(tmp, "a.cpio.gz")],
                          capture_output=True)
    p2 = subprocess.run(["busybox", "cpio", "-idm", "-H", "newc"],
                        cwd=ext2, input=gunz.stdout, capture_output=True)
    check(p2.returncode == 0, "gzipped archive extracts after gunzip (rc=%d)" % p2.returncode)
    check(os.path.exists(os.path.join(ext2, "init")), "gzipped archive contained /init")
    shutil.rmtree(tmp, ignore_errors=True)


# ------------------------------------------------- /init script emission ----
def read_kotlin(name):
    with open(os.path.join(SRC, name)) as f:
        return f.read()


def extract_raw(src, start_marker, end_marker='"""'):
    i = src.index(start_marker) + len(start_marker)
    j = src.index(end_marker, i)
    return src[i:j]


def trim_margin(s):
    """Kotlin trimMargin(): keep everything after the first '|' on each line."""
    out = []
    for line in s.split("\n"):
        k = line.find("|")
        out.append(line[k + 1:] if k >= 0 else line)
    return "\n".join(out)


def extract_quoted_after(src, marker):
    """The first triple-quoted string following `marker`, with trimMargin applied."""
    i = src.index(marker)
    j = src.index('"""', i) + 3
    k = src.index('"""', j)
    return kotlin_unescape(trim_margin(src[j:k]))


def kotlin_unescape(s):
    # The only escape used in these templates is ${'$'} for a literal '$'.
    return s.replace("${'$'}", "$")


def test_init_script():
    print("\n[2] /init emission from the real InitramfsBuilder.kt source")
    src = read_kotlin("InitramfsBuilder.kt")

    template = kotlin_unescape(extract_raw(src, 'private val INIT_TEMPLATE = """'))
    nvram_block = extract_quoted_after(src, "private fun nvramBlock(")
    net_header = extract_quoted_after(src, "val header = \"\"\"")
    net_both = extract_quoted_after(src, "NetStrategy.BOTH -> \"\"\"")
    watchdog = extract_quoted_after(src, "private fun watchdogBlock(")

    check("%DEVICE%" in template, "template still carries %DEVICE% (substituted later)")
    check("switch_root /new_root" in template, "template hands over with switch_root")
    check("brctl addbr br0" in net_both, "BOTH strategy creates br0")

    # --- mirror InitramfsBuilder.initScript()'s substitution ORDER ----------
    # Blocks first (they contain %GUEST_IP%/%NETMASK% of their own), then scalars.
    script = template
    script = script.replace("%NVRAM_BLOCK%", nvram_block)
    script = script.replace("%NET_BLOCK%", net_header + "\n" + net_both)
    script = script.replace("%WATCHDOG_BLOCK%", watchdog.replace("$iters", "60"))
    script = script.replace("%DEVICE%", "TL-WR720N v1")
    script = script.replace("%SOC%", "Atheros AR9331 (Hornet) @400MHz")
    script = script.replace("%ROOT_CANDIDATES%", "/dev/sda /dev/sda1 /dev/hda")
    script = script.replace("%INIT_PATH%", "/sbin/init")
    script = script.replace("%GUEST_IP%", "10.0.2.15")
    script = script.replace("%NETMASK%", "255.255.255.0")
    script = script.replace("%GATEWAY%", "10.0.2.2")

    leftover = sorted(set(re.findall(r"%[A-Z_]+%", script)))
    check(not leftover, "no unsubstituted placeholders (found %s)" % (leftover or "none"))

    out = "/tmp/generated_init.sh"
    with open(out, "w") as f:
        f.write(script)

    for shell in (["busybox", "sh", "-n", out], ["dash", "-n", out]):
        p = subprocess.run(shell, capture_output=True, text=True)
        check(p.returncode == 0, "`%s` syntax-checks the emitted /init: %s"
              % (" ".join(shell), p.stderr.strip()[:200] or "clean"))

    # Content assertions on the emitted script.
    check("10.0.2.15" in script, "SLIRP guest IP present")
    check("10.0.2.2" in script, "SLIRP gateway present")
    check("LD_PRELOAD=/lib/libnvram.so" in script, "LD_PRELOAD exported for libnvram")
    check("brctl addbr br0" in script, "br0 bridge created (BOTH strategy)")
    check("routeremu-busybox" in script, "busybox copied into new root for the watchdog")
    check(script.count("/sbin/init") >= 2, "init path present in handover + fallback")
    print("      emitted script: %d lines, %d bytes" % (script.count("\n"), len(script)))


def test_dollar_escaping():
    """
    Kotlin raw strings interpolate on `$`. Every shell `$` in these templates
    must be written ${'$'} or the compiler fails with "Unresolved reference".
    This is the exact class of bug that cost a CI round-trip, so check it here.
    """
    print("\n[3] `$` escaping in the raw-string templates")
    src = read_kotlin("InitramfsBuilder.kt")

    # Whitelist: the only Kotlin symbols that legitimately interpolate.
    allowed = {"iters", "WATCHDOG_INTERVAL_SECONDS"}

    raws = re.findall(r'"""(.*?)"""', src, re.S)
    check(len(raws) >= 4, "found %d raw-string templates to inspect" % len(raws))

    offenders = []
    for body in raws:
        # Drop escaped dollars first; whatever `$` remains is interpolation.
        cleaned = body.replace("${'$'}", "")
        for m in re.finditer(r"\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)", cleaned):
            expr = m.group(1) if m.group(1) is not None else m.group(2)
            if expr in allowed or expr.startswith("config.") or expr.startswith("busybox"):
                continue
            offenders.append(expr)

    check(not offenders,
          "no unescaped shell `$var` in templates (offenders: %s)"
          % (sorted(set(offenders)) or "none"))

    # And confirm the escaping is actually load-bearing: the template must
    # contain escaped dollars, otherwise the check above passed vacuously.
    check(src.count("${'$'}") >= 20,
          "template uses ${'$'} escaping (%d occurrences)" % src.count("${'$'}"))


if __name__ == "__main__":
    test_cpio()
    test_init_script()
    test_dollar_escaping()
    print("\n" + ("ALL CHECKS PASSED" if not FAIL else "%d CHECK(S) FAILED" % len(FAIL)))
    for f in FAIL:
        print("  - " + f)
    sys.exit(1 if FAIL else 0)
