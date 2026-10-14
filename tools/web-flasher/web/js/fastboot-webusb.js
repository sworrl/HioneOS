/**
 * ✨ HioneOS Fastboot WebUSB Engine ✨
 * Pure JavaScript implementation of the Android Fastboot Protocol over WebUSB.
 * Full support for Hardware Telemetry (getvar all), A/B Slot Recovery & Chunked Flashing.
 */
class FastbootWebUSB {
    constructor() {
        this.device = null;
        this.interfaceNumber = null;
        this.endpointIn = null;
        this.endpointOut = null;
        this.maxDownloadSize = 512 * 1024 * 1024;
        this.product = "m500";
        this.serialNo = "Unknown";
        this.currentSlot = "a";
        this.isUnlocked = true;
        this.isUserspace = false;
        this.batteryVoltage = "N/A";
        this.variables = {};
        this.isConnected = false;
        this.onLog = (level, msg) => console.log(`[${level}] ${msg}`);
        this.onProgress = (percent, speedMb, transferred, total, status) => {};
    }

    static isSupported() {
        return !!(navigator.usb && navigator.usb.requestDevice);
    }

    async connect() {
        if (!FastbootWebUSB.isSupported()) {
            throw new Error("WebUSB API is not supported in this browser. Please use Google Chrome, Brave, or Microsoft Edge.");
        }

        this.onLog("INFO", "🔍 Requesting USB Fastboot device pairing...");

        const filters = [
            { classCode: 0xFF, subclassCode: 0x42, protocolCode: 0x03 },
            { vendorId: 0x18D1 }, // Google / AOSP
            { vendorId: 0x05C6 }, // Qualcomm Technologies
            { vendorId: 0x2A70 }, // OnePlus / Oppo
            { vendorId: 0x0BB4 }, // HTC / Generic
            { vendorId: 0x2717 }, // Xiaomi
        ];

        try {
            this.device = await navigator.usb.requestDevice({ filters });
        } catch (err) {
            throw new Error("USB Pairing cancelled: " + err.message);
        }

        this.onLog("INFO", `🔌 Opened USB Device: ${this.device.productName || 'Android Fastboot Target'} (${this.device.vendorId.toString(16)}:${this.device.productId.toString(16)})`);

        await this.device.open();
        if (this.device.configuration === null) {
            await this.device.selectConfiguration(1);
        }

        let iface = null;
        for (const c of this.device.configurations) {
            for (const i of c.interfaces) {
                for (const alt of i.alternates) {
                    if (alt.interfaceClass === 0xFF && alt.interfaceSubclass === 0x42 && alt.interfaceProtocol === 0x03) {
                        iface = { number: i.interfaceNumber, alt: alt };
                        break;
                    }
                    if (alt.endpoints.some(e => e.type === "bulk" && e.direction === "in") &&
                        alt.endpoints.some(e => e.type === "bulk" && e.direction === "out")) {
                        iface = { number: i.interfaceNumber, alt: alt };
                    }
                }
                if (iface) break;
            }
            if (iface) break;
        }

        if (!iface) {
            throw new Error("No Fastboot bulk interface found. Ensure device is in bootloader / fastboot mode.");
        }

        this.interfaceNumber = iface.number;
        for (const ep of iface.alt.endpoints) {
            if (ep.direction === "in") this.endpointIn = ep.endpointNumber;
            if (ep.direction === "out") this.endpointOut = ep.endpointNumber;
        }

        await this.device.claimInterface(this.interfaceNumber);
        this.isConnected = true;
        this.onLog("SUCCESS", `✔ Fastboot USB Interface Claimed (EP In: ${this.endpointIn}, EP Out: ${this.endpointOut})`);

        await this.queryAllVariables();
        return this.variables;
    }

