# Qemu-KVM

**Router Firmware Emulator** — a standalone Android app (Jetpack Compose) that boots a
MIPS Linux router firmware image under QEMU (`-M malta`) and shows the emulated
router's web admin UI inside an in-app WebView. Same emulation approach as
firmadyne / FirmAE, packaged as a self-contained mobile app.

> The full app source lives in [`RouterEmu/`](RouterEmu/).
> `RouterEmu.zip` is the original archive the project was uploaded as — the
> extracted tree in `RouterEmu/` is the canonical, readable copy.

## App overview

| Component | Purpose |
|---|---|
| `RouterEmu/app/src/main/java/.../MainActivity.kt` | Compose UI: file pickers, download buttons, boot/stop controls, terminal log tab, WebView tab |
| `RouterEmu/app/src/main/java/.../QemuService.kt` | Foreground `Service` that runs `qemu-system-mips` via `ProcessBuilder` and streams stdout/stderr |
| `RouterEmu/app/src/main/java/.../QemuLogBus.kt` | In-process `SharedFlow`/`StateFlow` pub-sub connecting the service to the UI |
| `RouterEmu/app/src/main/java/.../AssetInstaller.kt` | Copies the bundled QEMU binary to `filesDir` + chmod, and downloads/imports kernel & disk assets |
| `RouterEmu/app/src/main/java/.../PortUtils.kt` | Polls `127.0.0.1:8080` so the WebView only loads once the emulated HTTP server is actually up |
| `RouterEmu/app/src/main/java/.../RouterEmuApp.kt` | Creates the notification channel the foreground service needs |
| `RouterEmu/app/src/main/AndroidManifest.xml` | Foreground-service (`specialUse` type), INTERNET, POST_NOTIFICATIONS declarations |
| `RouterEmu/app/src/main/res/xml/network_security_config.xml` | Allows cleartext HTTP to `localhost` only |

## How it works

1. **Provide assets** — a cross-compiled `qemu-system-mips` binary in
   `RouterEmu/app/src/main/assets/`, plus a MIPS kernel (`vmlinux.elf`) and raw
   root filesystem (`tplink_root.img`) picked via SAF or downloaded.
2. **Boot** — `QemuService` launches QEMU with `-M malta`, user-mode networking,
   and `hostfwd=tcp::8080-:80` so the guest's port 80 is reachable on the host.
3. **Wait** — `PortUtils` polls `127.0.0.1:8080` (up to 3 minutes) until the
   firmware's web server is actually accepting connections.
4. **Browse** — the WebView tab loads `http://localhost:8080` (cleartext allowed
   for loopback only via `network_security_config.xml`).

## Design notes

- **Foreground service, not a plain background thread** — Android kills
  long-lived child processes started from Activity-scoped coroutines as soon as
  the app backgrounds; the `specialUse` foreground service with a persistent
  notification (Stop action) keeps the emulator alive.
- **Two independent stream-pump coroutines** drain QEMU's stdout and stderr
  concurrently — reading them sequentially can deadlock on a full pipe buffer.
- **Port polling, not a fixed delay** — firmware boot time varies from seconds
  to over a minute, so readiness is detected by an actual TCP connect.
- **START_NOT_STICKY** — if the system kills the service under memory pressure
  it is not silently respawned with a null intent; the user re-taps Boot.

## Building

1. Put a real statically-linked `qemu-system-mips` binary (built for
   arm64-v8a / armeabi-v7a / x86_64 via the Android NDK) at
   `RouterEmu/app/src/main/assets/qemu-system-mips`
   (see `PUT_QEMU_BINARY_HERE.txt` there).
2. Point `DEFAULT_KERNEL_URL` / `DEFAULT_DISK_URL` in `MainActivity.kt` at
   real, license-appropriate hosting — or use the "Choose file…" pickers only.
3. Open `RouterEmu/` in Android Studio (Koala/Ladybug+), let Gradle sync, build.
   `minSdk` 26, `targetSdk`/`compileSdk` 34, Kotlin 1.9.24, AGP 8.5.2, Compose BOM 2024.06.00.

## CI: APK builds, info data, auto-merge publishing

### [`build-apk.yml`](.github/workflows/build-apk.yml) — Build APK

Runs on every PR (including `auto_merge_enabled`), pushes to `main`, and the
merge queue. It:

1. **Builds the APK** — debug + release (`RouterEmu-debug.apk`,
   `RouterEmu-release.apk`, release signed with the debug keystore so CI
   artifacts are installable).
2. **Gathers toolchain & info data** — `build-info.md` / `build-info.env`
   (Java, Gradle, Android SDK, AGP/Kotlin/Compose versions, commit, run
   metadata) and `pr-info.json` on PRs (number, title, auto-merge flag).
3. **Uploads artifacts** — the full bundle including `APP_INVENTORY.md`,
   `SHA256SUMS.txt`, and the info files (kept 30 days).

**On auto-merge to `main`** the same workflow additionally:

- regenerates and **commits refreshed info data** (`APP_INVENTORY.md`) back to
  `main` (`docs: refresh app info data after merge [skip ci]`), and
- **publishes/updates the `latest-main` GitHub Release** with the APKs,
  checksums, inventory, and build-info (release assets are `--clobber`-updated
  on every merge).

### [`update-app-docs.yml`](.github/workflows/update-app-docs.yml) — docs on every PR

Runs on every new PR (opened / synchronize / reopened) and regenerates
[`APP_INVENTORY.md`](APP_INVENTORY.md) from the live source tree, committing it
back to the PR branch when changed.

### Local equivalents

```bash
./scripts/update_app_docs.sh   # regenerate APP_INVENTORY.md
gradle -p RouterEmu assembleDebug assembleRelease   # build APKs
```
