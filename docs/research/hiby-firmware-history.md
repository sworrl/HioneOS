# HiBy M500 firmware history: what each release actually changed

This page lists every official HiBy Digital x Hatsune Miku M500 firmware release. For each release
it quotes HiBy's changelog, then lists what the firmware images show actually changed, then says
what that means for MikuOS. Most of the evidence comes from a partition-level, module-level and
class-level diff of the two builds we have images for: WiFi 1.20 and WiFi 1.30. For 1.40 there is
partial evidence from APKs, jars and properties pulled off the user's own 4G unit. Nothing was run
on a device.

## 1. What was obtained, and a correction

### Version map

| Release | Variant | `ro.fota.version` | Build dates (system / vendor / kernel) | What we have |
|---|---|---|---|---|
| 1.00 | both | unknown | unknown | nothing; no changelog, no image |
| 1.10 | both | unknown (wiki title `HiByDigitaly_M500_MIKU_1.10_20251211`) | 2025-12-11 | changelog only; full eMMC image is on Baidu Pan, not downloaded |
| 1.20 | WiFi | `1.20_20260228-1619` | 2026-02-28 17:32 / 2026-02-28 16:19 / 2026-02-04 | full OTA `mSHAY1Ex.zip`, all partitions |
| 1.20 | 4G | unknown | unknown | changelog only |
| 1.30 | WiFi | `1.30_20260509-1120` | 2026-04-27 12:39 / 2026-05-09 11:20 / 2026-04-23 | incremental OTA `mOGC5azx.zip` (1.20 to 1.30), applied and verified |
| 1.30 | 4G | unknown | unknown | changelog only |
| 1.40 | 4G | `1.40G_20260703-1757` | 2026-06-15 19:28 / 2026-07-03 17:57 / 2026-06-16 | getprop, framework.jar, services.jar, Settings, FM2 and vendor apps pulled from the user's unit on 2026-08-17/18; no partition images |
| 1.40 | WiFi | unknown | unknown | changelog only |
| 1.41 | both | unknown | unknown | changelog only; only distributed through the updater API, which this network's DNS filter blocks (rechecked 2026-10-10) |

