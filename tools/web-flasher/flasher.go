package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

func logEvent(level, msg string) {
	ts := time.Now().Format("15:04:05.000")
	formatted := fmt.Sprintf("[%s] [%s] %s", ts, level, msg)
	broadcastSSE(formatted)
}

func runHostFlashPipeline(profileID string) {
	logEvent("INFO", fmt.Sprintf("🚀 Starting Host Flash Pipeline for profile [%s]...", profileID))

	out, err := exec.Command("fastboot", "devices").Output()
	if err != nil || len(strings.TrimSpace(string(out))) == 0 {
		logEvent("ERROR", "❌ No device found in fastboot mode. Connect HiBy M500 in fastboot.")
		return
	}
	devID := strings.Fields(string(out))[0]
	logEvent("SUCCESS", fmt.Sprintf("✅ Detected Fastboot Target: %s", devID))

	execFb := func(args ...string) bool {
		fullArgs := append([]string{"-s", devID}, args...)
		cmdStr := "fastboot " + strings.Join(args, " ")
		logEvent("FLASH", fmt.Sprintf("▶ %s", cmdStr))

		cmd := exec.Command("fastboot", fullArgs...)
		stdout, _ := cmd.StdoutPipe()
		stderr, _ := cmd.StderrPipe()
		_ = cmd.Start()

		go func() {
			scanner := bufio.NewScanner(stdout)
			for scanner.Scan() {
				logEvent("INFO", scanner.Text())
			}
		}()
		go func() {
			scanner := bufio.NewScanner(stderr)
			for scanner.Scan() {
				logEvent("INFO", scanner.Text())
			}
		}()

		if err := cmd.Wait(); err != nil {
			logEvent("ERROR", fmt.Sprintf("❌ Command failed: %v", err))
			return false
		}
		logEvent("OKAY", fmt.Sprintf("✔ OKAY [fastboot %s]", args[0]))
		return true
	}

	bootImg := filepath.Join(cfg.FirmwareDir, "boot.img")
	stockInitBootImg := filepath.Join(cfg.FirmwareDir, "init_boot.img")
	magiskInitBootImg := filepath.Join(cfg.RepoDir, "m500-system-archive", "firmware", "magisk_patched_init_boot.img")
	dtboImg := filepath.Join(cfg.FirmwareDir, "dtbo.img")
	vendorBootImg := filepath.Join(cfg.FirmwareDir, "vendor_boot.img")
	vbmetaDisabled := filepath.Join(cfg.HioneOutDir, "vbmeta_disabled.img")
	vbmetaSysDisabled := filepath.Join(cfg.HioneOutDir, "vbmeta_system_disabled.img")
	stockVb := filepath.Join(cfg.FirmwareDir, "vbmeta.img")
	stockVbSys := filepath.Join(cfg.FirmwareDir, "vbmeta_system.img")
	superImg := filepath.Join(cfg.HioneOutDir, "hioneos_super.img")

	switch profileID {
	case "hiby-stock-1.00", "hiby-stock-1.00-rooted":
		isRooted := profileID == "hiby-stock-1.00-rooted"
		targetInitBoot := stockInitBootImg
		if isRooted {
			targetInitBoot = magiskInitBootImg
			logEvent("STEP", "[1/3] Flashing Official HiBy Kernel & Magisk Rooted Init Ramdisk...")
		} else {
			logEvent("STEP", "[1/3] Flashing Official Stock Non-Rooted Kernel & Bootchain...")
		}

		if !execFb("flash", "boot_a", bootImg) || !execFb("flash", "boot_b", bootImg) { return }
		if !execFb("flash", "init_boot_a", targetInitBoot) || !execFb("flash", "init_boot_b", targetInitBoot) { return }
		if !execFb("flash", "dtbo_a", dtboImg) || !execFb("flash", "dtbo_b", dtboImg) { return }
		if !execFb("flash", "vendor_boot_a", vendorBootImg) || !execFb("flash", "vendor_boot_b", vendorBootImg) { return }

		logEvent("STEP", "[2/3] Flashing Official Factory Signed AVB Keys...")
		if !execFb("flash", "vbmeta_a", stockVb) || !execFb("flash", "vbmeta_b", stockVb) { return }
		if !execFb("flash", "vbmeta_system_a", stockVbSys) || !execFb("flash", "vbmeta_system_b", stockVbSys) { return }

		logEvent("STEP", "[3/3] Setting Slot A & Erasing Userdata for Factory Reset...")
		execFb("set_active", "a")
		execFb("erase", "userdata")
		execFb("erase", "metadata")
		logEvent("SUCCESS", "🎉 Stock Factory Restore Complete! Rebooting device...")
		execFb("reboot")

	case "root-swap-enable":
		logEvent("STEP", "[1/1] Swapping Init Boot to Magisk Rooted Kernel (Zero Data Loss)...")
		if !execFb("flash", "init_boot_a", magiskInitBootImg) || !execFb("flash", "init_boot_b", magiskInitBootImg) { return }
		execFb("set_active", "a")
		logEvent("SUCCESS", "⚡ Magisk Root Enabled! (Userdata Preserved). Rebooting...")
		execFb("reboot")

	case "root-swap-disable":
		logEvent("STEP", "[1/1] Swapping Init Boot back to Clean Stock Kernel (Zero Data Loss)...")
		if !execFb("flash", "init_boot_a", stockInitBootImg) || !execFb("flash", "init_boot_b", stockInitBootImg) { return }
		execFb("set_active", "a")
		logEvent("SUCCESS", "🛡️ Returned to Clean Stock Kernel! (Userdata Preserved). Rebooting...")
		execFb("reboot")

	case "hioneos-recovery":
		logEvent("STEP", "[1/2] Flashing Clean Kernel & Boot Partitions...")
		if !execFb("flash", "boot_a", bootImg) || !execFb("flash", "boot_b", bootImg) { return }
		if !execFb("flash", "init_boot_a", stockInitBootImg) || !execFb("flash", "init_boot_b", stockInitBootImg) { return }
		if !execFb("flash", "dtbo_a", dtboImg) || !execFb("flash", "dtbo_b", dtboImg) { return }
		if !execFb("flash", "vendor_boot_a", vendorBootImg) || !execFb("flash", "vendor_boot_b", vendorBootImg) { return }

		logEvent("STEP", "[2/2] Flashing AVB 2.0 Verification Disabler (flags=0x03)...")
		if !execFb("flash", "vbmeta_a", vbmetaDisabled) || !execFb("flash", "vbmeta_b", vbmetaDisabled) { return }
		if !execFb("flash", "vbmeta_system_a", vbmetaSysDisabled) || !execFb("flash", "vbmeta_system_b", vbmetaSysDisabled) { return }

		execFb("set_active", "a")
		logEvent("SUCCESS", "🎉 AVB & Bootchain Recovery Complete! (Userdata Preserved). Rebooting...")
		execFb("reboot")

	default: // "hioneos-v0.1.0-clean", "hioneos-v0.1.0-rooted", "hioneos-v0.1.0"
		isRooted := profileID == "hioneos-v0.1.0-rooted"
		targetInitBoot := stockInitBootImg
		if isRooted {
			targetInitBoot = magiskInitBootImg
			logEvent("STEP", "[1/4] Flashing Qualcomm Kernel & Magisk Rooted Init Ramdisk...")
		} else {
			logEvent("STEP", "[1/4] Flashing Qualcomm Kernel & Clean Non-Rooted Ramdisk...")
		}

		if !execFb("flash", "boot_a", bootImg) || !execFb("flash", "boot_b", bootImg) { return }
		if !execFb("flash", "init_boot_a", targetInitBoot) || !execFb("flash", "init_boot_b", targetInitBoot) { return }
		if !execFb("flash", "dtbo_a", dtboImg) || !execFb("flash", "dtbo_b", dtboImg) { return }
		if !execFb("flash", "vendor_boot_a", vendorBootImg) || !execFb("flash", "vendor_boot_b", vendorBootImg) { return }

		logEvent("STEP", "[2/4] Flashing AVB 2.0 Verification Disablers (Flags=0x00000003)...")
		if !execFb("flash", "vbmeta_a", vbmetaDisabled) || !execFb("flash", "vbmeta_b", vbmetaDisabled) { return }
		if !execFb("flash", "vbmeta_system_a", vbmetaSysDisabled) || !execFb("flash", "vbmeta_system_b", vbmetaSysDisabled) { return }

		logEvent("STEP", "[3/4] Flashing Dynamic super.img Container (~4.8 GB with -S 400M Chunking)...")
		if !execFb("-S", "400M", "flash", "super", superImg) { return }

		logEvent("STEP", "[4/4] Activating Slot A & Formatting Userdata...")
		execFb("set_active", "a")
		execFb("erase", "userdata")
		execFb("erase", "metadata")

		if isRooted {
			logEvent("SUCCESS", "🎉 HioneOS v0.1.0 (Rooted) Installation Successful! Rebooting device...")
		} else {
			logEvent("SUCCESS", "🎉 HioneOS v0.1.0 (Clean Non-Rooted) Installation Successful! Rebooting device...")
		}
		execFb("reboot")
	}
}

