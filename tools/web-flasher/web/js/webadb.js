/**
 * ✨ HioneOS WebADB & WebSerial OTA Updater ✨
 * Connects to Android OS over WebUSB ADB / WebSerial to update Core System APKs,
 * run diagnostics, check battery & audio DAC status, and reboot to fastboot.
 */
class HioneWebADB {
    constructor() {
        this.port = null;
        this.reader = null;
        this.writer = null;
        this.isConnected = false;
        this.onLog = (level, msg) => console.log(`[${level}] ${msg}`);
    }

    static isSerialSupported() {
        return !!(navigator.serial && navigator.serial.requestPort);
    }

    async connectSerial() {
        if (!HioneWebADB.isSerialSupported()) {
            throw new Error("WebSerial API is not supported in this browser. Please use Chrome/Edge on desktop.");
        }

        this.onLog("INFO", "🔍 Requesting Serial USB Port pairing...");
        this.port = await navigator.serial.requestPort();
        await this.port.open({ baudRate: 115200 });

        this.writer = this.port.writable.getWriter();
        this.reader = this.port.readable.getReader();
        this.isConnected = true;

        this.onLog("SUCCESS", "✔ WebSerial connection established!");
        this.startReading();
    }

    async startReading() {
        const decoder = new TextDecoder();
        try {
            while (this.isConnected && this.reader) {
                const { value, done } = await this.reader.read();
                if (done) break;
                if (value) {
                    const text = decoder.decode(value);
                    this.onLog("SERIAL", text);
                }
            }
        } catch (err) {
            this.onLog("ERROR", "Serial read loop error: " + err.message);
        }
    }

    async sendCommand(cmd) {
        if (!this.writer) throw new Error("Serial port not writable");
        const encoder = new TextEncoder();
        await this.writer.write(encoder.encode(cmd + "\n"));
    }

    async rebootBootloader() {
        this.onLog("INFO", "📲 Triggering device reboot to Fastboot bootloader...");
        await this.sendCommand("reboot bootloader");
    }

    async disconnect() {
        this.isConnected = false;
        if (this.reader) {
            await this.reader.cancel();
            this.reader.releaseLock();
        }
        if (this.writer) {
            this.writer.releaseLock();
        }
        if (this.port) {
            await this.port.close();
        }
        this.onLog("INFO", "🔌 WebSerial disconnected.");
    }
}

window.HioneWebADB = HioneWebADB;