**Correction to `m500-system-archive/firmware/FIRMWARE_ARCHIVE_REPORT.md`.** That report labels
`mSHAY1Ex.zip` "v1.00 Base" and `mOGC5azx.zip` "v1.20 Delta". Both labels are wrong.
`mSHAY1Ex.zip` is WiFi 1.20 (`ro.fota.version=1.20_20260228-1619`, `ro.build.version.subversion=1.20`,
device `M500_MIKU`). `mOGC5azx.zip` updates WiFi 1.20 to WiFi 1.30 (`1.30_20260509-1120`,
subversion `1.30`, and it adds the cassette app that 1.30's changelog announces). The "1.00 stock"
that MikuOS builds on (`extracted_1.00/`) is therefore **WiFi 1.20**. Two more points: that 1.20
build is dated 2026-02-28, but HiBy's 1.20 WiFi article was already online on 2026-02-05, so this
is a later respin of 1.20. And the M500's own DT overlay is named "HiBy M500 4G" even in the WiFi
build, so both variants appear to share one kernel and device tree.

### How the packages were checked

- Both OTA zips are AOSP A/B payloads (CrAU v2, minor 8). The incremental uses SOURCE_COPY,
  BROTLI_BSDIFF, PUFFDIFF, REPLACE_BZ/XZ and ZERO. There are no ZUCCHINI ops.
- I wrote an applier (`apply_payload.py`) that calls AOSP `bspatch` and `puffpatch`, built here
  from `platform/external/bsdiff` and `platform/external/puffin`. The puffin protobuf header is
  parsed by a hand-written shim instead of generated code, and zucchini is stubbed. After the ops,
  the applier rebuilds the dm-verity hash tree and the libfec RS(255,253) parity, as update_engine
  does.
- The 1.20 source images matched `old_partition_info` for all 14 partitions. All 14 results
  matched `new_partition_info`: boot, dtbo, init_boot, odm, product, recovery, system, system_dlkm,
  system_ext, vbmeta, vbmeta_system, vendor, vendor_boot, vendor_dlkm.
- Diffs were then taken at four levels:
  - file level, by md5 over debugfs-extracted trees;
  - kernel module level, by ELF section hashes that ignore the signature, build-id, vermagic,
    srcversion and scmversion;
  - function level, with `llvm-objdump -d -r` plus a normalizer;
  - Java class level, with jadx 1.5.
- Tools and outputs are in `/home/reaver/mikuos-scratch/hiby_fw_work/` (`fwtools/`, `fs/`,
  `boot/`, `jadx/`, `diff/`). The shared index is `/home/reaver/mikuos-scratch/hiby_fw/`.

### What could not be obtained, and what was tried

- **The OTA check API (any version, either variant).** I reimplemented the update client from the
  decompiled `com.hiby.update` (Abupdate 2.1.36), as `fwtools/otacheck.py`. The flow is:
  1. `POST /product/obtainProduct`, signed `MD5(MD5("su200smartphoneHiBySM6125"))`, with the
     reply AES-ECB-decrypted using key `sign[8:24]`.
  2. `POST /register/<productId>`, signed HMAC-MD5 over `mid+productId+timestamp`.
  3. `POST /product/<productId>/<deviceId>/ota/checkVersion`, sending `ro.fota.version` as
     `version`.

  The base URL is `https://iotapi.abupdate.com`; the app selects the China region. The device
  fields come from vendor `build.prop` and are the same in 1.20, 1.30 and the 4G 1.40G pull:
  `ro.fota.oem=HiBy`, `ro.fota.device=su200` (sent as `models`), `ro.fota.platform=SM6125`,
  `ro.fota.type=smartphone`. The product id and secret are not in `build.prop`. The client gets
  them from `obtainProduct`. The CDN paths of the two known packages use product id `1750730662`.
  `mid` is the device serial: the app's `assets/CustomConfig.properties` sets
  `ro.fota.midType=sn`, and `DeviceUtil.getSN()` returns `Build.getSerial()`. The client does not
  derive it from anything else. So a check needs some `mid`. Whether the server accepts a
  placeholder that is not a real serial is not known.

  On this network every API host answers from NextDNS: iotapi, piotapi and uiotapi.abupdate.com,
  and iotapi.adups.com. Rechecked 2026-10-10: the names now resolve, but to 149.248.211.216, and
  the reply is `HTTP 200`, `blocked-by: NextDNS`, empty body. That is the DNS filter's block page,
  not the update server. DNS-over-HTTPS is blocked too. I did not bypass the user's DNS filter, so
  the API has never been queried. The CDN host `iotdown-jd.mayitek.com` is not blocked: a HEAD on
  the known 1.30 delta URL returned 200. Package filenames are random, though, so the CDN cannot be
  enumerated without the API.

  To get more versions, allow `iotapi.abupdate.com` (temporarily) and run
  `otacheck.py <ro.fota.version> <mid>` once per known version string. Each reply names the next
  package for that source version. Known strings: `1.20_20260228-1619`, `1.30_20260509-1120`,
  `1.40G_20260703-1757`. Pass a `mid` explicitly. The script's built-in default is the user's own
  unit's serial, taken from the 1.40G getprop pull.
- **Baidu Pan (from the HiBy wiki).**
  - `HiBy_M500_MIKU_1.40_20260701-2325_14.0_user_update.zip` (1,431,255,992 bytes, code `cpdd`).
    The build stamp differs from the wiki's 4G title, so it may be the WiFi 1.40.
  - `HiBy_M500_MIKU_1.10_20251211-1355_14.0_user_emmc_wf_burn.zip` (1,795,184,653 bytes, code
    `zbh5`).

  The filenames were read through the share pages. Baidu needs a logged-in account to download, so
  neither file was fetched.
- **No source for 1.00 or 1.41** was found: no image and no download link. 1.00 has no published
  changelog either.

## 2. Release notes and findings

### 1.00 (launch firmware)

**Official changelog:** none published. The HiBy wiki firmware page starts at 1.10. In Wayback
snapshots, the Help Center has no M500 section before February 2026.

**What actually changed:** no image available.

**Relevance to MikuOS:** none.

### 1.10 (2025-12-11, single entry, no variant split)

**Official changelog** (HiBy wiki,
https://guide.hiby.com/en/docs/products/audio_player/hibydigital_m500miku/firmware, entry
"HiByDigitaly_M500_MIKU_1.10_20251211 Firmware"):

> Fixed the issue that could occur on certain devices where standby battery drain was high;
> Fixed the issue where there may be no audio output after pausing and going to standby;
> Fixed issue where USB audio output volume could not be adjusted;
> Fixed error in battery display in "About Device"
> Adjusted FM radio scan for better accuracy;
> Fixed the issue where FM radio could go silent after normal first use and exit;
> Fixed the issue where the MIKU prompt pop-up failed to appear when headphones were inserted during the first boot;
> Optimized camera white balance;
> Fixed several other bugs.

The Chinese page adds "适配新屏驱动，请知悉！" ("adapted for the new screen driver, please note").

**What actually changed:** cannot be determined. No 1.00 or 1.10 image was available. Every 1.10
fix should already be in the 1.20 baseline that MikuOS uses.

**Relevance to MikuOS:** MikuOS is built on 1.20, so it already includes whatever 1.10 fixed.

### 1.20 (WiFi: published 2026-01-09 to 2026-02-05; 4G: 2026-03-03 to 2026-03-24)

**Official changelog** (HiBy Help Center, https://store.hiby.com/apps/help-center,
`#hc-m500-wifi-version-v120-firmware-update`):

> 1) Added LDAC support for Bluetooth;
> 2) Added touch selection lock functionality to the Fn;
> 3) Optimized the speed of turning FM on and off;
> 4) Fixed several other bugs.