func runBuildSuperPipeline() {
	logEvent("BUILD", "⚙ Compiling HioneOS Dynamic Super Image...")
	buildScript := filepath.Join(cfg.RepoDir, "hioneos", "build", "build_hioneos_super.sh")

	cmd := exec.Command(buildScript)
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()
	_ = cmd.Start()

	go func() {
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			logEvent("BUILD", scanner.Text())
		}
	}()
	go func() {
		scanner := bufio.NewScanner(stderr)
		for scanner.Scan() {
			logEvent("BUILD", scanner.Text())
		}
	}()

	if err := cmd.Wait(); err != nil {
		logEvent("ERROR", fmt.Sprintf("❌ Build failed: %v", err))
		return
	}
	logEvent("SUCCESS", "✨ HioneOS super.img successfully compiled and ready for flashing!")
}

// latestApk resolves a glob pattern to the most recently modified match, so the
// OTA pipeline never breaks when versionName bumps rename the output APK.
func latestApk(pattern string) string {
	matches, err := filepath.Glob(pattern)
	if err != nil || len(matches) == 0 {
		return pattern // fall through; install will fail with a clear path in the log
	}
	best := matches[0]
	bestTime := int64(0)
	for _, m := range matches {
		if fi, err := os.Stat(m); err == nil && fi.ModTime().UnixNano() > bestTime {
			bestTime = fi.ModTime().UnixNano()
			best = m
		}
	}
	return best
}