    async queryAllVariables() {
        this.onLog("INFO", "📊 Polling complete hardware telemetry via 'getvar all'...");
        this.variables = {};

        try {
            await this.rawCommandWithInfoCollector("getvar:all", (infoLine) => {
                let clean = infoLine.replace(/^\(bootloader\)\s*/i, "").trim();
                const parts = clean.split(/:\s*/);
                if (parts.length >= 2) {
                    const key = parts[0].trim();
                    const val = parts.slice(1).join(":").trim();
                    this.variables[key] = val;
                }
            });
        } catch (_err) {
            // Fallback to individual variables if getvar all fails
            for (const v of ["product", "serialno", "unlocked", "current-slot", "max-download-size", "is-userspace", "battery-voltage", "version-bootloader"]) {
                const val = await this.getVar(v);
                if (val) this.variables[v] = val;
            }
        }

        this.product = this.variables["product"] || "m500";
        this.serialNo = this.variables["serialno"] || "Unknown";
        this.currentSlot = this.variables["current-slot"] || "a";
        this.isUnlocked = this.variables["unlocked"] === "yes" || this.variables["unlocked"] === "true";
        this.isUserspace = this.variables["is-userspace"] === "yes";
        this.batteryVoltage = this.variables["battery-voltage"] || "OK";

        const maxDlStr = this.variables["max-download-size"];
        if (maxDlStr) {
            const parsed = parseInt(maxDlStr, 16);
            if (!isNaN(parsed) && parsed > 0) this.maxDownloadSize = parsed;
        }

        this.onLog("SUCCESS", `📱 Telemetry Synced: ${this.product.toUpperCase()} | Serial: ${this.serialNo} | Active Slot: ${this.currentSlot.toUpperCase()} | Fastbootd: ${this.isUserspace} | Max Buffer: ${(this.maxDownloadSize / 1024 / 1024).toFixed(0)} MB`);
    }

    async rawCommand(cmd) {
        if (!this.isConnected || !this.device) throw new Error("Device not connected");
        const encoder = new TextEncoder();
        await this.device.transferOut(this.endpointOut, encoder.encode(cmd));
        return await this.readResponse();
    }

    async rawCommandWithInfoCollector(cmd, onInfo) {
        if (!this.isConnected || !this.device) throw new Error("Device not connected");
        const encoder = new TextEncoder();
        await this.device.transferOut(this.endpointOut, encoder.encode(cmd));

        const decoder = new TextDecoder();
        while (true) {
            const res = await this.device.transferIn(this.endpointIn, 512);
            const status = decoder.decode(res.data.buffer.slice(0, 4));
            const payload = decoder.decode(res.data.buffer.slice(4));

            if (status === "INFO") {
                if (onInfo) onInfo(payload);
                continue;
            } else if (status === "OKAY") {
                return { status: "OKAY", payload };
            } else if (status === "FAIL") {
                throw new Error(`Fastboot command [${cmd}] failed: ${payload}`);
            } else {
                return { status, payload };
            }
        }
    }

    async readResponse() {
        const decoder = new TextDecoder();
        while (true) {
            const res = await this.device.transferIn(this.endpointIn, 512);
            const status = decoder.decode(res.data.buffer.slice(0, 4));
            const payload = decoder.decode(res.data.buffer.slice(4));

            if (status === "INFO") {
                this.onLog("INFO", `[FB] ${payload}`);
                continue;
            } else if (status === "OKAY") {
                return { status: "OKAY", payload };
            } else if (status === "DATA") {
                const bytes = parseInt(payload, 16);
                return { status: "DATA", bytes };
            } else if (status === "FAIL") {
                throw new Error(`Fastboot error: ${payload}`);
            } else {
                return { status, payload };
            }
        }
    }

    async getVar(variable) {
        try {
            const res = await this.rawCommand(`getvar:${variable}`);
            return res.payload ? res.payload.trim() : null;
        } catch (_err) {
            return null;
        }
    }

    async setActiveSlot(slot) {
        this.onLog("FLASH", `▶ fastboot set_active ${slot}`);
        const res = await this.rawCommand(`set_active:${slot}`);
        this.currentSlot = slot;
        return res;
    }

    async erase(partition) {
        this.onLog("FLASH", `▶ fastboot erase ${partition}`);
        return await this.rawCommand(`erase:${partition}`);
    }

    async reboot(target = "") {
        const cmd = target ? `reboot-${target}` : "reboot";
        this.onLog("FLASH", `▶ fastboot ${cmd}`);
        try {
            await this.rawCommand(cmd);
        } catch (_) {}
        await this.disconnect();
    }