4G (`#hc-m500-4g-version-v120-firmware-update`):

> 1) Added LDAC support for Bluetooth output;
> 2) Added touch lock function to the Fn button;
> 3) Added support for the "pause" button control on FM radio;
> 4) Optimized the speed of turning FM radio on and off.
> 5) Fixed several other bugs.

**What actually changed:** there is no 1.10 image to diff against, so the "other bugs" in 1.20
cannot be identified. The 1.20 build (2026-02-28 respin) does contain the advertised features:
`libldacBT_enc/abr/dec` in `/system/lib*`, and `touch_lock` handling in SystemUI and services.jar.
More useful is the state of 1.20 as the baseline, because 1.30 fixes several of its defects
(next section). In particular:

- `cs43198_dlkm.ko` (md5 `6ef92ff320d4b64f101eee7262260a2d`) writes the filter with mask `0xC0`,
  so NOS never engages (`m500-nos-filter.md`). Its writes to the second, balanced-output DAC
  (index 3, I2C 0x33) are gated on output mode 3.
- `NotificationManagerService.playSound()` always returns `false`, so no notification sound ever
  plays.
- `radio-si4705-common.ko` has the RDS copy and stereo-pilot defects that MikuOS already patches.

**Relevance to MikuOS:** this is MikuOS's base (`extracted_1.00` = WiFi 1.20). Every 1.20 defect
listed under 1.30 below is present in MikuOS unless MikuOS replaces the component.

### 1.30 (published 2026-05-07 to 2026-07-03; WiFi build `1.30_20260509-1120`)

**Official changelog** (Help Center `#hc-m500-wifi-version-v130-firmware-update`; the 4G text is
identical):

> 1) Added a cassette playback UI;
> 2) Added FM Bluetooth output function;
> 3) Added a toggle switch for the desktop pet feature;
> 4) Added automatic recording termination for FM recording upon Bluetooth connection or disconnection.
> 5) Fixed several other bugs.

**What actually changed (WiFi 1.20 to WiFi 1.30, all partitions verified):**

Partitions changed: all 14. The kernel and modules were rebuilt from a clean tree:
`5.15.153-android13-8-gca6de2449164-dirty` (2026-02-04) became `...-g7199799f26f2` (2026-04-23).
The kernel's embedded config is byte-identical, and so is its set of printable strings, so the
GKI image itself is a rebuild only. The security patch level stays at 2025-09-03 (vendor
2024-12-05).

Advertised features, as implemented:

| Item | Evidence |
|---|---|
| Cassette UI | new `/vendor/app/HiByTape/HiByTape.apk` (com.hiby.tape 1.0). Launcher3 gains `NegativeOneScreenManagerTape`, `NegativeOneScreenViewTape`, `TapeImageProcessor` and `MediaSessionManagerHelper`, plus tape and "hibyrender" drawables. Launcher and com.hiby.tape get default grants of `MEDIA_CONTENT_CONTROL` and `MODIFY_AUDIO_SETTINGS` (`DefaultPermissionGrantPolicy.HIBY_NEGATIVE_ONE_SCREEN_PERMISSIONS`), and launcher3 privapp permissions add `MEDIA_CONTENT_CONTROL` |
| FM Bluetooth output | FM2 gains `AudioTrackHelper` (captures FM through `AudioRecord`, replays through `AudioTrack` with usage MEDIA, so audio policy can route it to A2DP). `libfmpal.so` gains a `PAL_DEVICE_OUT_A2DP` path. SELinux allows `vendor_fm_app` to find `mediametrics_service` |
| Desktop pet toggle | Settings `DisplaySettings` adds a `hiby_desktop_pet_enable` preference (removed from that screen when `Build.MODEL` is `M500_MIKU`); Launcher `floatmiku/FloatViewManager` changed |
| FM recording stops on BT change | FM2 `A2dpDeviceStatus.isDeviceAvailable()` is new. Recording moves to a new `WavHelper` (WAV output, `audio/wav`) |