// deviceIsRooted reports whether the booted device is running the Magisk-patched
// init_boot (the magisk binary is only mounted on a rooted boot).
func deviceIsRooted() bool {
	out, err := exec.Command("adb", "shell", "command", "-v", "magisk").Output()
	return err == nil && len(strings.TrimSpace(string(out))) > 0
}

func runHostOtaPushPipeline() {
	logEvent("OTA", "📦 Starting Live HioneOS Core APKs Update via ADB...")

	out, err := exec.Command("adb", "devices").Output()
	if err != nil || len(strings.TrimSpace(string(out))) == 0 {
		logEvent("ERROR", "❌ No ADB device detected. Ensure USB Debugging is enabled in HioneOS Settings.")
		return
	}

	apks := []struct {
		name string
		path string
	}{
		{"HioneOS_Launcher", latestApk(filepath.Join(cfg.ApkDir, "hioneos-launcher", "build", "outputs", "apk", "release", "HioneOS_Launcher-v*.apk"))},
		{"HioneMusic", latestApk(filepath.Join(cfg.ApkDir, "app", "build", "outputs", "apk", "*", "HioneMusic-v*.apk"))},
		{"HioneOS_Settings", latestApk(filepath.Join(cfg.ApkDir, "hioneos-settings", "build", "outputs", "apk", "release", "HioneOS_Settings-v*.apk"))},
		{"HioneOS_SystemUI", latestApk(filepath.Join(cfg.ApkDir, "hioneos-systemui", "build", "outputs", "apk", "release", "HioneOS_SystemUI-v*.apk"))},
	}

	// Rooted boot ⇒ Magisk manager is mandatory, not optional. Without the
	// companion app the su daemon has no UI and root requests silently pile up.
	if deviceIsRooted() {
		magiskApk := latestApk(filepath.Join(cfg.RepoDir, "m500-system-archive", "tools", "magisk", "Magisk-v*.apk"))
		apks = append(apks, struct {
			name string
			path string
		}{"Magisk_Manager", magiskApk})
		logEvent("OTA", "🔓 Rooted boot detected — Magisk Manager will be installed automatically.")
	}

	for _, apk := range apks {
		logEvent("OTA", fmt.Sprintf("📲 Installing %s...", apk.name))
		cmd := exec.Command("adb", "install", "-r", "-d", apk.path)
		out, err := cmd.CombinedOutput()
		if err != nil {
			logEvent("ERROR", fmt.Sprintf("Failed to install %s: %s", apk.name, string(out)))
		} else {
			logEvent("SUCCESS", fmt.Sprintf("✔ Installed %s: %s", apk.name, strings.TrimSpace(string(out))))
		}
	}
	logEvent("SUCCESS", "🎉 Core System APKs OTA Update Complete!")
}
