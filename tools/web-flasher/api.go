package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type PartitionImage struct {
	Name        string `json:"name"`
	Partition   string `json:"partition"`
	TargetSlot  string `json:"target_slot"` // "both", "a", "b", "none"
	Path        string `json:"path"`
	Size        int64  `json:"size"`
	SizeHuman   string `json:"size_human"`
	Sha256      string `json:"sha256,omitempty"`
	Description string `json:"description"`
	Required    bool   `json:"required"`
	IsSparse    bool   `json:"is_sparse"`
	Order       int    `json:"order"`
}

type CoreApk struct {
	Name        string `json:"name"`
	PackageName string `json:"package_name"`
	Version     string `json:"version"`
	Path        string `json:"path"`
	Size        int64  `json:"size"`
	SizeHuman   string `json:"size_human"`
	Description string `json:"description"`
	TargetDir   string `json:"target_dir"`
}

type FirmwareProfile struct {
	ID          string           `json:"id"`
	Name        string           `json:"name"`
	Tagline     string           `json:"tagline"`
	Version     string           `json:"version"`
	Type        string           `json:"type"` // "custom_rom", "stock_factory", "ota_update", "recovery"
	Description string           `json:"description"`
	Images      []PartitionImage `json:"images"`
	WipeData    bool             `json:"wipe_data"`
	TargetSlot  string           `json:"target_slot"`
}

type ManifestResponse struct {
	OSName          string            `json:"os_name"`
	Version         string            `json:"version"`
	TargetDevice    string            `json:"target_device"`
	HardwareDAC     string            `json:"hardware_dac"`
	KernelVersion   string            `json:"kernel_version"`
	SuperSizeBytes  int64             `json:"super_size_bytes"`
	Profiles        []FirmwareProfile `json:"profiles"`
	Images          []PartitionImage  `json:"images"`
	APKs            []CoreApk         `json:"apks"`
	ServerTimestamp int64             `json:"server_timestamp"`
}

type SystemStatusResponse struct {
	FastbootFound    bool              `json:"fastboot_found"`
	FastbootVersion  string            `json:"fastboot_version"`
	AdbFound         bool              `json:"adb_found"`
	AdbVersion       string            `json:"adb_version"`
	FastbootDevices  []string          `json:"fastboot_devices"`
	AdbDevices       []string          `json:"adb_devices"`
	SuperImageExists bool              `json:"super_image_exists"`
	SuperImageSize   int64             `json:"super_image_size"`
	FastbootVars     map[string]string `json:"fastboot_vars,omitempty"`
}

var (
	sseClientsMu sync.Mutex
	sseClients   = make(map[chan string]bool)
)

func RegisterAPIRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/status", handleSystemStatus)
	mux.HandleFunc("/api/manifest", handleManifest)
	mux.HandleFunc("/api/profiles", handleProfiles)
	mux.HandleFunc("/api/images/", handleImageStream)
	mux.HandleFunc("/api/apks/", handleApkStream)
	mux.HandleFunc("/api/logs/stream", handleSSELogs)
	mux.HandleFunc("/api/flash/host", handleHostFlash)
	mux.HandleFunc("/api/build/super", handleBuildSuper)
	mux.HandleFunc("/api/ota/push", handleHostOtaPush)
	mux.HandleFunc("/api/device/reboot", handleDeviceReboot)
	mux.HandleFunc("/api/unbrick/action", handleUnbrickAction)
	mux.HandleFunc("/api/fastboot/getvar-all", handleFastbootGetvarAll)
	mux.HandleFunc("/api/root/one-click", handleRootAction)
	mux.HandleFunc("/api/unroot/action", handleUnrootAction)
	mux.HandleFunc("/api/meta", handleMetadata)
}