The "several other bugs", by component:

1. **CS43198 DAC driver (`cs43198_dlkm.ko`, md5 6ef92ff3 to 12ded9dc).** This is the most
   important fix.
   - `cs43198_codec_digital_filter_set`: the mask on register `0x090000` (PCM Filter Option)
     changes from `0xC0` to `0xE0` at both write sites. This is exactly the MikuOS NOS fix: in
     1.30, HiBy's own NOS setting engages the chip's NOS bit for the first time.
   - The write to the second DAC (index 3, 0x33) is no longer gated on `mode == 3`. In 1.20, a
     filter changed while the 3.5 mm output was active never reached the balanced DAC. The same
     gate is removed in `cs43198_dai_hw_params` (registers 0x070004, 0x01000B and 0x01000C)
     and in `cs43198_dre_function_enable`. That function was also rewritten: it now writes
     0x0C0001, 0x010010 and the DRE registers to both chips, and the disable path writes 0 where
     1.20 wrote 1.
   - `codec_set_mqa_source` (20 to 71 instructions) now re-applies the current filter with the
     `0xE0` mask to both DACs, then makes one more register-0x090000 call per chip (I2C
     address, and address + 3 when in mode 3).
   - Net effect: in 1.20, the filter, DRE and sample-rate settings could be wrong on the balanced
     output, and NOS never worked on either output.
2. **Machine driver and mixer paths.**
   - `machine_dlkm.ko` gains `Headphone Switch` and `Speaker Switch` kcontrols (`hp_sw_put`,
     `speaker_sw_put`). `output_select_put` grows from 45 to 94 instructions and logs "old target
     -> new target".
   - `mixer_paths_bengal_idp.xml` defaults both switches to 0 and sets them to 1 only inside the
     speaker and headphone paths. This stops the speaker amp and the headphone path being live at
     the same time.
   - `TX_DEC2 Volume` goes from 84 to 100 in the capture paths.
   - The DT overlay moves `qcom,msm-mi2s-master` and `qcom,mi2s-mclk-enable` into the
     `spf_core_platform/sound` node the M500 actually uses, so the PRI MI2S master/MCLK
     configuration now applies. The driver adds "No DT match for mi2s master, set to default".
3. **Headset button handling.**
   - `sa_sound_switch_dlkm.ko` drops `sa_switch_hw_detect_on` and `sa_switch_sw_status`, and
     exports `muti_button_headset_status` and `get_sa_switch_enable`.
   - `mbhc_dlkm.ko` consumes them in `wcd_mbhc_jack_report`, `wcd_btn_lpress_fn`,
     `wcd_mbhc_release_handler` and the OCP IRQs.
   - `AudioService` maps a USB device named `headset_635` to plugged state 12 for the volume
     warning.
4. **Battery and charging (likely the "battery display" and standby complaints).**
   - `cw2015_battery.ko` (the fuel gauge, in vendor_boot and vendor_dlkm) was rewritten. A new
     `cw_update_charge_status` reads the charger power supply (`chrg_usb_psy`) and keeps a
     `shadow_soc`. `cw_bat_work` shrinks from 365 to 243 instructions, and all "FG_CW2015" debug
     logging is gone.
   - `mp2731_charger.ko` adds an OTG enable GPIO (`mp,otg-gpio`) and a DT recharge voltage
     (`mp,recharge-voltag`), and changes `mp2731_charge_update_work`, `mp2731_hw_init` and the IRQ
     thread.
   - The health HAL (`android.hardware.health-service.qti`) now waits and retries for the
     `mp2731-charger` power supply at init ("waiting for mp2731... (attempt %d)") instead of
     reading it once. 1.20 could start before the charger registered.
5. **Notification sounds.** In 1.20, `NotificationManagerService.playSound()` checks audio focus,
   reads the stream volume, then returns `false` without playing anything. 1.30 restores the AOSP
   body (`IRingtonePlayer.playAsync`). No notification sound played at all in 1.20.
