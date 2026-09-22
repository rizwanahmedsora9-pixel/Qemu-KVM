# Router Firmware Emulator (Android / Jetpack Compose)

A standalone Android app that boots a MIPS Linux router firmware image under
QEMU (`-M malta`) and exposes its web admin UI inside an in-app WebView. This
follows the same emulation approach used by well-known firmware-analysis
tools such as firmadyne / FirmAE, packaged as a self-contained mobile app.

## What's included

| File | Purpose |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Compose UI: file pickers, download buttons, boot/stop controls, terminal log tab, WebView tab |
| `app/src/main/java/.../QemuService.kt` | Foreground `Service` that runs `qemu-system-mips` via `ProcessBuilder` and streams stdout/stderr |
| `app/src/main/java/.../QemuLogBus.kt` | In-process `SharedFlow`/`StateFlow` pub-sub connecting the service to the UI |
| `app/src/main/java/.../AssetInstaller.kt` | Copies the bundled QEMU binary to `filesDir` + chmod, and downloads/imports kernel & disk assets |
| `app/src/main/java/.../PortUtils.kt` | Polls `127.0.0.1:8080` so the WebView only loads once the emulated HTTP server is actually up |
| `app/src/main/java/.../RouterEmuApp.kt` | Creates the notification channel the foreground service needs |
| `AndroidManifest.xml` | Foreground-service (`specialUse` type), INTERNET, POST_NOTIFICATIONS declarations |
| `res/xml/network_security_config.xml` | Allows cleartext HTTP to `localhost` only (WebView blocks cleartext by default on modern Android) |

## Before this builds and runs

1. **Provide a real `qemu-system-mips` binary.** This project does not (and
   cannot) ship one. Cross-compile QEMU's `mips-softmmu` target for your
   device's Android ABI (arm64-v8a / armeabi-v7a / x86_64) — e.g. via the
   Android NDK toolchain — statically linked so it has no missing shared
   library dependencies at runtime, then drop the resulting binary at:

   ```
   app/src/main/assets/qemu-system-mips
   ```

   `AssetInstaller.ensureQemuBinaryInstalled()` copies this into
   `context.filesDir` and `setExecutable(true)`s it on first boot.

2. **Point the default-download URLs at real, license-appropriate hosting**
   for your kernel (`vmlinux.elf`) and extracted root filesystem
   (`tplink_root.img`) in `MainActivity.kt`:

   ```kotlin
   private const val DEFAULT_KERNEL_URL = "https://example.com/router-emu/vmlinux.elf"
   private const val DEFAULT_DISK_URL = "https://example.com/router-emu/tplink_root.img"
   ```

   Only redistribute firmware/kernel images you have the rights to
   distribute. Alternatively, ship the two "Choose file…" pickers only and
   drop the download buttons.

3. Open the project root in Android Studio (Koala/Ladybug or newer), let
   Gradle sync, and build. `minSdk` is 26 (foreground services + the process
   APIs this relies on need a reasonably modern Android version).

## Notes on the design

- **Foreground service, not a plain background thread.** Android will kill a
  long-lived child process started from an ordinary Activity-scoped
  coroutine the moment the app backgrounds. `QemuService` runs as a
  `specialUse` foreground service with a persistent notification (with a
  Stop action) specifically so the emulator keeps running while the user
  switches to the Router UI tab, checks another app, etc.
- **Two independent stream-pump coroutines** drain stdout and stderr
  concurrently — reading them sequentially risks deadlocking QEMU if one
  pipe's OS buffer fills up while you're blocked reading the other.
- **Port polling, not a fixed delay**, gates the WebView load. Firmware boot
  time varies a lot (a few seconds to well over a minute), so `PortUtils`
  polls `127.0.0.1:8080` every second for up to 3 minutes rather than
  guessing a sleep duration.
- **`network_security_config.xml`** scopes the cleartext-HTTP exemption to
  `localhost`/`127.0.0.1` only — nothing else in the app gets a blanket
  cleartext allowance.