func handleSystemStatus(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	status := SystemStatusResponse{
		FastbootDevices: []string{},
		AdbDevices:      []string{},
		FastbootVars:     make(map[string]string),
	}

	// Check fastboot
	fbPath, err := exec.LookPath("fastboot")
	if err == nil {
		status.FastbootFound = true
		out, err := exec.Command(fbPath, "--version").Output()
		if err == nil {
			status.FastbootVersion = strings.TrimSpace(strings.Split(string(out), "\n")[0])
		}
		// Check connected devices
		devOut, err := exec.Command(fbPath, "devices").Output()
		if err == nil {
			lines := strings.Split(strings.TrimSpace(string(devOut)), "\n")
			for _, line := range lines {
				if strings.TrimSpace(line) != "" {
					status.FastbootDevices = append(status.FastbootDevices, line)
				}
			}
		}
		// If fastboot device connected, fetch fastboot getvar all
		if len(status.FastbootDevices) > 0 {
			vOut, _ := exec.Command(fbPath, "getvar", "all").CombinedOutput()
			vLines := strings.Split(string(vOut), "\n")
			for _, vl := range vLines {
				vl = strings.TrimPrefix(vl, "(bootloader) ")
				parts := strings.SplitN(vl, ": ", 2)
				if len(parts) == 2 {
					status.FastbootVars[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
				}
			}
		}
	}

	// Check adb
	adbPath, err := exec.LookPath("adb")
	if err == nil {
		status.AdbFound = true
		out, err := exec.Command(adbPath, "--version").Output()
		if err == nil {
			status.AdbVersion = strings.TrimSpace(strings.Split(string(out), "\n")[0])
		}
		// Check connected adb devices
		devOut, err := exec.Command(adbPath, "devices").Output()
		if err == nil {
			lines := strings.Split(strings.TrimSpace(string(devOut)), "\n")
			for i, line := range lines {
				if i > 0 && strings.TrimSpace(line) != "" {
					status.AdbDevices = append(status.AdbDevices, line)
				}
			}
		}
	}

	// Check super.img
	superPath := filepath.Join(cfg.HioneOutDir, "hioneos_super.img")
	if fi, err := os.Stat(superPath); err == nil {
		status.SuperImageExists = true
		status.SuperImageSize = fi.Size()
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(status)
}

func getHioneOSCleanImages() []PartitionImage {
	images := []PartitionImage{
		{
			Name:        "boot.img",
			Partition:   "boot",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "boot.img"),
			Description: "Android 14 Qualcomm GKI Kernel & Ramdisk (Stock Official)",
			Required:    true,
			Order:       1,
		},
		{
			Name:        "init_boot.img",
			Partition:   "init_boot",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "init_boot.img"),
			Description: "Official Stock Non-Rooted Init Ramdisk",
			Required:    true,
			Order:       2,
		},
		{
			Name:        "dtbo.img",
			Partition:   "dtbo",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "dtbo.img"),
			Description: "Device Tree Blob Overlay (CS43198 DAC, SGM31324 RGB, Keypad Pinmux)",
			Required:    true,
			Order:       3,
		},
		{
			Name:        "vendor_boot.img",
			Partition:   "vendor_boot",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "vendor_boot.img"),
			Description: "Vendor Boot Drivers & Early Hardware Module Dependencies",
			Required:    true,
			Order:       4,
		},
		{
			Name:        "vbmeta_disabled.img",
			Partition:   "vbmeta",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.HioneOutDir, "vbmeta_disabled.img"),
			Description: "AVB 2.0 Verification Disabler (Flags=0x00000003 - Permits Custom ROMs)",
			Required:    true,
			Order:       5,
		},
		{
			Name:        "vbmeta_system_disabled.img",
			Partition:   "vbmeta_system",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.HioneOutDir, "vbmeta_system_disabled.img"),
			Description: "AVB 2.0 System Tree Disabler (Flags=0x00000003 - Permits Modified System/System_Ext)",
			Required:    true,
			Order:       6,
		},
		{
			Name:        "hioneos_super.img",
			Partition:   "super",
			TargetSlot:  "none",
			Path:        filepath.Join(cfg.HioneOutDir, "hioneos_super.img"),
			Description: "HioneOS Dynamic Partition Super Image (system, system_ext, product, vendor, odm)",
			Required:    true,
			Order:       7,
		},
	}

	var active []PartitionImage
	for _, img := range images {
		if fi, err := os.Stat(img.Path); err == nil {
			img.Size = fi.Size()
			img.SizeHuman = formatBytes(fi.Size())
			active = append(active, img)
		}
	}
	return active
}

