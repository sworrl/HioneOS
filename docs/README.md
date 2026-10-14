# M500 DAP Documentation and Engineering Reports

Comprehensive hardware audits, telemetry reports, audio subsystem architecture, and system mod specifications for the HiBy M500 Hatsune Miku Edition (`M500_MIKU_4G`).

## Table of Contents

1. [01. Hardware & Sensors Audit Report](01_hardware_and_sensors_report.md)
   - Sensor inventory (22 active hardware sensors, confirmation of physical Ambient Light Sensor absence).
   - Display subsystem, Snapdragon 680 SoC architecture, thermal profiles, and battery drain analysis.
2. [02. Out-of-Box Telemetry & Network Audit](02_out_of_box_telemetry_audit.md)
   - Analysis of pre-installed apps, background telemetry sockets, and network egress behavior.
3. [03. HiBy M500 Audio Architecture Guide](03_audio_architecture_guide.md)
   - Dual CS43198 MasterHIFI DAC topology, the direct output path (direct through Android, 192 kHz at the DAC), digital filters, analog gain, and 4.4mm balanced output specifications.
4. [04. 24h Battery & Thermal Metrics Report](04_24h_battery_and_thermal_metrics.md)
   - Discharge rate benchmarks across idle, Wi-Fi transfer, screen-on playback, and DSD direct decode.
5. [05. Fn Hardware Switch System Mod Specification](05_fn_switch_system_mod_spec.md)
   - Kernel input inhibition subsystem, `fn_status` system settings key, HUD overlay, and physical lockout failsafe architecture.
6. [06. Native Rsync Multi-Transport Architecture](06_rsync_multi_transport_architecture.md)
   - Standalone root `rsyncd` daemon (port 8730), USB direct socket auto-routing (35 to 82 MB/s), and Wi-Fi link benchmarks.
7. [07. M500 Audio Manual (HTML Reference)](07_audio_manual.html)
   - Visual reference manual for DAC modes, sample rates, and hardware configurations.
8. [08. Architecture Directive: Pure Kotlin Target & Zero-Smali Policy](08_architecture_directive_pure_kotlin_target.md)
   - Long-term engineering policy mandating 100% native Kotlin codebase and retiring smali modifications once prototyped.
9. [09. MikuOS Signing & PKI Architecture Reference](09_mikuos_signing_and_pki_architecture.md)
   - Comprehensive PKI architecture, 1:1 bijective AOSP test-key replacement, dynamic Gradle signing without hardcoded secrets, standalone APK signing tool, OS rootfs re-signing, and AVB 2.0 / vbmeta verification.

