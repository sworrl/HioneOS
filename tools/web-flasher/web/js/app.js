/**
 * 🌟 HioneOS Production Web Flasher Controller 🌟
 * Coordinates Firmware Profiles, WebUSB Fastboot, Hardware Telemetry & Unbricking.
 */

document.addEventListener("DOMContentLoaded", () => {
    const fastboot = new FastbootWebUSB();
    let currentManifest = null;
    let availableProfiles = [];
    let selectedProfile = null;

    // UI Elements
    const terminalLogs = document.getElementById("terminalLogs");
    const profileSelect = document.getElementById("profileSelect");
    const profileBadge = document.getElementById("profileBadge");
    const profileDesc = document.getElementById("profileDesc");
    const connectionBadge = document.getElementById("connectionBadge");
    const deviceModelText = document.getElementById("deviceModelText");
    const deviceDetailsText = document.getElementById("deviceDetailsText");
    const partitionTableBody = document.getElementById("partitionTableBody");
    const progressBox = document.getElementById("progressBox");
    const progressFill = document.getElementById("progressFill");
    const progressStatusText = document.getElementById("progressStatusText");
    const progressPercentText = document.getElementById("progressPercentText");
    const progressBytesText = document.getElementById("progressBytesText");
    const progressSpeedText = document.getElementById("progressSpeedText");
    const opStatus = document.getElementById("opStatus");

    // Telemetry Elements
    const tvProduct = document.getElementById("tvProduct");
    const tvSerial = document.getElementById("tvSerial");
    const tvSlot = document.getElementById("tvSlot");
    const tvUnlocked = document.getElementById("tvUnlocked");
    const tvMode = document.getElementById("tvMode");
    const tvBuffer = document.getElementById("tvBuffer");

    // Buttons
    const btnWebUSB = document.getElementById("btnWebUSB");
    const btnRefreshHost = document.getElementById("btnRefreshHost");
    const btnFlashWebUSB = document.getElementById("btnFlashWebUSB");
    const btnFlashHost = document.getElementById("btnFlashHost");
    const btnOtaApkPush = document.getElementById("btnOtaApkPush");
    const btnClearLogs = document.getElementById("btnClearLogs");
    const btnCopyLogs = document.getElementById("btnCopyLogs");

    // Unbrick Toolkit Buttons
    const btnSwitchSlotA = document.getElementById("btnSwitchSlotA");
    const btnSwitchSlotB = document.getElementById("btnSwitchSlotB");
    const btnFastbootd = document.getElementById("btnFastbootd");
    const btnAvbFix = document.getElementById("btnAvbFix");
    const btnEraseData = document.getElementById("btnEraseData");
    const btnRebootSystem = document.getElementById("btnRebootSystem");

    function log(level, msg) {
        const line = document.createElement("div");
        line.className = `log-line ${level.toLowerCase()}`;
        const ts = new Date().toLocaleTimeString();
        line.textContent = `[${ts}] [${level}] ${msg}`;
        terminalLogs.appendChild(line);
        terminalLogs.scrollTop = terminalLogs.scrollHeight;
    }

    fastboot.onLog = log;
    fastboot.onProgress = (percent, speedMb, transferred, total, status) => {
        progressBox.style.display = "block";
        progressFill.style.width = `${percent}%`;
        progressPercentText.textContent = `${percent}%`;
        progressStatusText.textContent = status;
        progressBytesText.textContent = `${(transferred / 1024 / 1024).toFixed(1)} MB / ${(total / 1024 / 1024).toFixed(1)} MB`;
        progressSpeedText.textContent = `${speedMb} MB/s`;
    };

    // Connect SSE stream for host pipeline logs
    const sse = new EventSource("/api/logs/stream");
    sse.onmessage = (e) => {
        if (e.data === "CONNECTED") return;
        const line = document.createElement("div");
        line.className = "log-line";
        if (e.data.includes("[SUCCESS]")) line.className += " success";
        else if (e.data.includes("[ERROR]")) line.className += " error";
        else if (e.data.includes("[FLASH]")) line.className += " flash";
        else if (e.data.includes("[STEP]")) line.className += " step";
        else if (e.data.includes("[OKAY]")) line.className += " okay";
        else line.className += " info";
        line.textContent = e.data;
        terminalLogs.appendChild(line);
        terminalLogs.scrollTop = terminalLogs.scrollHeight;
    };

    // Load Manifest and Profiles
    async function loadManifest() {
        try {
            const resp = await fetch("/api/manifest");
            currentManifest = await resp.json();
            availableProfiles = currentManifest.profiles || [];
            updateProfileDropdown();
            renderPartitionTable();
        } catch (err) {
            log("ERROR", "Failed to load firmware manifest: " + err.message);
        }
    }

    function updateProfileDropdown() {
        if (!availableProfiles || availableProfiles.length === 0) return;
        profileSelect.innerHTML = "";
        availableProfiles.forEach(p => {
            const opt = document.createElement("option");
            opt.value = p.id;
            opt.textContent = p.name;
            profileSelect.appendChild(opt);
        });
        selectProfile(availableProfiles[0].id);
    }

    function selectProfile(profileId) {
        selectedProfile = availableProfiles.find(p => p.id === profileId) || availableProfiles[0];
        if (!selectedProfile) return;

        profileBadge.textContent = selectedProfile.type.toUpperCase().replace("_", " ");
        profileDesc.textContent = selectedProfile.description;
        renderPartitionTable();
    }

    profileSelect.addEventListener("change", (e) => {
        selectProfile(e.target.value);
    });

    function renderPartitionTable() {
        if (!selectedProfile || !selectedProfile.images) return;
        partitionTableBody.innerHTML = "";
        selectedProfile.images.forEach(img => {
            const tr = document.createElement("tr");
            tr.innerHTML = `
                <td><span class="badge-tag cyan">#${img.order}</span></td>
                <td style="font-weight: 700; color: #ffffff;">${img.partition}</td>
                <td><span class="badge-tag ${img.target_slot === 'both' ? 'pink' : 'cyan'}">${img.target_slot.toUpperCase()}</span></td>
                <td style="color: var(--text-secondary);">${img.name}</td>
                <td style="font-family: var(--font-mono); color: var(--cyan-hione);">${img.size_human}</td>
            `;
            partitionTableBody.appendChild(tr);
        });
    }

    function updateTelemetryUI(vars) {
        tvProduct.textContent = (vars["product"] || "m500").toUpperCase();
        tvSerial.textContent = vars["serialno"] || "Unknown";
        tvSlot.textContent = (vars["current-slot"] || "A").toUpperCase();
        tvUnlocked.textContent = (vars["unlocked"] === "yes" || vars["unlocked"] === "true") ? "UNLOCKED (Ready)" : "LOCKED";
        tvUnlocked.style.color = (vars["unlocked"] === "yes") ? "var(--green-success)" : "var(--red-danger)";
        tvMode.textContent = vars["is-userspace"] === "yes" ? "Fastbootd (Userspace)" : "Bootloader";
        
        const maxDl = vars["max-download-size"];
        if (maxDl) {
            const sz = parseInt(maxDl, 16);
            tvBuffer.textContent = `${(sz / 1024 / 1024).toFixed(0)} MB`;
        } else {
            tvBuffer.textContent = "512 MB";
        }

        connectionBadge.className = "badge-tag green";
        connectionBadge.textContent = "Fastboot Connected";
        deviceModelText.textContent = `HiBy ${tvProduct.textContent} (Snapdragon SoC)`;
        deviceDetailsText.textContent = `Serial: ${tvSerial.textContent} · Slot: ${tvSlot.textContent}`;
    }

    // Pair WebUSB
    btnWebUSB.addEventListener("click", async () => {
        try {
            log("INFO", "Connecting WebUSB Fastboot...");
            const vars = await fastboot.connect();
            updateTelemetryUI(vars);
            log("SUCCESS", "✅ WebUSB Fastboot pairing established!");
        } catch (err) {
            log("ERROR", "WebUSB connection failed: " + err.message);
        }
    });

    // Scan Host Fastboot/ADB
    btnRefreshHost.addEventListener("click", async () => {
        try {
            log("INFO", "Scanning for host fastboot and adb devices...");
            const resp = await fetch("/api/status");
            const status = await resp.json();

            if (status.fastboot_devices && status.fastboot_devices.length > 0) {
                log("SUCCESS", `Host Fastboot detected: ${status.fastboot_devices.join(", ")}`);
                if (status.fastboot_vars) updateTelemetryUI(status.fastboot_vars);
            } else if (status.adb_devices && status.adb_devices.length > 0) {
                log("SUCCESS", `Host ADB detected: ${status.adb_devices.join(", ")}`);
                connectionBadge.className = "badge-tag cyan";
                connectionBadge.textContent = "ADB Connected";
                deviceModelText.textContent = "HiBy M500 (Android OS Booted)";
                deviceDetailsText.textContent = status.adb_devices.join(", ");
            } else {
                log("WARN", "No device detected on host. Hold Power + Volume Down for Fastboot.");
            }
        } catch (err) {
            log("ERROR", "Scan failed: " + err.message);
        }
    });

    // WebUSB Direct Browser Flashing Pipeline
    btnFlashWebUSB.addEventListener("click", async () => {
        if (!fastboot.isConnected) {
            alert("Please connect your device via '⚡ Pair WebUSB' first!");
            return;
        }
        if (!selectedProfile || !selectedProfile.images) {
            alert("Please select a firmware profile!");
            return;
        }

        const confirmMsg = `Are you sure you want to flash [${selectedProfile.name}]?\n${selectedProfile.wipe_data ? 'WARNING: All user data will be wiped.' : 'User data will be preserved.'}`;
        if (!confirm(confirmMsg)) return;

        try {
            opStatus.textContent = "Flashing...";
            opStatus.className = "badge-tag pink";
            log("STEP", `🚀 Starting WebUSB flash pipeline for [${selectedProfile.name}]...`);

            for (const img of selectedProfile.images) {
                const url = `/api/images/${img.name}`;
                if (img.target_slot === "both") {
                    log("FLASH", `⚡ Flashing ${img.partition}_a with ${img.name}...`);
                    await fastboot.flashImageFromUrl(`${img.partition}_a`, url, img.size);
                    log("FLASH", `⚡ Flashing ${img.partition}_b with ${img.name}...`);
                    await fastboot.flashImageFromUrl(`${img.partition}_b`, url, img.size);
                } else if (img.target_slot === "a") {
                    log("FLASH", `⚡ Flashing ${img.partition}_a with ${img.name}...`);
                    await fastboot.flashImageFromUrl(`${img.partition}_a`, url, img.size);
                } else {
                    log("FLASH", `⚡ Flashing ${img.partition} with ${img.name}...`);
                    await fastboot.flashImageFromUrl(img.partition, url, img.size);
                }
            }

            log("STEP", "🔧 Setting Slot A active...");
            await fastboot.setActiveSlot("a");

            if (selectedProfile.wipe_data) {
                log("STEP", "🧹 Formatting userdata and metadata...");
                try { await fastboot.erase("userdata"); } catch (_) {}
                try { await fastboot.erase("metadata"); } catch (_) {}
            }

            log("SUCCESS", "🎉 Flashing pipeline complete! Rebooting device into HioneOS...");
            await fastboot.reboot();

            opStatus.textContent = "Flash Complete";
            opStatus.className = "badge-tag green";
            progressStatusText.textContent = "Complete!";
        } catch (err) {
            log("ERROR", "Flash pipeline error: " + err.message);
            opStatus.textContent = "Failed";
            opStatus.className = "badge-tag warn";
        }
    });

    // Host Backend Flash Pipeline
    btnFlashHost.addEventListener("click", async () => {
        if (!selectedProfile) return;
        if (!confirm(`Flash [${selectedProfile.name}] via host backend?`)) return;

        try {
            log("INFO", `Triggering host flashing pipeline for ${selectedProfile.id}...`);
            const resp = await fetch(`/api/flash/host?profile=${selectedProfile.id}`, { method: "POST" });
            const res = await resp.json();
            log("INFO", res.message);
        } catch (err) {
            log("ERROR", "Host flash failed: " + err.message);
        }
    });

    // Live OTA APK Push
    btnOtaApkPush.addEventListener("click", async () => {
        try {
            log("OTA", "Triggering live Core APKs installation via ADB...");
            const resp = await fetch("/api/ota/push", { method: "POST" });
            const res = await resp.json();
            log("INFO", res.message);
        } catch (err) {
            log("ERROR", "OTA Push failed: " + err.message);
        }
    });

    // Unbrick Toolkit Handlers
    async function handleUnbrickAction(actionName) {
        log("INFO", `Executing recovery action: ${actionName}...`);
        try {
            const resp = await fetch(`/api/unbrick/action?action=${actionName}`, { method: "POST" });
            const res = await resp.json();
            if (res.success) {
                log("SUCCESS", `✔ Action [${actionName}] succeeded: ${res.output || 'OKAY'}`);
            } else {
                log("ERROR", `❌ Action [${actionName}] failed: ${res.output}`);
            }
        } catch (err) {
            log("ERROR", `Request error for ${actionName}: ` + err.message);
        }
    }

    btnSwitchSlotA.addEventListener("click", () => handleUnbrickAction("switch-slot-a"));
    btnSwitchSlotB.addEventListener("click", () => handleUnbrickAction("switch-slot-b"));
    btnFastbootd.addEventListener("click", () => handleUnbrickAction("enter-fastbootd"));
    btnAvbFix.addEventListener("click", () => handleUnbrickAction("flash-avb-fix"));
    btnEraseData.addEventListener("click", () => handleUnbrickAction("erase-userdata"));
    btnRebootSystem.addEventListener("click", async () => {
        await fetch("/api/device/reboot");
        log("INFO", "Reboot dispatched.");
    });

    btnClearLogs.addEventListener("click", () => {
        terminalLogs.innerHTML = "";
    });

    btnCopyLogs.addEventListener("click", () => {
        navigator.clipboard.writeText(terminalLogs.innerText).then(() => {
            alert("Logs copied to clipboard!");
        });
    });

    // Tab Navigation
    document.querySelectorAll(".nav-tab").forEach(tab => {
        tab.addEventListener("click", () => {
            document.querySelectorAll(".nav-tab").forEach(t => t.classList.remove("active"));
            document.querySelectorAll(".tab-pane").forEach(p => p.classList.remove("active"));
            tab.classList.add("active");
            const target = tab.getAttribute("data-tab");
            const targetPane = document.getElementById(target);
            if (targetPane) targetPane.classList.add("active");
        });
    });

    // One-Click Root & Clean Unroot Handlers
    const btnOneClickRoot = document.getElementById("btnOneClickRoot");
    const btnUnrootClean = document.getElementById("btnUnrootClean");

    if (btnOneClickRoot) {
        btnOneClickRoot.addEventListener("click", async () => {
            log("STEP", "Initiating 1-Click Magisk Root Installation via Fastboot/ADB...");
            try {
                const resp = await fetch("/api/root/one-click", { method: "POST" });
                const res = await resp.json();
                if (res.success) {
                    log("SUCCESS", "✔ 1-Click Magisk Root installed successfully!");
                } else {
                    log("ERROR", "❌ Root installation failed: " + res.output);
                }
            } catch (err) {
                log("ERROR", "Root request failed: " + err.message);
            }
        });
    }

    if (btnUnrootClean) {
        btnUnrootClean.addEventListener("click", async () => {
            log("STEP", "Restoring pristine stock init_boot (Clean Unroot)...");
            try {
                const resp = await fetch("/api/unroot/action", { method: "POST" });
                const res = await resp.json();
                if (res.success) {
                    log("SUCCESS", "✔ Device cleanly restored to stock non-root kernel!");
                } else {
                    log("ERROR", "❌ Clean unroot failed: " + res.output);
                }
            } catch (err) {
                log("ERROR", "Unroot request failed: " + err.message);
            }
        });
    }

    // Initial boot scan
    loadManifest();
    btnRefreshHost.click();
});
