# ✨ HioneOS Web Flasher & USB Serial Suite

A high-performance **Go-powered WebUSB & WebSerial Flasher** for the HiBy M500 Qualcomm Digital Audio Player. 

Provides **1-Click zero-CLI browser flashing in Google Chrome, Microsoft Edge, and Brave**, converting any stock firmware revision (1.00 / 1.01) to **HioneOS v0.1.0** directly over USB.

---

## ⚡ Key Capabilities

1. **Direct Browser Fastboot over WebUSB (`navigator.usb`)**:
   - Flashes Android 14 GKI Kernel (`boot_a/b`, `init_boot_a/b`, `dtbo_a/b`, `vendor_boot_a/b`).
   - Flashes AVB 2.0 disablers (`vbmeta_a/b`, `vbmeta_system_a/b` with `flags=0x03`).
   - HTTP Range-streamed chunk flashing for massive dynamic partition images (`hioneos_super.img` ~4.8 GB) without RAM exhaustion.
   - Slot activation (`set_active a`) & user data formatting (`erase userdata`, `erase metadata`).

2. **Non-Destructive Live Core System APKs Updater (WebADB / WebSerial)**:
   - Updates `HioneLauncher.apk`, `HioneMusic.apk`, `HioneSettings.apk`, and `HioneSystemUI.apk` on a running device without wiping user data or music files.

3. **Standalone Embedded Go Binary**:
   - Single binary bundling the web interface (HTML5, Cyberpunk CSS, JavaScript Fastboot Engine) using `//go:embed`.
   - REST API & Server-Sent Events (SSE) for real-time terminal output.

---

## 🚀 Quick Start

### 1. Launch Web Server
```bash
./hioneos/web-flasher/run.sh
```
Or with Go directly:
```bash
cd hioneos/web-flasher
go run . -port 3939
```

Open **`http://localhost:3939`** in Google Chrome, Brave, or Edge.

---

## 🔌 Connecting HiBy M500

### Entering Fastboot Mode:
- **From Power Off:** Hold **`Power + Volume Down`** until the Fastboot splash screen appears.
- **From Running OS:** Connect USB cable and click **"📲 Reboot to Fastboot"** in the Web UI (or run `adb reboot bootloader`).

### In the Web Flasher:
1. Click **`⚡ Connect WebUSB`**.
2. Select **`Android`** or **`Qualcomm`** in the Chrome USB pairing modal.
3. Click **`⚡ FLASH HIONEOS VIA CHROME`**.
