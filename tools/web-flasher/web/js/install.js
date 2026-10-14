/*
 * HioneOS public web installer — GrapheneOS-style, fully client-side (no backend).
 *
 * Drives two flows over WebUSB fastboot (fastboot-webusb.js):
 *   • "Root My M500"   — flashes the Magisk-patched init_boot to both slots, no data wipe.
 *   • "Install HioneOS" — flashes the full image set, sets slot A, wipes userdata/metadata.
 *
 * It is deliberately static-hostable (GitHub/Cloudflare Pages, plain nginx): it reads a
 * static ./manifest.json describing the release and streams each image from ./images/*.
 * There is NO /api backend here (that's app.js's bench console). Embedded DEFAULT_MANIFEST
 * is used only if manifest.json is absent, so the page still explains itself.
 *
 * super.img note: the A/B "super" (dynamic partitions) image is normally larger than the
 * device's fastboot max-download-size. fastboot handles that by flashing a *sparse* image
 * whose chunks each carry their destination block offset. A raw image larger than the
 * download buffer CANNOT be range-split and re-flashed to offset 0 safely. So the manifest
 * lists super as one or more PRE-SPLIT SPARSE chunk files (build step: lpmake -> raw super,
 * then `img2simg super.img super.sparse.img 4096` and `fastboot`-style split, or simply
 * `lpmake ... && img2simg` producing chunks under max-download-size). Each chunk is a valid
 * standalone sparse image; flashing them in sequence to "super" is exactly what CLI
 * `fastboot flash super` does. flashOne() below refuses any RAW image over the buffer.
 */
