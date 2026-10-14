# HiBy M500 (Miku Edition) — Complete Vendor Architecture & Reverse-Engineering Reference

## 1. Executive Summary & Hardware Identification
The HiBy M500 is a dedicated Digital Audio Player (DAP) running Android 14 (AOSP / Qualcomm Bengal platform `SM6115`). While the base operating system uses standard AOSP building blocks, all audio processing, hardware switches, LED signaling, and UI controls are managed by a specialized suite of proprietary HiBy vendor software, custom HAL shared objects, and Linux kernel sysfs interfaces.

Every non-AOSP component on the device has been extracted, decompiled, and indexed into [`m500-system-archive/decompiled_vendor_software/`](file:///home/reaver/Documents/GitHub/m500/m500-system-archive/decompiled_vendor_software/).

---

## 2. Inventory of Custom & Proprietary Software

### A. Applications & Framework Overlays (`/vendor/app`, `/system_ext/priv-app`)

| Package / Binary | Path on Device | Decompiled Location | Description & Reverse-Engineered Functionality |
|---|---|---|---|
| **HiBy Music** (`com.hiby.music`) | `/vendor/app/HiByMusic/HiByMusic.apk` | `decompiled_vendor_software/HiByMusic`<br>`jadx_sources/HiByMusic` | **Primary Hi-Res Audio Engine**: Direct ALSA bypass, DSD64–DSD256 Native/DoP (`.dsf`, `.dff`, `.iso`), MQA Core Decoder / Full Unfolder, 32-bit/768kHz PCM output. Direct communication with `vendor.audio.hiby.*` system properties. |
| **HiBy Sound Core** (`com.hiby.sound`) | `/vendor/app/HibySound/HibySound.apk` | `decompiled_vendor_software/HibySound`<br>`jadx_sources/HibySound` | **DSP & Tuning Pipeline**: Contains MSEB (MageSound 8-Ball sound tuning algorithm), 10-band Parametric EQ, Soundfield extension, channel balance, and digital filter rolloff selection. |
| **HiBy Tape** (`com.hiby.tape`) | `/vendor/app/HiByTape/HiByTape.apk` | `decompiled_vendor_software/HiByTape`<br>`jadx_sources/HiByTape` | **High-Fidelity Audio Recorder**: Low-latency PCM/WAV/FLAC recording through the internal microphone and dual external line-in jacks. |
| **HiBy Launcher Widget** (`com.hiby.widget`) | `/vendor/app/HiByM500Widget/HiByM500Widget.apk` | `decompiled_vendor_software/HiByM500Widget`<br>`jadx_sources/HiByM500Widget` | **Home Screen Controller**: Miku-themed desktop widget interfacing with `MediaSession` and displaying live bitrate/format badges. |
| **HiBy Test Suite** (`com.hiby.test`) | `/vendor/app/HiByTest/HiByTest.apk` | `decompiled_vendor_software/HiByTest`<br>`jadx_sources/HiByTest` | **Hardware Factory Diagnostic**: Diagnostic test harnesses for the Cirrus Logic DAC, rotary volume encoder, GPIO media keys, Pulsar LED patterns, and touch digitizer. |
| **Ride Mode Audio** (`com.qti.ridemode`) | `/product/app/RideModeAudio/RideModeAudio.apk` | `decompiled_vendor_software/RideModeAudio`<br>`jadx_sources/RideModeAudio` | **Automotive Audio Bridge**: High-gain, high-visibility UI designed for vehicle mounts and Bluetooth car units. |
| **HiBy Settings Mod** (`com.android.settings`) | `/system_ext/priv-app/Settings/Settings.apk` | `decompiled_vendor_software/Settings`<br>`jadx_sources/Settings` | **Custom System Settings**: Implements `FnFunctionSettings`, `HibySpeakerMutePreferenceController`, USB DAC In/Out toggles, and Gain modes. |
| **SystemUI Customizations** (`com.android.systemui`) | `/system_ext/priv-app/SystemUI/SystemUI.apk` | `decompiled_vendor_software/SystemUI`<br>`jadx_sources/SystemUI` | **Status Bar & Quick Settings**: Implements `PhoneStatusBarPolicy$7` (Fn switch broadcast listener), `RotationLockTile`, `VerticalRotationTile`, and status bar bitrate indicators. |
| **Miku Theme Overlay** (`android.overlay.hiby`) | `/vendor/overlay/FrameworksResM500_MIKU.apk` | `decompiled_vendor_software/FrameworksResM500_MIKU` | **Visual Styling**: Miku Hatsune color scheme, custom notification sounds, boot animations, and system accent assets. |
| **Settings Overlay** (`com.hiby.providers.settings.overlay`) | `/vendor/overlay/SettingsProviderM500_4G.apk` | `decompiled_vendor_software/SettingsProviderM500_4G` | **Factory Defaults**: Default settings for screen timeout, USB audio behavior, audio volume limits, and Fn key functions. |

---

### B. Core Framework Services (`/system/framework/services.jar`)

* **`com.android.server.input.InputManagerService`**:
  * Customized by HiBy to intercept the Linux kernel input switch event `SW_KEYPAD_SLIDE` (Bit 0x0A).
  * Encapsulates the switch state into a Bundle with extra boolean **`"fnCoverd"`**.
  * Broadcasts the system-wide intent **`FN_BUTTON_STATE_CHANGE`** with `Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT`.

---

### C. Native Audio HAL & Hardware Libraries (`/vendor/lib64/hw/`)

| Native Module | Path | Description |
|---|---|---|
| `audio.primary.bengal.so` | `/vendor/lib64/hw/audio.primary.bengal.so` | Qualcomm Bengal Primary Audio HAL (AudioReach: PAL and AGM underneath). DIRECT outputs skip Android's mixer, but PAL runs the wired outputs at 192 kHz for every track, so nothing reaches the DACs at 768kHz or at the file's own rate (corrected 2026-10-10, see docs/research/hiby-audio-knobs.md section 10). Also hardware DAC mute controls. |
| `sound_trigger.primary.bengal.so` | `/vendor/lib64/hw/sound_trigger.primary.bengal.so` | Hardware sound trigger and low-power audio DSP listener. |
| `audioflacapp` | `/vendor/bin/audioflacapp` | Native command-line lossless audio test pipeline. |
| `init.hiby.set.dwc3.rt.sh` | `/vendor/bin/init.hiby.set.dwc3.rt.sh` | Shell hook executed during USB enumeration to configure the Synopsys DWC3 USB controller for asynchronous USB DAC mode. |

---

## 3. Hardware Controller & Sysfs Interface Map

### A. Pulsar LED Indicator (Dual-Color Red + Blue)
The physical Pulsar indicator on the front panel is driven by an SG Micro `SGM31324` I2C LED driver and Qualcomm `soc:work_leds` GPIOs:

```text
/sys/class/leds/red/brightness               ➔ Direct Red LED PWM (0-255)
/sys/class/leds/blue/brightness              ➔ Direct Blue LED PWM (0-255)
/sys/class/leds/sgm31324-leds/brightness     ➔ SGM31324 Master Dimmer (0-255)
/sys/class/leds/sgm31324-leds/rgb_val        ➔ Formatted "R G B" write (maps to RB dies)
/sys/class/leds/sgm31324-leds/led_pattern    ➔ Hardware animation pattern index:
                                                0 = Off
                                                1 = Cyan / Miku Teal (Standby / Hi-Res)
                                                2 = Mint Green emulation
                                                3 = Teal standard
                                                4 = Amber emulation (Ultra Hi-Res)
                                                5 = Pure White emulation (DSD)
                                                6 = Red Breathing Pulse (Normal Charge)
                                                7 = Fast Red Flash (QC Fast Charge)
                                                10 = Magenta / Violet (MQA / Dual Mix)
```

> [!NOTE]
> Hardware verification confirms there is no physical green LED die soldered on the board. The physical LED package is a **Dual-Die (Red + Blue)** diode. Yellow/Green sample rate targets are represented via blended violet/cyan/purple PWM signatures.

---

### B. Hardware Keys & Input Subsystem
Mapped via Linux Input subsystem:

```text
/dev/input/event0 ➔ qpnp_pon       (Power button)
/dev/input/event1 ➔ ring-keys      (Rotary volume ring encoder)
/dev/input/event2 ➔ gpio-keys-hiby (Play/Pause, Next, Prev, Fn Switch)
/dev/input/event3 ➔ Goodix-CTP     (Capacitive touchscreen digitizer)
```

* **Physical Key Inhibition**:
  * Writing `sw_user` to `/sys/devices/platform/soc/soc:gpio_keys_hiby/disabled_keys` locks Prev, Play/Pause, and Next buttons while preserving the volume wheel.
  * Writing `all` locks all keys including the volume wheel.
  * Writing `none` unlocks all keys.

* **Touchscreen Inhibition**:
  * Managed via [`PocketLockService.kt`](file:///home/reaver/Documents/GitHub/m500/miku-player-kotlin/hardware-settings/src/main/java/com/m500/hardware/PocketLockService.kt) attaching an OS-wide modal `TYPE_APPLICATION_OVERLAY` `WindowManager` barrier swallowing 100% of touch events on all layers.

---

### C. Audio Output & Speaker Protection
* `/sys/devices/platform/soc/soc:hiby,sound-plat/mute`:
  * `speaker off` ➔ Unmutes standard audio line/speaker outputs.
  * `speaker on` ➔ Cuts analog line drive.
* System Property: `vendor.audio.hw.set.mute` (`"speaker off"` / `"speaker on"`).

---

## 4. Architectural Summary
Every single piece of custom vendor code, shared library, and hardware interface on the HiBy M500 is now identified, documented, and decompiled for ongoing system modification.