func getHioneOSRootedImages() []PartitionImage {
	clean := getHioneOSCleanImages()
	magiskInitBoot := filepath.Join(cfg.RepoDir, "m500-system-archive", "firmware", "magisk_patched_init_boot.img")
	for i, img := range clean {
		if img.Partition == "init_boot" {
			clean[i].Name = "magisk_patched_init_boot.img"
			clean[i].Path = magiskInitBoot
			clean[i].Description = "Magisk v27.0 Patched Root Init Ramdisk (SU Privileges)"
			if fi, err := os.Stat(magiskInitBoot); err == nil {
				clean[i].Size = fi.Size()
				clean[i].SizeHuman = formatBytes(fi.Size())
			}
		}
	}
	return clean
}

func getStock100CleanImages() []PartitionImage {
	images := []PartitionImage{
		{
			Name:        "boot.img",
			Partition:   "boot",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "boot.img"),
			Description: "Official Stock HiBy 1.00 Kernel (Qualcomm Android 14 GKI)",
			Required:    true,
			Order:       1,
		},
		{
			Name:        "init_boot.img",
			Partition:   "init_boot",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "init_boot.img"),
			Description: "Official Stock HiBy 1.00 Init Boot Ramdisk (Non-Rooted)",
			Required:    true,
			Order:       2,
		},
		{
			Name:        "dtbo.img",
			Partition:   "dtbo",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "dtbo.img"),
			Description: "Official Stock HiBy 1.00 DTBO Hardware Overlay",
			Required:    true,
			Order:       3,
		},
		{
			Name:        "vendor_boot.img",
			Partition:   "vendor_boot",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "vendor_boot.img"),
			Description: "Official Stock HiBy 1.00 Vendor Boot Drivers",
			Required:    true,
			Order:       4,
		},
		{
			Name:        "vbmeta.img",
			Partition:   "vbmeta",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "vbmeta.img"),
			Description: "Official Stock Signed AVB 2.0 Root Key",
			Required:    true,
			Order:       5,
		},
		{
			Name:        "vbmeta_system.img",
			Partition:   "vbmeta_system",
			TargetSlot:  "both",
			Path:        filepath.Join(cfg.FirmwareDir, "vbmeta_system.img"),
			Description: "Official Stock Signed System AVB 2.0 Key",
			Required:    true,
			Order:       6,
		},
	}

	var active []PartitionImage
	for _, img := range images {
		if fi, err := os.Stat(img.Path); err == nil {
			img.Size = fi.Size()
			img.SizeHuman = formatBytes(fi.Size())
			active = append(active, img)
		}
	}
	return active
}

func getStock100RootedImages() []PartitionImage {
	clean := getStock100CleanImages()
	magiskInitBoot := filepath.Join(cfg.RepoDir, "m500-system-archive", "firmware", "magisk_patched_init_boot.img")
	for i, img := range clean {
		if img.Partition == "init_boot" {
			clean[i].Name = "magisk_patched_init_boot.img"
			clean[i].Path = magiskInitBoot
			clean[i].Description = "Magisk v27.0 Patched Root Init Ramdisk (SU Privileges)"
			if fi, err := os.Stat(magiskInitBoot); err == nil {
				clean[i].Size = fi.Size()
				clean[i].SizeHuman = formatBytes(fi.Size())
			}
		}
	}
	return clean
}

func getRootSwapEnableImages() []PartitionImage {
	magiskInitBoot := filepath.Join(cfg.RepoDir, "m500-system-archive", "firmware", "magisk_patched_init_boot.img")
	img := PartitionImage{
		Name:        "magisk_patched_init_boot.img",
		Partition:   "init_boot",
		TargetSlot:  "both",
		Path:        magiskInitBoot,
		Description: "Magisk v27.0 Patched Root Init Ramdisk (SU Privileges)",
		Required:    true,
		Order:       1,
	}
	if fi, err := os.Stat(magiskInitBoot); err == nil {
		img.Size = fi.Size()
		img.SizeHuman = formatBytes(fi.Size())
	}
	return []PartitionImage{img}
}

func getRootSwapDisableImages() []PartitionImage {
	stockInitBoot := filepath.Join(cfg.FirmwareDir, "init_boot.img")
	img := PartitionImage{
		Name:        "init_boot.img",
		Partition:   "init_boot",
		TargetSlot:  "both",
		Path:        stockInitBoot,
		Description: "Official Stock Non-Rooted Init Boot Ramdisk",
		Required:    true,
		Order:       1,
	}
	if fi, err := os.Stat(stockInitBoot); err == nil {
		img.Size = fi.Size()
		img.SizeHuman = formatBytes(fi.Size())
	}
	return []PartitionImage{img}
}