(() => {
  "use strict";

  // ---- Embedded fallback manifest (used only if ./manifest.json can't be fetched). ----
  // Real releases ship a manifest.json next to this file; sizes there are authoritative.
  const DEFAULT_MANIFEST = {
    version: "unknown",
    device: "HiBy M500 (HIONE)",
    images_base: "images/",
    // Each image: { name, partition, target_slot: "both"|"a"|"none", size (bytes),
    //   sparse?: true, variant?: "stock"|"root" }.
    // variant filters init_boot: "stock" flashed for a plain install, "root" when the user
    // opts into root; entries without a variant flash in every profile that lists them.
    profiles: {
      root: {
        label: "Root (Magisk init_boot, keep data)",
        wipe_data: false,
        images: [
          { name: "magisk_patched_init_boot.img", partition: "init_boot", target_slot: "both", size: 8 * 1024 * 1024 }
        ]
      },
      install: {
        label: "Full HioneOS install (wipes data)",
        wipe_data: true,
        images: [
          { name: "boot.img",            partition: "boot",          target_slot: "both", size: 0 },
          { name: "init_boot.img",       partition: "init_boot",     target_slot: "both", size: 0, variant: "stock" },
          { name: "magisk_patched_init_boot.img", partition: "init_boot", target_slot: "both", size: 0, variant: "root" },
          { name: "dtbo.img",            partition: "dtbo",          target_slot: "both", size: 0 },
          { name: "vendor_boot.img",     partition: "vendor_boot",   target_slot: "both", size: 0 },
          { name: "vbmeta.img",          partition: "vbmeta",        target_slot: "both", size: 0 },
          { name: "vbmeta_system.img",   partition: "vbmeta_system", target_slot: "both", size: 0 },
          // super is pre-split sparse; a real manifest lists super.1.img … super.N.img here.
          { name: "super.img",           partition: "super",         target_slot: "none", size: 0, sparse: true }
        ]
      }
    }
  };

  // ---- DOM ----
  const $ = (id) => document.getElementById(id);
  const el = {
    connect: $("btnConnect"), root: $("btnRoot"), install: $("btnInstall"),
    rooted: $("chkRooted"), devPill: $("devPill"), devText: $("devText"),
    bar: $("bar"), status: $("status"), log: $("log"), nowebusb: $("nowebusb")
  };

  const fastboot = new FastbootWebUSB();
  let manifest = DEFAULT_MANIFEST;
  let busy = false;

  // ---- logging / progress ----
  function logLine(level, msg) {
    const line = document.createElement("div");
    line.className = level;
    line.textContent = `[${level}] ${msg}`;
    el.log.appendChild(line);
    el.log.scrollTop = el.log.scrollHeight;
  }
  fastboot.onLog = (level, msg) => logLine(level, msg);
  fastboot.onProgress = (percent) => { if (typeof percent === "number") setBar(percent); };
  function setBar(pct) { el.bar.style.width = `${Math.max(0, Math.min(100, pct))}%`; }
  function setStatus(s) { el.status.textContent = s; }

  function setConnected(on, label) {
    el.devPill.classList.toggle("on", on);
    el.devText.textContent = on ? (label || "Connected") : "Not connected";
    el.root.disabled = !on || busy;
    el.install.disabled = !on || busy;
  }
  function setBusy(on) {
    busy = on;
    el.connect.disabled = on;
    el.root.disabled = on || !fastboot.isConnected;
    el.install.disabled = on || !fastboot.isConnected;
  }

  // ---- one image, honoring per-slot + sparse/size guardrails ----
  async function flashOne(img) {
    const base = manifest.images_base || "images/";
    const url = base + img.name;
    const maxDl = fastboot.maxDownloadSize || 0;
    const size = img.size || 0;

    // Guardrail: a RAW image bigger than the download buffer cannot be safely range-split.
    if (!img.sparse && maxDl && size > maxDl) {
      throw new Error(
        `${img.name} is raw and ${(size / 1048576).toFixed(0)} MB > max-download ` +
        `${(maxDl / 1048576).toFixed(0)} MB. It must be provided as pre-split sparse chunks ` +
        `(sparse:true, e.g. super.1.img…super.N.img). Refusing to flash to avoid corruption.`
      );
    }

    if (img.target_slot === "both") {
      await fastboot.flashImageFromUrl(`${img.partition}_a`, url, size);
      await fastboot.flashImageFromUrl(`${img.partition}_b`, url, size);
    } else if (img.target_slot === "a") {
      await fastboot.flashImageFromUrl(`${img.partition}_a`, url, size);
    } else {
      // "none" => base partition name (e.g. super, which is logical/not slotted)
      await fastboot.flashImageFromUrl(img.partition, url, size);
    }
  }

  // Pick the images for a profile, resolving the stock/root init_boot variant.
  function imagesFor(profile, wantRoot) {
    return profile.images.filter((img) => {
      if (!img.variant) return true;
      return wantRoot ? img.variant === "root" : img.variant === "stock";
    });
  }

  async function runProfile(profileKey, { wantRoot }) {
    const profile = manifest.profiles[profileKey];
    if (!profile) { logLine("ERROR", `No "${profileKey}" profile in manifest.`); return; }
    if (!fastboot.isConnected) { logLine("ERROR", "Connect the device first."); return; }

    const wipe = !!profile.wipe_data;
    const human = profileKey === "root" ? "root" : "install HioneOS";
    if (wipe && !confirm(
      `Install HioneOS ${manifest.version}? This ERASES ALL USER DATA on the M500.\n` +
      `Do not unplug until it reboots.`)) return;

    setBusy(true); setBar(0);
    try {
      const imgs = imagesFor(profile, wantRoot);
      setStatus(`Flashing (${imgs.length} images)…`);
      for (let i = 0; i < imgs.length; i++) {
        setStatus(`Flashing ${imgs[i].partition} (${i + 1}/${imgs.length})…`);
        await flashOne(imgs[i]);
        setBar(Math.round(((i + 1) / imgs.length) * 90));
      }

      logLine("STEP", "Setting slot A active…");
      await fastboot.setActiveSlot("a");

      if (wipe) {
        logLine("STEP", "Formatting userdata + metadata…");
        try { await fastboot.erase("userdata"); } catch (_) {}
        try { await fastboot.erase("metadata"); } catch (_) {}
      }
      setBar(100);
      logLine("SUCCESS", `${profileKey === "root" ? "Root" : "HioneOS install"} complete — rebooting…`);
      await fastboot.reboot();
      setStatus(profileKey === "root"
        ? "Rooted. After boot, install the Magisk app to finish."
        : "HioneOS installed. First boot takes a few minutes.");
    } catch (err) {
      logLine("ERROR", `${human} failed: ${err.message || err}`);
      setStatus(`Failed: ${err.message || err}`);
    } finally {
      setBusy(false);
    }
  }

  // ---- wiring ----
  async function onConnect() {
    setBusy(true);
    try {
      await fastboot.connect();
      try { await fastboot.queryAllVariables(); } catch (_) {}
      const label = (fastboot.product ? fastboot.product.toUpperCase() : "M500")
        + (fastboot.serialNo ? ` · ${fastboot.serialNo}` : "");
      setConnected(true, label);
      setStatus(`Connected. Ready. (max-download ${((fastboot.maxDownloadSize || 0) / 1048576).toFixed(0)} MB)`);
    } catch (err) {
      logLine("ERROR", `Connect failed: ${err.message || err}`);
      setStatus("Connect failed. Put the M500 in fastboot mode and try again.");
      setConnected(false);
    } finally {
      setBusy(false);
    }
  }

  async function loadManifest() {
    try {
      const resp = await fetch("manifest.json", { cache: "no-store" });
      if (resp.ok) {
        manifest = await resp.json();
        logLine("INFO", `Loaded manifest: HioneOS ${manifest.version} for ${manifest.device || "M500"}.`);
      } else {
        logLine("WARN", "No manifest.json found — using built-in defaults (sizes unknown).");
      }
    } catch (_) {
      logLine("WARN", "manifest.json unreadable — using built-in defaults.");
    }
  }

  function init() {
    if (!FastbootWebUSB.isSupported() || !navigator.usb) {
      if (el.nowebusb) el.nowebusb.style.display = "block";
      logLine("ERROR", "WebUSB unavailable. Use desktop Chrome/Edge over https.");
      return;
    }
    el.connect.addEventListener("click", onConnect);
    el.root.addEventListener("click", () => runProfile("root", { wantRoot: true }));
    el.install.addEventListener("click", () => runProfile("install", { wantRoot: el.rooted.checked }));
    setConnected(false);
    loadManifest();
    logLine("INFO", "HioneOS installer ready. Connect your M500 in fastboot mode to begin.");
  }

  document.addEventListener("DOMContentLoaded", init);
})();
