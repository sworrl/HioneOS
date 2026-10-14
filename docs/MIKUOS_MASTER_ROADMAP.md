# MikuOS Master Engineering Roadmap & Technical Specification

**Device**: HiBy M500 High-Resolution Digital Audio Player  
**Target Platform**: Qualcomm Snapdragon 680 (SM6225 / Khaje Platform)  
**Primary DAC**: Cirrus Logic Dual CS43198 MasterHIFI™  
**OS Base**: Android 14 (AOSP 14) -> Upstream Android 15, 16, 17  
**Build Architecture**: 100% Modern Kotlin + Jetpack Compose Native Platform Suite  

---

## 1. Kernel Architecture: Linux 4.19 LTS (Stable, High-Performance, Zero-Risk)

### Strategy:
- **Base Version**: Linux `4.19.320+` LTS (Qualcomm SM6225 BSP branch).
- **Rationale**:
  - Ensures 100% binary compatibility with proprietary Qualcomm SM6225 firmware blobs:
    - Hexagon ADSP audio firmware (`q6dsp`, `snd-soc-sm6225`). It converts every wired stream to 192 kHz today, see docs/research/hiby-audio-knobs.md section 10.
    - Adreno 610 GPU `kgsl` kernel driver and display DRM compositor.
    - SGM31324 Pulsar Dual-Die RGB LED PWM driver.
    - HiBy `sa_sound_setting` CS43198 ALSA kernel driver.
- **Backported Kernel Enhancements**:
  - Upstream Linux 4.19 LTS security and stability patches.
  - WireGuard kernel module integration.
  - eBPF packet filtering and scheduler enhancements.
  - BBR TCP congestion control and memory management optimizations.

---

## 2. Rooting, Security & Platform PKI Infrastructure

### Development vs Release Model:
- **Development**:
  - Retains root privileges (`su`, Magisk / KernelSU) for real-time ADB debugging, hot module reloading, and direct kernel sysfs profiling.
- **End-User Release**:
  - Ships as a **clean, platform-signed, non-rooted base ROM**.
  - All MikuOS system APKs (`Settings.apk`, `SystemUI.apk`, `Launcher3QuickStep.apk`) run natively with platform privileges (`android:sharedUserId="android.uid.system"` and AOSP `privapp-permissions-mikuos.xml`).
  - Users can easily unlock bootloader and root via standard Magisk / KernelSU if desired.

### Platform Key Hierarchy:
- **Root CA**: Enterprise Root of Trust (4096-bit RSA / Ed25519) stored in secure KMS / HSM.
- **Intermediate CA**: `MikuOS OS Release CA`.
- **Leaf Platform Keys**:
  - `platform.pk8` / `platform.x509.pem` -> Signs `SystemUI`, `Settings`, `framework-res`, `services.jar`.
  - `releasekey.pk8` -> Signs OTA updates and recovery verification payloads.
  - `media.pk8` -> Signs `MediaProvider`, `DownloadProvider`, audio effect engines.
  - `shared.pk8` -> Signs shared system utilities and `Launcher3`.
  - `networkstack.pk8` -> Signs `NetworkStack` and captive portal controllers.

---

## 3. Core System Packages & Architecture

### A. `MikuOS_Settings.apk` (`com.miku.settings` / `com.android.settings`)
- **MasterHIFI Dual CS43198 Audio Pipeline**:
  - Digital Reconstruction Filters: `fast_rolloff_phase_compensated`, `fast_rolloff_low_latency`, `slow_rolloff_phase_compensated`, `slow_rolloff_low_latency`, `nos`.
  - Analog PO Gain: `high` (+6 dB) / `low` (0 dB).
  - Line Out (LO) Gain: `high` / `low`.
  - Dynamic Range Enhancement (DRE): `dremode_enable` / `dremode_disable` (130dB+ SNR).
  - Audio Turbo High Power Mode: `hpower_enable` / `hpower_disable`.
  - DSD Compensation: `6` dB / `0` dB.
  - Output Routing: `bal_po` (Headphone Out) vs `bal_lo` (Line Out).
  - Hardware L/R Balance (`-10` to `+10`) and Pre-gain (`-10` to `+10`).
  - Timbre: `monitor` / `warm`.
- **Pulsar RGB LED Matrix**:
  - SGM31324 dual-die constant-current PWM control.
  - Modes: Audiophile BPM Pulse, Chroma Wave, Cyber Heartbeat, Smooth Breathing, Miku Blue, Battery Monitor, Off.
- **Connectivity & Networking**:
  - Wi-Fi, Hotspot, and Wireless ADB (Port 5555 toggle with live IP display).
  - Bluetooth codec manager (LDAC 990kbps, aptX HD, AAC, SBC).
- **System Telemetry**:
  - Battery observatory (voltage, temperature, mA draw).
  - Ambient brightness and display atmosphere controls.
  - Application and storage management.

### B. `MikuOS_SystemUI.apk` (`com.miku.systemui` / `com.android.systemui`)
- **Quick Settings & Notification Shade**:
  - Top-down pull gesture interceptor across status bar.
  - 3x3 Cyber Quick Tiles: DAC Filter, PO Gain, Audio Turbo, DRE, Pulsar RGB, Wireless ADB, Wi-Fi, Bluetooth, Airplane Mode.
  - Hardware Sliders: Cyan Display Brightness & Pink Media Volume.
  - Now Playing Media Card with direct player deep linking.
  - Header with digital clock, date, and live battery gauge.

### C. `MikuOS_Launcher.apk` (`com.miku.launcher` / `com.android.launcher3`)
- **Cyberpunk DAP Launcher**:
  - Live clock/calendar widget with MikuOS styling.
  - Hardware status badges (Dual DAC, Kernel, Battery).
  - Fast cyber app dock (Music, FM Radio, Settings, Browser, Apps).
  - Full app drawer with search and spring drag physics.

### D. `Miku Music Player` (`com.miku.player`)
- DIRECT output with no Android mixer (done). The file's own rate at the CS43198s (not done: the driver runs them at 192 kHz).
- Release year resolution on all songs, albums, and discographies in glowing Miku Gold (`#FFD54F`).
- Hexagon tech badges (`24-BIT`, `96KHZ`, `FLAC`, `DSD`) across all grids, lists, and playback cards.

---

## 4. Multi-Version OS Evolution

1. **Phase 1: Android 14 MikuOS (Current Focus)**:
   - Establish stable, complete MikuOS platform on AOSP 14.
   - Deploy platform-signed system APKs and HAL routing.
2. **Phase 2: Android 15 MikuOS**:
   - Construct Android 15 system image utilizing Android 14 MikuOS foundation.
   - Build Qualcomm Treble HIDL/AIDL audio and display HAL shims.
3. **Phase 3: Android 16 & 17 MikuOS**:
   - Upstream Android migration retaining identical platform signing keys, Compose UI suite, and MasterHIFI audio pipeline.