func getFirmwareProfiles() []FirmwareProfile {
	return []FirmwareProfile{
		{
			ID:          "hioneos-v0.1.0-clean",
			Name:        "🌟 HioneOS v0.1.0 (Non-Rooted Clean) [RECOMMENDED]",
			Tagline:     "Cyan Cyberpunk · CS43198 Direct HAL · Pixel Gestures · Clean Stock Kernel",
			Version:     "v0.1.0-clean",
			Type:        "custom_rom",
			Description: "Full custom OS installation with 100% clean stock non-rooted kernel, AVB disablers, dynamic HioneOS system container, debloated apps, and auto-ADB.",
			Images:      getHioneOSCleanImages(),
			WipeData:    true,
			TargetSlot:  "a",
		},
		{
			ID:          "hioneos-v0.1.0-rooted",
			Name:        "⚡ HioneOS v0.1.0 (Magisk Rooted & SU Enabled)",
			Tagline:     "Cyan Cyberpunk · Magisk v27.0 Patched init_boot · Full SU Privileges",
			Version:     "v0.1.0-magisk",
			Type:        "custom_rom",
			Description: "Full custom OS installation with Magisk-patched init_boot for systemless root access, kernel modules, and SU privileges.",
			Images:      getHioneOSRootedImages(),
			WipeData:    true,
			TargetSlot:  "a",
		},
		{
			ID:          "hiby-stock-1.00",
			Name:        "🏛️ Official HiBy Factory Firmware v1.00 (Non-Rooted Stock)",
			Tagline:     "Stock HiByMusic · Factory AVB · Stock Launcher3 & SystemUI",
			Version:     "1.00_2024",
			Type:        "stock_factory",
			Description: "Restores official factory bootchain and signed AVB keys. Returns device to pure stock non-rooted factory state.",
			Images:      getStock100CleanImages(),
			WipeData:    true,
			TargetSlot:  "a",
		},
		{
			ID:          "hiby-stock-1.00-rooted",
			Name:        "⚡ Official HiBy Factory Firmware v1.00 (Magisk Rooted)",
			Tagline:     "Stock HiBy Firmware · Magisk Patched init_boot · Factory Reset",
			Version:     "1.00_2024-magisk",
			Type:        "stock_factory",
			Description: "Restores official factory firmware with Magisk root integration pre-applied.",
			Images:      getStock100RootedImages(),
			WipeData:    true,
			TargetSlot:  "a",
		},
		{
			ID:          "root-swap-enable",
			Name:        "⚡ Root Toggle: Enable Magisk Root (No Data Wipe)",
			Tagline:     "Flashes Magisk patched init_boot across Slots A & B without wiping data",
			Version:     "magisk-v27",
			Type:        "recovery",
			Description: "Swaps init_boot to Magisk rooted kernel. Does NOT wipe any user data or apps.",
			Images:      getRootSwapEnableImages(),
			WipeData:    false,
			TargetSlot:  "a",
		},
		{
			ID:          "root-swap-disable",
			Name:        "🛡️ Root Toggle: Return to Clean Stock Kernel (No Data Wipe)",
			Tagline:     "Flashes Stock non-rooted init_boot across Slots A & B without wiping data",
			Version:     "stock-1.00",
			Type:        "recovery",
			Description: "Swaps init_boot back to clean stock non-rooted kernel. Does NOT wipe any user data.",
			Images:      getRootSwapDisableImages(),
			WipeData:    false,
			TargetSlot:  "a",
		},
		{
			ID:          "hioneos-recovery",
			Name:        "🛡️ AVB & Bootchain Emergency Repair",
			Tagline:     "Fixes 'Device is corrupt' & boot loops without touching user storage",
			Version:     "v0.1.0-fix",
			Type:        "recovery",
			Description: "Flashes AVB 2.0 verification disablers and official stock kernel across slots A and B. Preserves user data.",
			Images:      getHioneOSCleanImages()[:6],
			WipeData:    false,
			TargetSlot:  "a",
		},
	}
}

func handleProfiles(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(getFirmwareProfiles())
}