6. **USB / Type-C.**
   - `fusb302.ko` `tcpm_set_pd_rx` ignores a redundant enable or disable ("pd is already %s").
   - `dwc3-msm.ko` exports a new `dwc3_msm_typec_orientation_set`.
   - `usb_f_uac2_sa.ko` (the UAC2 gadget used for "DAC in" mode) was rebuilt with small changes.
   - `libar-pal.so` changes `USBDeviceConfig::getDefaultRate` and
     `ECRefDevice::checkAndUpdateSampleRate`. The audio HAL logs USB plug-in card and device.
   - SystemUI gains `UsbDacBroadcastReceiver`, which switches `work_mode` to `dacin` and opens the
     work-mode screen when the USB audio function comes up.
   - `init.hiby.set.dwc3.rt.sh` was tidied: it finds the dwc3 IRQ dynamically. Vendor sepolicy
     lets `vendor_qti_init_shell` read `/proc/interrupts` and run its tools.
7. **Touch.** `gt9xx.ko` (the M500's touch controller, `goodix,gt9xx` at 0x5D) prints the I2C
   address and `Sensor_ID` at probe, and `gtp_init_panel` grows from 222 to 230 instructions. This
   is consistent with selecting a config group per panel (`cfg-group0/2/5` exist in the DT), which
   fits HiBy's "new screen driver" note. `focaltech_fts.ko` (gesture wake-up) and the new
   `sgm3804.ko` LCD bias regulator are for other boards in the shared tree (M300MAX, "TY HG5"); the
   M500 overlay uses neither.
8. **LED.** `leds-sgm31324.ko` replaces `enable-gpio` with `auto-blink-enable-gpio`.
9. **Audio settings plumbing.**
   - `HibyAudioSettingInitUtils` (services.jar) grows from 184 to 575 lines. At boot it now
     restores output, gain, L/R balance, timbre, DSD compensation, turbo, MSEB, PEQ and the active
     DSP plugin. It does this through `HIBY_DSP_SET=...` parameters and properties under
     `vendor.audio.hiby.hw.*`.
   - The filter property is renamed from `vendor.audio.hiby.digital_filter` to
     `vendor.audio.hiby.hw.digital_filter`, in both services.jar and `init.hiby.audio.rc`.
   - `AudioManager.setParameters()` (framework.jar) now intercepts about fifteen
     `vendor.audio.hiby.hw.*` and `vendor.audio.hw.*` keys and forwards them to a new
     `IAudioService.setProperties()` (see section 4, security).
   - `system_ext_property_contexts` exports new capability flags: `ro.vendor.hires_support`,
     `gain_support`, `dsdgain_support`, `lrbalance_support`, `maxvolume_support`,
     `volume_ring_reverse_support`, `dre_support`, `high_power_support`, `peq`, `fm.enable`,
     `cpu_model`, `vibration_support`, `fn_buttion_support`, `workmode_support`, and
     `vendor.audio.hiby.hw.diect_list_app`. Vendor build.prop sets `ro.vendor.cpu_model=sm6225`,
     `hires_support=no`, `vibration_support=yes`, `fn_buttion_support=yes`, `dre_support=yes` and
     `high_power_support=yes`.
10. **Settings and SystemUI (features without changelog lines).**
    - Settings imports HiBy's shared audio module (349 new classes): MSEB 2, PEQ with QR-code
      import, plugin manager, Darwin/RS8II filter assets, and Roon server toggles.
      `AudioSettings` grows by about 1,300 lines.
    - Developer options gain "shutdown_directly" and a log saver to `/sdcard/DevMore/`.
    - About now opens the hidden `ProductTest://` factory screen only if a JSON check passes.
      Before, the tap sequence always opened it.
    - SystemUI adds a gain quick-settings tile (`GainSettingsTile`, `GainBar`) and a sample-rate
      readout in the status bar (`SamplingText`, from `vendor.audio.hiby.hw.sample_rate`).
11. **Other system changes.**
    - `libsmartaudioservice.so` (32-bit) is added.
    - `Telecom.apk` shrinks.
    - `ActivityTaskManagerService` (+47 lines) and `RecentTasks.getTaskList()` change, for the
      launcher.
    - Platform SELinux moves the `usbaudio` domain from system_ext into plat policy and adds
      `usbaudio self:capability dac_override` and `usbaudio audio_service:find`.
    - ADSP firmware (`/vendor/firmware/adsp_444`) changed in 19 segments plus `adsp.mdt`; `adsp.b15`, `b17`,
      `b20`, `b22`, `b23`, `b34` and `b35` also changed size. Not analysed further.
    - `audio.primary.bengal.so` changes `AudioDevice::SetParameters`, `CreateAudioPatch`,
      `StreamOutPrimary::Open/write` and `StopOffloadVisualizer`.
    - `HiByMusic` goes from 2.1.5 to 2.1.8.
- **Unchanged:**
  - `hiby_m500_plat_dlkm.ko`: identical `.text` and the same string set; only rodata ordering
    differs.
  - `radio-si4705-common.ko` and `radio-si4705-i2c.ko`: rebuild noise only, so the RDS 4-of-8-byte
    copy and the dropped stereo pilot are still present in 1.30.
  - `sa_sound_*` other than the switch driver.

**Relevance to MikuOS:**

- **NOS mask:** HiBy fixed it the same way MikuOS did (`0xE0`), so the MikuOS analysis is
  confirmed. MikuOS should also take the second half of HiBy's fix: write the filter, DRE and
  sample-rate registers to the balanced DAC unconditionally. The 0.2.0 patch only widens the mask,
  so on MikuOS a filter chosen while on 3.5 mm still does not reach the 4.4 mm DAC. Either port
  the 1.30 module (needs the 1.30 kernel ABI: vermagic `g7199799f26f2`) or extend
  `patch_cs43198_nos.py` to NOP the two `cmp w8,#0x3; b.ne` gates in
  `cs43198_codec_digital_filter_set`, `cs43198_dai_hw_params` and `cs43198_dre_function_enable`.
  `patch_cs43198_nos.py` refuses the 1.30 module (different md5), which is correct, because 1.30
  needs no NOS patch.
- **Notification sounds:** MikuOS ships the 1.20 services.jar, so notification sounds are silently
  dropped there too. MikuOS should patch `playSound` or rebase on the 1.30+ services.jar.
- **Si4705:** HiBy has not fixed RDS or stereo as of 1.30. MikuOS's `patch_si4705_rds.py` is still
  needed.
- **Filter property rename:** stock 1.30+ listens on `vendor.audio.hiby.hw.digital_filter`.
  MikuOS uses its own `persist.vendor.audio.miku.*` path and init file
  (`hiby-audio-knobs.md`), so it is not affected. If MikuOS ever adopts the 1.30 vendor partition,
  any code that sets the old name stops working.
- **Charger, fuel gauge and health HAL:** the 1.30 boot-ramdisk modules and health HAL fix a
  charger registration race and change SOC reporting. MikuOS, on 1.20 vendor_boot and vendor,
  still has the old behaviour. These are worth adopting with the 1.30 kernel as a unit (module
  vermagic must match the kernel).
- **Mixer paths:** porting the Speaker/Headphone Switch path entries requires the 1.30
  `machine_dlkm.ko`. The `TX_DEC2 Volume` 84 to 100 change can be taken alone.
- **FM Bluetooth output:** HiBy's 1.30 approach is an AudioRecord-to-AudioTrack loop in the app.
  It ties up the capture path, which is why SystemUI 1.30 adds a hidden setting to suppress the mic
  privacy chip (section 3). 1.40 drops the app loop (next section). MikuOS's multi-output design
  should not copy the 1.30 loop.

### 1.40 (published 2026-07-03 to 2026-08-22; 4G build `1.40G_20260703-1757`)

**Official changelog** (Help Center `#hc-m500-4g-and-wifi-versions-v140-firmware-update`, both
variants):

> 1) Added Direct Transport Audio (DTA) support;
> 2) Added maximum battery charge limit (Adjustable range: 50%–100%);
> 3) Fixed several other bugs.
> Kind Reminder: Direct Transport Audio (DTA) supports sampling rates of 44.1/48/88.2/96/176.4/192/384kHz (Applicable only to PO and BAL.PO output ports).