    async rebootFastbootd() {
        this.onLog("FLASH", "▶ fastboot reboot fastboot (Entering Userspace Fastbootd)");
        try {
            await this.rawCommand("reboot-fastboot");
        } catch (_) {}
        await this.disconnect();
    }

    async rebootRecovery() {
        this.onLog("FLASH", "▶ fastboot reboot recovery");
        try {
            await this.rawCommand("reboot-recovery");
        } catch (_) {}
        await this.disconnect();
    }

    async downloadBuffer(buffer, label = "payload") {
        const sizeHex = buffer.byteLength.toString(16).padStart(8, "0");
        this.onLog("FLASH", `▶ fastboot download:${sizeHex} (${(buffer.byteLength / 1024 / 1024).toFixed(2)} MB)`);

        const res = await this.rawCommand(`download:${sizeHex}`);
        if (res.status !== "DATA") {
            throw new Error(`Device rejected download: ${res.payload || 'Unknown response'}`);
        }

        const chunkSize = 1024 * 1024;
        let offset = 0;
        const total = buffer.byteLength;
        const startTime = performance.now();

        while (offset < total) {
            const end = Math.min(offset + chunkSize, total);
            const chunk = buffer.slice(offset, end);
            await this.device.transferOut(this.endpointOut, chunk);

            offset = end;
            const elapsedSec = (performance.now() - startTime) / 1000;
            const speedMbps = elapsedSec > 0 ? (offset / 1024 / 1024) / elapsedSec : 0;
            const pct = Math.round((offset / total) * 100);

            this.onProgress(pct, speedMbps.toFixed(2), offset, total, `Sending ${label}...`);
        }

        const finalize = await this.readResponse();
        if (finalize.status !== "OKAY") {
            throw new Error(`Download verification failed: ${finalize.payload}`);
        }
    }

    async flashPartition(partition, arrayBuffer) {
        this.onLog("FLASH", `⚡ Flashing [${partition}] (${(arrayBuffer.byteLength / 1024 / 1024).toFixed(2)} MB)...`);
        await this.downloadBuffer(arrayBuffer, partition);

        this.onLog("FLASH", `▶ fastboot flash ${partition}`);
        const res = await this.rawCommand(`flash:${partition}`);
        if (res.status !== "OKAY") {
            throw new Error(`Flash ${partition} failed: ${res.payload}`);
        }
        this.onLog("OKAY", `✔ OKAY [flash ${partition}]`);
    }

    async flashImageFromUrl(partition, url, totalBytes) {
        this.onLog("INFO", `🌐 Streaming [${partition}] from ${url} (${(totalBytes / 1024 / 1024).toFixed(2)} MB)...`);
        const maxChunk = Math.min(this.maxDownloadSize, 512 * 1024 * 1024);

        if (totalBytes <= maxChunk) {
            const resp = await fetch(url);
            if (!resp.ok) throw new Error(`HTTP ${resp.status} fetching ${url}`);
            const buf = await resp.arrayBuffer();
            await this.flashPartition(partition, buf);
        } else {
            let offset = 0;
            let chunkIdx = 1;
            const totalChunks = Math.ceil(totalBytes / maxChunk);

            while (offset < totalBytes) {
                const end = Math.min(offset + maxChunk - 1, totalBytes - 1);
                this.onLog("INFO", `📥 Range Stream [${chunkIdx}/${totalChunks}]: bytes ${offset}-${end}...`);

                const resp = await fetch(url, { headers: { "Range": `bytes=${offset}-${end}` } });
                if (!resp.ok && resp.status !== 206) throw new Error(`HTTP ${resp.status} range error`);
                const buf = await resp.arrayBuffer();

                this.onLog("FLASH", `⚡ Flashing chunk ${chunkIdx}/${totalChunks} to ${partition}...`);
                await this.flashPartition(partition, buf);

                offset = end + 1;
                chunkIdx++;
            }
        }
    }

    async disconnect() {
        if (this.device && this.isConnected) {
            try {
                if (this.interfaceNumber !== null) {
                    await this.device.releaseInterface(this.interfaceNumber);
                }
                await this.device.close();
            } catch (_) {}
        }
        this.isConnected = false;
        this.device = null;
        this.onLog("INFO", "🔌 USB Device Disconnected.");
    }
}

window.FastbootWebUSB = FastbootWebUSB;