func handleManifest(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	apks := []CoreApk{
		{
			Name:        "HioneOS_Launcher.apk",
			PackageName: "com.hione.launcher",
			Version:     "0.1.0",
			Path:        latestApk(filepath.Join(cfg.ApkDir, "hioneos-launcher", "build", "outputs", "apk", "release", "HioneOS_Launcher-v*.apk")),
			Description: "Hatsune Hione Jetpack Compose Cyber Launcher & System Gesture Navigation",
			TargetDir:   "/system/app/HioneLauncher/",
		},
		{
			Name:        "HioneMusic.apk",
			PackageName: "com.hione.player",
			Version:     "0.9.223",
			Path:        latestApk(filepath.Join(cfg.ApkDir, "app", "build", "outputs", "apk", "*", "HioneMusic-v*.apk")),
			Description: "Hione Music Bit-Perfect Audiophile Player, CS43198 Direct ALSA HAL & OnThe8s Weather",
			TargetDir:   "/system/app/HioneMusic/",
		},
		{
			Name:        "HioneOS_Settings.apk",
			PackageName: "com.hione.settings",
			Version:     "0.1.0",
			Path:        latestApk(filepath.Join(cfg.ApkDir, "hioneos-settings", "build", "outputs", "apk", "release", "HioneOS_Settings-v*.apk")),
			Description: "HioneOS Hardware Settings (Cirrus Logic DAC Filter, Gain, SGM31324 Pulsar RGB, Fn Pocket Lock)",
			TargetDir:   "/system/app/HioneSettings/",
		},
		{
			Name:        "HioneOS_SystemUI.apk",
			PackageName: "com.android.systemui",
			Version:     "0.1.0",
			Path:        latestApk(filepath.Join(cfg.ApkDir, "hioneos-systemui", "build", "outputs", "apk", "release", "HioneOS_SystemUI-v*.apk")),
			Description: "HioneOS SystemUI Quick Settings Shade & Status Telemetry Bar",
			TargetDir:   "/system/app/HioneSystemUI/",
		},
	}

	var activeApks []CoreApk
	for _, apk := range apks {
		if fi, err := os.Stat(apk.Path); err == nil {
			apk.Size = fi.Size()
			apk.SizeHuman = formatBytes(fi.Size())
			activeApks = append(activeApks, apk)
		}
	}

	manifest := ManifestResponse{
		OSName:          "HioneOS for HiBy M500",
		Version:         "v0.1.0 (Cyber Hatsune Hione Edition)",
		TargetDevice:    "HiBy M500 (Qualcomm Snapdragon SoC)",
		HardwareDAC:     "Cirrus Logic Dual CS43198 MasterHIFI™",
		KernelVersion:   "5.4.233-android14-gki",
		SuperSizeBytes:  4831838208,
		Profiles:        getFirmwareProfiles(),
		Images:          getHioneOSCleanImages(),
		APKs:            activeApks,
		ServerTimestamp: time.Now().Unix(),
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(manifest)
}

func handleImageStream(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	imgName := strings.TrimPrefix(r.URL.Path, "/api/images/")
	imgName = filepath.Clean(imgName)

	var targetPath string
	if strings.Contains(imgName, "super") || strings.Contains(imgName, "disabled") {
		targetPath = filepath.Join(cfg.HioneOutDir, imgName)
	} else {
		targetPath = filepath.Join(cfg.FirmwareDir, imgName)
	}

	if _, err := os.Stat(targetPath); err != nil {
		targetPath = filepath.Join(cfg.HioneOutDir, imgName)
	}

	file, err := os.Open(targetPath)
	if err != nil {
		http.Error(w, fmt.Sprintf("Image not found: %s", imgName), http.StatusNotFound)
		return
	}
	defer file.Close()

	fi, err := file.Stat()
	if err != nil {
		http.Error(w, "Failed to stat image", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", imgName))
	w.Header().Set("Accept-Ranges", "bytes")
	http.ServeContent(w, r, imgName, fi.ModTime(), file)
}

func handleApkStream(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	apkName := strings.TrimPrefix(r.URL.Path, "/api/apks/")
	apkName = filepath.Clean(apkName)

	var targetPath string
	switch apkName {
	case "HioneOS_Launcher.apk":
		targetPath = latestApk(filepath.Join(cfg.ApkDir, "hioneos-launcher", "build", "outputs", "apk", "release", "HioneOS_Launcher-v*.apk"))
	case "HioneMusic.apk":
		targetPath = latestApk(filepath.Join(cfg.ApkDir, "app", "build", "outputs", "apk", "*", "HioneMusic-v*.apk"))
	case "HioneOS_Settings.apk":
		targetPath = latestApk(filepath.Join(cfg.ApkDir, "hioneos-settings", "build", "outputs", "apk", "release", "HioneOS_Settings-v*.apk"))
	case "HioneOS_SystemUI.apk":
		targetPath = latestApk(filepath.Join(cfg.ApkDir, "hioneos-systemui", "build", "outputs", "apk", "release", "HioneOS_SystemUI-v*.apk"))
	default:
		http.Error(w, "Unknown APK", http.StatusNotFound)
		return
	}

	file, err := os.Open(targetPath)
	if err != nil {
		http.Error(w, fmt.Sprintf("APK file not found on server: %s", apkName), http.StatusNotFound)
		return
	}
	defer file.Close()

	fi, _ := file.Stat()
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"%s\"", apkName))
	http.ServeContent(w, r, apkName, fi.ModTime(), file)
}

func handleSSELogs(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	msgChan := make(chan string, 100)
	sseClientsMu.Lock()
	sseClients[msgChan] = true
	sseClientsMu.Unlock()

	defer func() {
		sseClientsMu.Lock()
		delete(sseClients, msgChan)
		close(msgChan)
		sseClientsMu.Unlock()
	}()

	fmt.Fprintf(w, "data: %s\n\n", "CONNECTED")
	flusher.Flush()

	notify := r.Context().Done()
	for {
		select {
		case <-notify:
			return
		case msg := <-msgChan:
			fmt.Fprintf(w, "data: %s\n\n", msg)
			flusher.Flush()
		}
	}
}

func broadcastSSE(msg string) {
	sseClientsMu.Lock()
	defer sseClientsMu.Unlock()
	for ch := range sseClients {
		select {
		case ch <- msg:
		default:
		}
	}
}

func handleHostFlash(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	profileID := r.URL.Query().Get("profile")
	if profileID == "" {
		profileID = "hioneos-v0.1.0"
	}

	go runHostFlashPipeline(profileID)

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "started", "message": fmt.Sprintf("Flashing profile %s started", profileID)})
}