The wiki entry `HiByDigitaly_M500_MIKU_4G_1.40G_20260703-1757` has the same items in other words,
plus "适配新屏驱动，请知悉！".

**What actually changed (1.30 WiFi to 1.40 4G, app and framework layer only):** there are no
partition images, so kernel, HAL and vendor changes are unknown. Variants are also mixed in this
comparison, so some differences may be 4G-specific.

- **DTA:**
  - framework.jar `AudioTrack` (+181 lines) adds `isDirectEnable` and `releaseFlagDirect`. It
    reads `direct_support_app_list` and sets `vendor.audio.hiby.hw.diect_flags_enable=yes|no` and
    `vendor.audio.hiby.hw.diect_proess_name` for apps on the list.
  - services.jar seeds the list as `{"list":[{"packageName":"com.hiby.music"}]}`.
  - Settings adds `addItemNameToDirectList`, `removeItemNamefromDirectList` and `updateDirectList`.

  DTA is therefore a per-app allow-list that puts the app's AudioTrack on a direct output. By
  default only HiBy Music is on it.
- **Charge limit:** services.jar sets `persist.vendor.usb.charging.protect_percent` to
  `max_battery_value + 50` at boot. Settings has `setMaxChargingBattery`. The enforcement side
  (charger driver or health HAL) is not available to inspect.