func handleBuildSuper(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	go runBuildSuperPipeline()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "started", "message": "super.img compilation pipeline initiated"})
}

func handleHostOtaPush(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	go runHostOtaPushPipeline()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "started", "message": "OTA push pipeline initiated"})
}

func handleDeviceReboot(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	target := r.URL.Query().Get("target") // "bootloader", "system", "recovery", "fastboot"
	var cmd *exec.Cmd
	if target == "bootloader" {
		cmd = exec.Command("adb", "reboot", "bootloader")
	} else if target == "fastboot" {
		cmd = exec.Command("fastboot", "reboot", "fastboot")
	} else if target == "recovery" {
		cmd = exec.Command("adb", "reboot", "recovery")
	} else {
		cmd = exec.Command("fastboot", "reboot")
	}

	out, err := cmd.CombinedOutput()
	res := map[string]interface{}{
		"success": err == nil,
		"output":  string(out),
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(res)
}

func handleFastbootGetvarAll(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	out, err := exec.Command("fastboot", "getvar", "all").CombinedOutput()
	vars := make(map[string]string)
	if err == nil {
		lines := strings.Split(string(out), "\n")
		for _, l := range lines {
			l = strings.TrimPrefix(l, "(bootloader) ")
			parts := strings.SplitN(l, ": ", 2)
			if len(parts) == 2 {
				vars[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
			}
		}
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"success": err == nil,
		"vars":    vars,
		"raw":     string(out),
	})
}

func handleUnbrickAction(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}

	action := r.URL.Query().Get("action")
	var cmd *exec.Cmd
	switch action {
	case "switch-slot-a":
		cmd = exec.Command("fastboot", "set_active", "a")
	case "switch-slot-b":
		cmd = exec.Command("fastboot", "set_active", "b")
	case "enter-fastbootd":
		cmd = exec.Command("fastboot", "reboot", "fastboot")
	case "erase-userdata":
		cmd = exec.Command("fastboot", "erase", "userdata")
	case "erase-metadata":
		cmd = exec.Command("fastboot", "erase", "metadata")
	case "flash-avb-fix":
		vbmetaDisabled := filepath.Join(cfg.HioneOutDir, "vbmeta_disabled.img")
		vbmetaSysDisabled := filepath.Join(cfg.HioneOutDir, "vbmeta_system_disabled.img")
		_ = exec.Command("fastboot", "flash", "vbmeta_a", vbmetaDisabled).Run()
		_ = exec.Command("fastboot", "flash", "vbmeta_b", vbmetaDisabled).Run()
		_ = exec.Command("fastboot", "flash", "vbmeta_system_a", vbmetaSysDisabled).Run()
		cmd = exec.Command("fastboot", "flash", "vbmeta_system_b", vbmetaSysDisabled)
	default:
		http.Error(w, "Unknown unbrick action", http.StatusBadRequest)
		return
	}

	out, err := cmd.CombinedOutput()
	res := map[string]interface{}{
		"action":  action,
		"success": err == nil,
		"output":  string(out),
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(res)
}

func handleRootAction(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}
	logEvent("STEP", "Initiating HioneOS 1-Click Magisk Root Installation...")
	scriptPath := filepath.Join(cfg.RepoDir, "tools", "root_device.sh")
	cmd := exec.Command("/bin/bash", scriptPath)
	out, err := cmd.CombinedOutput()
	outStr := string(out)
	logEvent("INFO", fmt.Sprintf("Root execution result:\n%s", outStr))

	res := map[string]interface{}{
		"success": err == nil,
		"output":  outStr,
		"status":  "Root installation executed",
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(res)
}

func handleUnrootAction(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}
	logEvent("STEP", "Initiating HioneOS 1-Click Clean Unroot & Stock Restore...")
	scriptPath := filepath.Join(cfg.RepoDir, "tools", "unroot_device.sh")
	cmd := exec.Command("/bin/bash", scriptPath)
	out, err := cmd.CombinedOutput()
	outStr := string(out)
	logEvent("INFO", fmt.Sprintf("Unroot execution result:\n%s", outStr))

	res := map[string]interface{}{
		"success": err == nil,
		"output":  outStr,
		"status":  "Stock init_boot restored",
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(res)
}

func handleMetadata(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		return
	}
	meta := map[string]interface{}{
		"domain":            "hioneos.falcontechnix.com",
		"os_name":           "HioneOS for HiBy M500",
		"version":           "1.0.0-hioneos",
		"version_code":      1000,
		"release_date":      "2026-08-23",
		"target_hardware":   "HiBy M500 Digital Audio Player",
		"soc":               "Qualcomm Snapdragon 665 / 680 (bengal/trinket)",
		"dac":               "Dual Cirrus Logic CS43198 MasterHIFI™ (32-bit/768kHz, Native DSD256)",
		"rgb_controller":    "SGM31324 Pulsar Breath Breathing Engine",
		"features": []string{
			"Bit-Perfect Direct ALSA HAL Audio Pipeline",
			"Real-Time ProjectM 3.1.12 OpenGL Shader Visualizer",
			"Kawaii Retro Tape Deck Mode with Interactive Physics",
			"Dual-Band Transient Perceptual BPM Analysis Observatory",
			"Smart ScreenTime Sensor Fusion Attention Engine",
			"Diva Dock with Tactile Haptic App Switching",
			"1-Click Magisk Root & Bootchain Unbrick Suite",
		},
		"apps": []map[string]string{
			{"name": "Hione Music", "package": "com.hione.player", "version": "1.0.0-hioneos", "badge": "Audiophile Core"},
			{"name": "HioneOS Launcher", "package": "com.hione.launcher", "version": "1.0.0-hioneos", "badge": "Diva Desktop"},
			{"name": "HioneOS SystemUI", "package": "com.hione.systemui", "version": "1.0.0-hioneos", "badge": "Quick Shade"},
			{"name": "HioneOS Settings", "package": "com.hione.settings", "version": "1.0.0-hioneos", "badge": "DAC & Hardware"},
			{"name": "Hione FM Radio", "package": "com.caf.fmradio", "version": "1.0.0-hioneos", "badge": "Snapdragon FM"},
		},
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(meta)
}

func setCORS(w http.ResponseWriter) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Range")
	w.Header().Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
}

func formatBytes(b int64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.2f %cB", float64(b)/float64(div), "KMGTPE"[exp])
}