- **Other bugs and features visible in the pulls:**
  - **FM2.** The 1.30 AudioRecord/AudioTrack/WavHelper loop is removed from the service. This
    suggests BT output moved into the PAL path that `libfmpal` gained in 1.30, but that cannot be
    confirmed without vendor images. FM2 also adds internal-antenna support:
    `FmReceiverJNI.getInternalAntenna()`, and the antenna-available check becomes `internal ||
    headset`. Radio can therefore start without wired headphones on hardware that reports an
    internal antenna. A new receiver, `FMRadioTesterReceiver`, kills the FM process.
  - **Settings** adds `HibySpeakerMutePreferenceController`. services.jar sets
    `vendor.audio.hw.set.mute` from `speaker_mute_status` at boot.
  - **services.jar.**
    - HiBy RM01 remote support (`com.hiby.action.RM01_FN_KEY`, `isFromHiByRM01`).
    - `PhoneWindowManager` +155 lines (touch lock, key logging).
    - `SingleKeyGestureDetector` changes.
    - The M500 audio init is now selected by `Build.PRODUCT`/`MODEL` rather than by model only.
    - At boot, `work_mode` is reset from `dacin`/`bluetooth` to the default, and the USB default
      functions are restored.
  - **AppOpsService** drops a `startOperationImpl` overload, and `PduPersister` loses 27 lines.
  - **Apps:** HiByMusic 2.1.9 and HiByTest 1.6. HiByTape and HiByM500Widget are byte-identical to
    1.30.
  - **Privacy:** Settings calls `https://otaserver.hiby.com/app/support/getSupportDevice`.

**Relevance to MikuOS:**

- DTA as HiBy built it is an allow-list on top of `AudioTrack`, plus properties that the HAL
  reads. MikuOS already gets DIRECT output for its own player, so there is nothing to adopt
  unless system-wide direct output for third-party apps is wanted. Neither DTA nor MikuOS gets
  past the 192 kHz PAL backend (hiby-audio-knobs.md section 10).
- A charge limit is worth having. The HiBy property `persist.vendor.usb.charging.protect_percent`
  is enforced below the framework, so MikuOS could only reuse it if MikuOS runs a 1.40+ vendor or
  kernel. Otherwise it needs its own limit in the charger path (mp2731) or in a userspace service.
- FM internal antenna: given the MikuOS note that wired FM capture is silent, check whether the
  M500's Si4705 build reports an internal antenna (`getInternalAntenna`) before relying on it.

### 1.41 (published 2026-08-22 to 2026-09-22)

**Official changelog** (Help Center `#hc-m500-4g-and-wifi-versions-v141-firmware-update`):

> 1) Added birthday animation for the Mini desktop Miku widget
> 2) Fixed several other bugs.

**What actually changed:** unknown. No package or pull exists. The widget feature would live in
`/vendor/app/HiByM500Widget` (com.hiby.widget), which was unchanged from 1.30 to 1.40.

**Relevance to MikuOS:** not assessable until a 1.41 package is obtained. As of 2026-10-10 the
updater API is still blocked on this network (see section 1).

## 3. Privacy and telemetry across versions

Compared with the 1.40 audit in `m500-system-archive/M500_TELEMETRY_AND_PRIVACY_AUDIT.md`:

| Change | First seen | Detail |
|---|---|---|
| Settings contacts HiBy servers | 1.30 | `https://otaserver.hiby.com/app/earphoneSet/getEarphoneSet`, `.../msebSetting/findMsebSettingList`, `http://server.hiby.com` (arrived with the imported audio/MSEB module) |
| Device support lookup | 1.40 | `https://otaserver.hiby.com/app/support/getSupportDevice` from Settings |
| HiBy Music new services | 1.30 (2.1.8) | `api.deezer.com`, `ws.audioscrobbler.com` (Last.fm), `music.migu.cn` and `pd.musicapp.migu.cn` (China Mobile Migu lyrics and search), `cdn.sonyselect` |
| HiBy Music default permission grants | 1.30 | new `/vendor/etc/default-permissions/com.hiby.music.xml` grants, with no prompt, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `READ_PHONE_STATE`, `PACKAGE_USAGE_STATS`, `NEARBY_WIFI_DEVICES`, `RECORD_AUDIO`, `MANAGE_EXTERNAL_STORAGE`, `ACCESS_MEDIA_LOCATION` and others |
| Serial number exposed to all apps | 1.30 | `HibyAudioSettingInitUtils.initAudioSettings()` copies `ro.serialno` into `Settings.Global` key `ro.serialno` on every boot. `Settings.Global` is readable by any app without permission, while `ro.serialno` itself is not |
| Mic privacy indicator can be hidden | 1.30 | SystemUI reads `Settings.Global hide_mic_privacy_indicator`; when it is 1, microphone items are dropped from the privacy chip (`PrivacyItemController`, `HeaderPrivacyIconsController`) |
| Local play-time counter | 1.30 | system_server polls `vendor.audio.hiby.hw.sample_rate` every 500 ms and accumulates `Settings.Global play_time`. Local only; no upload code was found next to it |
| Abupdate (FOTA) | unchanged | `com.hiby.update` 2.1.36 is byte-identical in 1.20, 1.30 and the 1.40 pull |
| Security patch level | unchanged | 2025-09-03 (system), 2024-12-05 (vendor) in 1.20, 1.30 and 1.40 |

MikuOS ships neither HiBy Music nor HiBy Settings, so most of this does not reach MikuOS users. It
matters if any of these components is ever reused.

## 4. Security finding: unprotected property setter (1.30 and later)

From 1.30, framework.jar's `IAudioService` has two new binder methods:
`setProperties(String key, String value)` (transaction 10) and `getProperties(String, String)`
(transaction 11). In `AudioService` (services.jar) they are implemented as:

```java
public void setProperties(String str, String str2) { SystemProperties.set(str, str2); }
public String getProperties(String str, String str2) { return SystemProperties.get(str, str2); }
```

Neither has a permission check, and neither restricts the key. The `audio` service is reachable by
every app. So any installed app can set any property that system_server's SELinux domain may set,
and read any property system_server may read (including ones apps are normally denied, such as
`ro.serialno`). `AudioManager.setParameters()` uses this path for its `vendor.audio.hiby.hw.*`
keys. The methods are still present in the 1.40 framework.jar.

MikuOS (1.20 framework) does not have this hole. MikuOS should not adopt the 1.30+ framework.jar or
services.jar unless it removes these methods or adds a signature-permission check.

## 5. Summary

| Release | Changelog "other bugs" | Identified changes | Status in MikuOS (1.20 base) |
|---|---|---|---|
| 1.10 | yes | not identifiable (no images) | included in the 1.20 base |
| 1.20 | yes | not identifiable (no 1.10 image); baseline defects documented | is the base |
| 1.30 | yes | NOS mask fixed; balanced-DAC writes ungated; DRE rewrite; notification sounds restored; charger-race fix in health HAL; cw2015/mp2731 rework; Speaker/Headphone Switch kcontrols and mixer gating; MI2S DT fix; multi-button headset; fusb302 PD guard; boot restore of all audio settings; plus FM BT output, cassette UI, desktop pet toggle, WAV FM recording | NOS fixed by MikuOS patch; the rest absent |
| 1.40 | yes | DTA allow-list; charge limit property; FM internal antenna; speaker mute; RM01 remote; FM BT moved out of the app (inferred) | absent |
| 1.41 | yes | unknown (no package) | unknown |

### Top items MikuOS should adopt

1. **Balanced-DAC register writes.** Remove the `mode == 3` gates in `cs43198_dlkm.ko` so the
   filter, DRE and sample rate reach the 0x33 DAC, as 1.30 does. The current NOS patch fixes only
   the mask.
2. **Notification sound.** Restore the AOSP body of `NotificationManagerService.playSound()`. The
   1.20 services.jar MikuOS ships never plays notification sounds.
3. **The 1.30 kernel-side charging fixes as one unit:** kernel, vendor_boot and vendor_dlkm, the
   health HAL's wait-for-mp2731 retry, and the cw2015 `cw_update_charge_status` rework. If a full
   kernel rebase is too much, at least add the health HAL retry. It is userspace and has no ABI tie
   to the kernel.
4. **Mixer and DT.** The MI2S master/MCLK properties now sit in the `spf_core_platform/sound`
   node, and the Speaker/Headphone Switch gating means the speaker amp is off on headphone paths.
   Both need the 1.30 dtbo and `machine_dlkm.ko`.
5. **Charge limit** (1.40 feature). Implement it in MikuOS's own stack; HiBy's property is
   enforced in components we have not seen.

Do not adopt: the 1.30+ framework/services `setProperties` binder, the `ro.serialno` copy into
`Settings.Global`, the hidden mic-indicator switch, and HiBy Music's default permission grants.
