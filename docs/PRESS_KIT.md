# HioneOS press kit

**For:** whoever is building the hioneos.falcontechnix.com and falcontechnix.com pages (the
flasher, the project page, the press post). This file is the brief. Everything in it is either
verifiable in the source or measured on the device, and where something is unproven it says so.
What changed on the sites, and the asset paths, are in [WEB_HANDOFF.md](WEB_HANDOFF.md).

**Status: work in progress, and help is wanted.** Say that plainly on every page. This is one
person, one device, and a lot of measurement. The HioneOS source isn't public yet, so the contact
is email: github@falcontechnix.com.

---

## The rename, in one paragraph

As of 2026-10-11 the project is **HioneOS**. The music app is **Hione Hits**, and the mascot is
Hione (Oki Hione, 隠岐 氷音), owned by Falcon Technix. Earlier releases were called MikuOS, a fan
project built around Crypton's Hatsune Miku for the M500's Hatsune Miku edition. The old MikuOS and
MikuMusic repos are private now and abandoned. Say that once, plainly, and move on. Don't make the
rename the story.

---

## Versions

| | |
|---|---|
| On the author's M500 | HioneOS 0.4.4, flashed 2026-10-11. It still shows Hione names on apps with `com.miku.*` package IDs |
| Next | HioneOS 0.5.0: the packages move to `com.hione.*`, with a data migration. In progress, not shipped |
| Last published source | MikuOS 0.3.0, 2026-10-10, in repos that are now private |
| Next major version | HioneOS on LineageOS 23.2 (Android 16). Planned, nothing built yet |

---

## The one-liner

> HioneOS is a platform-signed Android 14 replacement for the HiBy Digital M500 digital audio
> player. Its player hands local files to the audio driver at the file's own rate with no Android
> mixer in the path, and its own clean-room DAC driver runs the dual Cirrus Logic CS43198 DACs at
> the track's own rate for the 48 kHz family. It doesn't display a number it didn't measure.

Don't describe the output as untouched end to end. 44.1 kHz family music is still converted up to
the 48 kHz family at the DAC, and until 2026-10-10 the docs overclaimed this. The section below has
the whole path.

## Where things live

| What | Where |
|---|---|
| HioneOS | Source isn't public yet. Say so. Don't invent a repo address |
| The project site | hioneos.falcontechnix.com |
| Hione, the measurement tool | https://github.com/sworrl/hione (public, link it), and in the browser at https://hione.falcontechnix.com |

Don't link github.com/sworrl/MikuOS or github.com/sworrl/MikuMusic anywhere. They're private.

---

## Why it exists (use this framing, not a feature list)

The M500 is very good audio hardware running software that gets in its way. On stock firmware
HiBy Music gets a direct output and other players go through Android's mixer, which resamples
them. Below that, HiBy's DAC driver runs both DACs at 192 kHz for every track, whatever app is
playing. HioneOS fixed the first part for its own player. For the second part it replaced HiBy's
DAC driver with its own, and now the DACs follow the track for the 48 kHz family.

The other reason is smaller and matters more in practice: audio apps lie constantly. They show a
bit depth taken from a filename, a sample rate that's the mixer's and not the file's, a BPM invented
from the title, an EQ curve connected to nothing. HioneOS shows a dash where it doesn't know, and
the house rule is "never display a value you did not measure".

---

## The audio path, captured on the device

A live `dumpsys media.audio_flinger` while a 24-bit / 96kHz FLAC plays. It's in the source tree at
`docs/screenshots/bitperfect-audioflinger.txt` (an old file name, kept so links don't break; don't
quote the name):

```
Output thread 0xb400007846c31840, name AudioOut_14D, tid 25861, type 1 (DIRECT):
  Sample rate: 96000 Hz
  HAL format: 0x6 (AUDIO_FORMAT_PCM_24_BIT_PACKED)
  Channel count: 2
  Processing format: 0x6 (AUDIO_FORMAT_PCM_24_BIT_PACKED)
  Output devices: 0x8 (AUDIO_DEVICE_OUT_WIRED_HEADPHONE)
```

What it shows, and it's all true: the output thread is **DIRECT**, not MIXER, so Android's mixer
never touches the track. The sample rate is the file's 96kHz, not the mixer's, and it changes with
every track. The format is 24-bit packed integer, so nothing in Android converts it to float or
dithers it. That's the whole of Android's part, and it's where this capture stops.

### Below Android, with HiBy's driver: 192 kHz for every track

Found 2026-10-10 on 0.3.1, from device logs and HiBy's vendor partition.

1. The audio HAL hands the stream to PAL (Qualcomm's AudioReach layer) at the file's rate.
2. PAL picks the rate for the output device from `/vendor/etc/resourcemanager_bengal_idp.xml`,
   which gives the wired headphone and headset devices 192000 Hz and 32-bit, and replaces the
   stream's rate. Identical in HiBy 1.20 and 1.30.
3. The ADSP converts the track to 192 kHz / 32-bit before the I2S link to the DACs.
4. HiBy's DAC driver writes the CS43198's rate register as 192 kHz at every power-on, about 140 ms
   before Android says what the track's rate is, and never updates it.

So with HiBy's driver the DACs got 192 kHz whatever the file was, on stock too. HiBy Music plays
through the same path.

### With Blackjack: the 48 kHz family at its own rate

HioneOS changes the PAL config so the wired backend follows the track, and Blackjack, our own DAC
driver, makes the DACs follow the backend. Running since 0.4.1 (2026-10-10).

* 48, 96, 192 and 384 kHz files run the DACs at their own rate. Confirmed by measurement: at a
  48 kHz source the NOS filter droops -2.62 dB at 20 kHz, which is what a DAC really running at
  48 kHz gives. At 192 kHz it was -0.15 dB.
* 44.1 kHz family files are converted up to the matching 48 kHz family rate (44.1 to 48, 88.2 to
  96, and so on). Playing them at their own rate needs a second clock (22.5792 MHz). Not done.

### The bug worth telling, if the post wants one technical story

Android's `AudioTrack.Builder.build()` quietly adds `FLAG_DEEP_BUFFER` when the requested buffer is
around 100ms or larger, and Qualcomm's policy manager only routes to the `direct_pcm` profile when
the flags are NONE. So asking for a comfortable buffer silently opts you out of direct output. The
custom sink asks for a buffer under that threshold on purpose.

It then had a bug for months: it computed the small buffer and clamped it with
`max(getAudioTrackMinBufferSize(), threshold)`. On this device the platform minimum is about double
the threshold, so the `max` always won, and every track was deep-buffered onto a 192kHz mixer while
the app reported a direct output the whole time. Fixed 2026-09-17. If you're writing a direct output
sink for any Android device, that's the trap.

The second story, if there's room: the DAC driver set the rate before it knew the track's rate and
never looked again, so the only fix was writing a new driver.

---

## Measured: the M500's 3.5 mm output

Both runs used Hione, the audio measurement tool written alongside HioneOS. The M500 played log
sweeps and test tones over its 3.5 mm jack into a desktop's onboard Realtek ALC1220 line-in,
captured at 192 kHz / 32-bit, on battery with the USB cable unplugged (with USB in, a ground loop
added tones at 4.75 kHz and 226 Hz).

### 2026-10-11: Blackjack, on 0.4.1

| What | Result |
|---|---|
| Level, gain high, 44.1 kHz source, 1 kHz at -12 dBFS | -9.2 dBFS |
| THD+N | -81.1 dB |
| Noise | -93.3 dB(A) |
| SNR | 96 dB |
| NOS at a 48 kHz source | -2.62 dB at 20 kHz, as predicted for a 48 kHz zero-order hold |

That's the same as HiBy's driver the day before. Both sit at the ALC1220's own floor, so the honest
claim is that Blackjack measures as clean as HiBy's driver as far as this card can show. Not
cleaner. The NOS droop is the proof the DAC really runs at 48 kHz.

### 2026-10-10: HiBy's driver, on 0.3.1

Charts are in `docs/measurements/2026-10-10-m500-3.5mm/` in the source tree, raw numbers in
`summary.csv` there. The tool's own repo has the same run, and it's public.

| What | Result | How much to trust it |
|---|---|---|
| Gain high vs low | exactly 12.0 dB apart | solid |
| THD at 1 kHz, -12 dBFS | -99 to -100 dB (H2 about -103 dB) | solid |
| THD+N | -81 dB (gain high), -69 dB (gain low) | limited by the capture card's noise |
| Noise, SNR, dynamic range | about 96 dB(A) re full scale, the same in every mode | that's the ALC1220's own floor: the M500 is quieter than this card can show |
| Crosstalk | -91 dB | solid |
| Channel balance | -0.08 dB | solid |
| Clock vs the capture card | +47 ppm | solid |
| High power, DRE | no measurable change into a line input | as expected at line level. They matter into headphones |

**The finding worth telling: with HiBy's driver the five digital filters measured identical.** Fast
or slow roll-off, low latency or phase compensated, or NOS: the frequency response matched within
0.01 dB up to 20 kHz at both 44.1 and 96 kHz sources, and the impulse response was the same in
every mode. That was the 192 kHz backend: the ADSP's resampler did the filtering, and the CS43198's
own filter worked at 192 kHz, far above anything audible. With Blackjack at 48 kHz, the filters act
in the audio band again. Nobody has done a listening test yet.

Every mode, left channel, 1 kHz at -12 dBFS digital, volume 100/100, 3.5 mm into a line input:

| Mode | Source | Gain | Level (dBFS) | THD (dB) | THD+N (dB) | Noise A (dBFS) | Crosstalk (dB) |
|---|---|---|---|---|---|---|---|
| Fast, low latency | 44.1 kHz | low | -21.2 | -94.8 | -69.1 | -93.3 | -89.0 |
| Fast, low latency | 44.1 kHz | high | -9.2 | -99.4 | -81.1 | -93.2 | -91.5 |
| Fast, phase comp. | 44.1 kHz | low | -21.2 | -92.7 | -68.9 | -93.2 | -91.2 |
| Fast, phase comp. | 44.1 kHz | high | -9.2 | -99.2 | -80.7 | -93.2 | -91.1 |
| Slow, low latency | 44.1 kHz | low | -21.2 | -93.3 | -68.7 | -93.3 | -90.6 |
| Slow, low latency | 44.1 kHz | high | -9.2 | -98.5 | -80.8 | -93.2 | -91.7 |
| Slow, phase comp. | 44.1 kHz | low | -21.2 | -94.1 | -69.2 | -93.2 | -91.0 |
| Slow, phase comp. | 44.1 kHz | high | -9.2 | -98.6 | -80.8 | -93.2 | -90.6 |
| NOS | 44.1 kHz | low | -21.2 | -92.6 | -68.9 | -93.2 | -93.0 |
| NOS | 44.1 kHz | high | -9.2 | -99.9 | -80.8 | -93.3 | -91.3 |
| Fast, low latency | 96 kHz | low | -21.2 | -93.1 | -68.9 | -93.2 | -90.7 |
| Fast, low latency | 96 kHz | high | -9.2 | -99.3 | -80.9 | -93.2 | -90.8 |
| Fast, phase comp. | 96 kHz | low | -21.2 | -93.2 | -69.2 | -93.3 | -91.7 |
| Fast, phase comp. | 96 kHz | high | -9.2 | -99.6 | -80.9 | -93.2 | -91.3 |
| Slow, low latency | 96 kHz | low | -21.2 | -92.2 | -69.2 | -93.3 | -90.5 |
| Slow, low latency | 96 kHz | high | -9.2 | -100.4 | -81.0 | -93.3 | -91.1 |
| Slow, phase comp. | 96 kHz | low | -21.2 | -94.7 | -69.1 | -93.3 | -91.7 |
| Slow, phase comp. | 96 kHz | high | -9.2 | -99.4 | -80.8 | -93.3 | -90.9 |
| NOS | 96 kHz | low | -21.2 | -93.2 | -69.2 | -93.2 | -91.0 |
| NOS | 96 kHz | high | -9.2 | -98.8 | -81.1 | -93.2 | -90.4 |
| HP off, DRE off | 44.1 kHz | high | -9.2 | -99.4 | -80.9 | -93.2 | -91.1 |
| HP off, DRE on | 44.1 kHz | high | -9.2 | -99.8 | -80.8 | -93.3 | -90.8 |
| HP on, DRE off | 44.1 kHz | high | -9.2 | -99.5 | -80.6 | -93.2 | -90.7 |
| HP on, DRE on | 44.1 kHz | high | -9.2 | -99.4 | -80.9 | -93.3 | -91.4 |

Charts:
- `digital-filters-at-44-1-khz-gain-high-top.svg` (the top octave, all five filters on top of each other)
- `digital-filters-at-44-1-khz-gain-high-ir.svg` (impulse, identical in every mode)
- `digital-filters-at-96-0-khz-gain-high-top.svg`
- `digital-filters-at-44-1-khz-gain-high-fr.svg`, `high-power-and-dre-fr.svg`, `thdn.svg`

Every number goes through the capture card. Its noise and its own filter are part of every result,
and a device better than the card measures as the card. Say that wherever the numbers appear.

Still to measure: the 4.4 mm balanced output (needs a balanced-input ADC), output voltage (needs a
level calibration), the ALC1220's own loopback, and a full 24-mode Blackjack run with charts.

---

## Blackjack, our own audio driver

### What it is

HiBy's DAC driver pinned the DACs to 192 kHz. HiBy allows us to replace anything but won't share
source, so we wrote our own driver clean room: `blackjack_codec.ko` for the two CS43198s, from the
public datasheet and mainline Linux `cs43130`, and `blackjack_hooks.ko` for the platform glue. One
team writes a spec from HiBy's binaries and the device's behavior, with no code in it. A second
team writes the driver from that spec, the datasheet and mainline source, and never sees HiBy's
binaries.

Status: running on the author's M500 since 2026-10-10 23:51 EDT (0.4.1, first boot clean, both
chips answering), in every image since, and measured on 2026-10-11 (above). Native rate covers the
48 kHz family only. Still open: the 44.1 kHz family at its own rate, and the 4.4 mm word length
(needs a balanced-input ADC).

### First listen

The author's reaction at the first listen, verbatim:

> "it actually sounds better, idk if placebo. Hot fucking damn."
>
> "HOLY SHIT THE AUDIO... WOW... it was pretty good before.. but uhh.. fuck me sideways.."

That's one person's ears. Quote it as a reaction, never as a result. The measurement says
"as clean as HiBy's driver, at this card's limit". Pages that need a clean version can use:
"HOLY SHIT THE AUDIO... WOW... it was pretty good before." (profanity cut).

### The name

The name is a Bender joke. Use the meme as is: `art/blackjack/blackjack-meme.png` (lossless JPEG
XL: `art/blackjack/blackjack-meme.jxl`, WebP: `.webp`). Serve the JXL with a PNG or WebP fallback,
since most browsers can't show JXL. Futurama and Bender belong to their owners. This is a fan meme,
not an endorsement.

---

## The other drivers

The same clean-room process is going through the rest of HiBy's closed modules. HiBy has given
permission to replace anything ("everything but source code sharing").

| Part | State |
|---|---|
| DACs (2x CS43198) | Blackjack. Running since 0.4.1, measured |
| Status light (SGM31324) | Ours. Running since 0.4.2. In 0.4.4 it shows the track's format colour and pulses on the beat. Confirmed by the owner on 2026-10-11 |
| FM tuner (Si4705) | Ours built, failed its first hardware test on 0.4.2 (the chip didn't answer its first command), pulled from 0.4.3. HiBy's driver with our RDS patch is back meanwhile |
| Touch, speaker amps, keys and wheel, DAC settings nodes and jack switch, charger, fuel gauge | Specs written. Not built |
| Wi-Fi, Bluetooth, display | No driver rewrite needed. Config plans written. The Wi-Fi power-save fix is in 0.4.3 and later, not confirmed yet |

Say "specced", not "coming soon", for the ones that aren't built.

---

## Security findings in HiBy's modules

Reading the binaries for the specs turned up modules that can be crashed or abused from userspace.
They ship on stock, and HioneOS 0.4.4 still runs the ones not yet replaced. State these plainly. No
drama, no "exploit" headlines.

* `/proc/gmnode`, from HiBy's touch driver, is world-writable (0666) and gives raw I2C access to the
  touch controller and its firmware update path. Whether SELinux keeps normal apps away from it
  hasn't been checked.
* The volume wheel driver's `ring_key_reverse` node copies a written string into a 10-byte stack
  buffer with no length limit. A long write panics the kernel. The node is root-only.
* HiBy's `sa_sound` modules: `sa_switch_enable` has the same kind of stack overflow, and the 31 DAC
  settings nodes copy input into fixed 4 to 32 byte buffers with no length check. Writing `dac_type`
  makes the writer loop forever. Root-only nodes.
* A Wi-Fi factory-test hook in HiBy's init makes the WLAN driver's mode parameter world-writable
  when a vendor property is set, and switches the driver to factory test mode. Whether anything sets
  that property hasn't been checked.
* HiBy 1.30's framework adds a call that sets system properties with no permission check. HioneOS
  stays on the 1.20 framework.

None of these are fixed in an image yet. The replacement drivers are specced not to have them.

---

## Roadmap

* **Now:** HioneOS on Android 14, HiBy's image re-keyed, HiBy's drivers replaced one by one.
* **0.5.0:** the package move to `com.hione.*`. In progress.
* **Next major version:** HioneOS on LineageOS 23.2 (Android 16). Planned, not built.
* **Later:** kernel 6.12. Qualcomm goes from 5.15 straight to 6.12 for this chip, and every HiBy
  module would need porting, which is part of why the clean-room drivers exist.

---

## What's in 0.4.x

0.4.0 to 0.4.4 have all run on the author's M500. On the device isn't the same as confirmed, so
only items marked Yes are confirmed.

* **Blackjack, native rate for the 48 kHz family** (0.4.1). Yes, measured.
* **Our LED driver** (0.4.2), with **format colours and a beat pulse** (0.4.4). Yes, 2026-10-11.
* **Blackjack Monitor** (0.4.4): the Hardware app shows which DAC driver is bound, the rate, power
  and the registers, rebuilt from the driver's log. Not yet.
* **Wi-Fi reachable from the LAN while idle** (0.4.3). Not yet.
* **Hione edition images** (0.4.1 on): Hione's art, colours, names, boot video and system popups.
  Running daily.
* **Settings, About says which audio driver is running,** and the home screen's status card can live
  in a pull-up sheet (0.4.1). Not yet.
* From 0.4.0, all Not yet: seasonal and holiday wallpapers, tempo from QM-DSP and BTrack, the DAC
  response graph, tap to wake, no screen wake on charger plug, FM seek and scan on the Si4705,
  weather radar and pollen and optional Windy keys, Android Auto permissions up front.
* **System updates over the air (A/B): No.** The keys are in the image, but the image build writes
  only one slot into super, so the other can't boot. Don't tell anyone they can update the system
  from the device.

---

## Hione

The name covers two things, both by sworrl. They share the character.

### The mascot

Hione (氷音, "ice tone", said HEE-oh-neh), full name Oki Hione (隠岐 氷音), is HioneOS's mascot and
its identity. She's an original character created by Falcon Technix, which owns her outright.

* Her palette is Tally Amber `#FFAB2E` and Ice Blue `#8EC5FF` on Gunmetal Black `#111216`. Amber
  buttons take dark labels. White on amber fails contrast.
* Her motif is the coda mark, a circle crossed by a plus sign. In music it means "jump to the
  ending", and it reads as a scope reticle.
* Her voice today is a stand-in made with the same offline pipeline as the other HioneOS voices.
  Her own voice is planned as a synthetic voice designed and owned by the project. No hired voice,
  no cloud voice.
* Her art was made with an image model. Same open ask for art drawn by a person.

Miku survives only as an optional art and colour pack inside the OS, and may not stay. Don't use
any Miku art or name on the site.

### The measurement tool

Hione is also the audio measurement tool named after her. It plays a log sweep and test tones
through the M500 out of the headphone jack into a PC sound card's line input, and reports frequency
and impulse response, THD and THD+N, noise and dynamic range, crosstalk, channel balance and clock
drift. On the M500 it sets every DAC mode over adb and measures all of them in one run with no
hands, about 15 minutes for 24 modes, and puts the user's settings back at the end. It has a live
web UI and exports charts as SVG and PNG. The numbers above came from it.

It's public: https://github.com/sworrl/hione. A browser version at https://hione.falcontechnix.com
measures from the visitor's own audio input, on their machine. Link both.

---

## Screenshots

All in `docs/screenshots/` in the source tree, 720x1280, captured on the device on 0.3.0,
2026-10-10. Network names, addresses and the town are blurred. These predate the rename, so they
show the old theme and old app names. Caption them as 0.3.0, and prefer the ones without character
art. Hione edition screenshots come with 0.5.0.

| File | What it shows | Best used for |
|---|---|---|
| `01-now-playing-hires.png` | Now Playing a 24-bit / 96kHz FLAC: format, bitrate, the visualizer, the wavy scrubber | The hero image |
| `02-fullscreen-visualizer.png` | Real libprojectM, fullscreen | The visualizer section |
| `03-tape-mode.png` | The cassette deck, grading itself Type IV from a 24-bit 96kHz track | The "this isn't a skin" section |
| `04-library.png` | Library stats: 18,087 tracks, 506 artists, 1,870 albums, 98% FLAC | Scale |
| `06-albums.png` | The album grid with per-album format badges | The library |
| `07-hardware-observatory.png` | The Hardware screen: chip, RAM, the OS build and app versions, the DAC state and part numbers | The honesty section |
| `08-bpm-game-played.png` | The BPM game mid-run: 184 BPM, a 24-tap streak, a full crowd meter | The fun section |
| `09-launcher-home.png` | The launcher: clock, weather, sun times, a live NWS alert, the DAC badge, battery | The OS section |
| `dac-badge.png` | The DAC badge in the status bar over another app: `FAST-PC · HI · DRE · HP` | DAC settings |
| `fm-main.png`, `fm-spectrum.png`, `fm-song-id.png` | The FM app: RDS text and signal, the FFT and waterfall, song ID | The FM section |
| `riot-mode.png`, `mikupod-2007.png` | The two easter eggs | The fun section |

The rest of the folder is listed in WEB_HANDOFF.md.

---

## What is actually verified (don't overclaim)

**Confirmed on hardware:** DIRECT output with no Android mixer at 44.1 / 48 / 96 / 192kHz, 24-bit
packed, handed to the audio driver at the file's own rate. Blackjack running (0.4.1) with the DACs
at the track's own rate for the 48 kHz family, and measured (2026-10-11). Our LED driver, with
format colours and the beat pulse (0.4.4, 2026-10-11). Platform signing with no root. A re-keyed
system image that boots. APEX re-signing. Gesture navigation replacing the stock nav bar. OS-wide
idle dim on real system brightness. Fn-key pocket lock without root. The camera's auto-exposure used
as an ambient light sensor, because the device has no ALS at all. Real libprojectM 4.2.0. The FM
tuner, audible since 0.1.15. FM RadioText and program type on a strong station (0.1.17). FM song ID
and the live spectrum on the 4.4 mm output (0.3.0). DAC settings reaching the driver, from its own
kernel log. NOS selectable. Notification sounds. CPU power profiles. Screen lock with a PIN. Riot
mode and HionePod. Android Auto installed as a privileged app. The 3.5 mm measurements above.

**Implemented but not proven:** Blackjack Monitor. The Wi-Fi power-save fix. The LDAC push path.
The web installer on a second device. The FM stereo light and lower stereo blend. Android Auto in a
car. Whether NOS is audible now that it acts in the audio band. The charge limit holding. "Ask for
PIN only after restart" across a reboot. Playing to every output at once with the newer sync and
per-output volume. The 0.4.0 features listed above.

**Tried, doesn't work:** our own Si4705 FM driver (first hardware test failed, being fixed). The
44.1 kHz family at its own rate (converted to the 48 kHz family). System updates on the device (A/B
needs both slots in super first). A phone playing through the M500 over Bluetooth (the M500 is
source-only). A generic system image (didn't boot, cause unproven). Kernel 5.15.209 (one test
showed the charger input offline; the cause was never found, so it's set aside, not disproven). A
full AOSP-from-source build has never produced an image.

---

## The flasher page

The installer goes at `hioneos.falcontechnix.com/install/`, in bring-your-own-images mode. It's a
GrapheneOS-style browser installer: static files, no build step, no backend. The user plugs the
M500 into a PC, opens the page in Chrome or Edge, and clicks through a wizard. Everything runs
client-side over **WebUSB fastboot** using kdrag0n's `fastboot.js` (MIT, vendored unmodified).

Read `tools/web-installer/README.md` before changing the page. The short version of what it already
handles, and why, because these are device facts and not preferences:

* **super is 5,371,461,632 bytes exactly**, and the bootloader reports product `khaje`. The
  installer refuses a bundle of any other size and any device that differs.
* **The M500's USB gadget wedges on big bulk pushes.** Every payload goes as a self-contained sparse
  image of 64 MiB or less, one at a time, each chunk retried up to three times. A 90 second
  no-progress watchdog detects a wedged gadget, closes the USB device, and tells the user to
  power-cycle. "Reconnect & Resume" continues at the exact chunk.
* **Never two fastboot streams.** A single lock serializes every command.
* **Fastboot doesn't charge the battery.** A device reading 0mV in fastboot isn't dying, it isn't
  being told. The wizard requires a battery check before it will start.
* **Integrity before upload.** Every image carries a whole-file SHA-256, and images over 128 MiB
  also carry one per 63 MiB chunk. The browser verifies with Web Crypto before sending.

The installer hosts no firmware. The user builds the image from the stock firmware on their own
M500. Say that, and leave it there. While the source isn't public, also say that building an image
isn't possible for others yet.

**Say on the page that flashing can brick the device.** It's used daily on the author's own M500.
That's a statement about one device, not a warranty about the reader's.

---

## Tone

Plain, short sentences. US English. No brochure language, no invented product names, no
"revolutionary". Lead with what was measured. Where something is unproven, say "not proven" or
"not yet" rather than softening it. The project's whole pitch is that it doesn't overclaim, so a
press page that overclaims would undercut it. Don't describe the audio as untouched or perfect
end to end.

Don't write "AI-generated" or credit any tooling. Attribute the author: **sworrl**.

---

## Legal, put a version of this on every page

GPL-3.0-or-later. Hione™, Oki Hione™, HioneOS™, Hione Hits™ and the other Hione names and marks
are trademarks of Falcon Technix, which created the character and owns her outright. Use the TM
mark on first mention of each name per page. Don't use the R mark: nothing is registered yet.

Earlier releases were called MikuOS, a fan project built around Crypton Future Media's Hatsune Miku
for the M500's Hatsune Miku edition. Hatsune Miku is Crypton's. Any Miku art left is in an optional
pack. The project isn't affiliated with or endorsed by Crypton or HiBy Digital.

Much of the art, Hione's included, was made with an image model, and some older images in the
source tree came off a stock M500 and are HiBy's. The plan is for the build to pull HiBy's files
from the firmware the user already owns and for the source to ship only art made for the project.
HiBy Digital's firmware, bootloader and audio HAL are theirs and aren't redistributed, which is why
there's no image to download.

Credits: libprojectM (LGPL-2.1, used as a library). Media3 and AndroidX (Apache-2.0). Fonts from
Google Fonts (Orbitron, the wordmark font, and the rest under the SIL OFL, Permanent Marker under
Apache 2.0). Voices rendered from Kokoro-82M (Apache-2.0). Listening-profile EQ from AutoEq (MIT).
Weather from Open-Meteo, radar from RainViewer, map data from OpenStreetMap contributors, NWS alerts
and the NOAA transmitter list from the National Weather Service, optional Windy data with the user's
own keys. FM station data from the FCC and English Wikipedia (CC BY-SA 4.0). The Blackjack meme is a
fan meme by sworrl; Futurama and Bender belong to their owners. Full list in ATTRIBUTIONS.md.

---

## Contributing, and please make this prominent

The project is a work in progress and help is welcome. The HioneOS source isn't public yet, so the
contact is github@falcontechnix.com. The measurement tool takes issues and pull requests at
https://github.com/sworrl/hione.

Things that would help most:

* **Another M500.** Everything is verified on exactly one device.
* **A balanced-input ADC run** of the 4.4 mm output.
* **A capture card quieter than an onboard ALC1220**, to tell Blackjack and HiBy's driver apart if
  they differ at all.
* **An LDAC sink**, to prove or disprove the Bluetooth codec path.
* **A NOS listening test** at the 48 kHz family.
* **FM listening reports** from somewhere with a clear line to a transmitter.
* **A car with Android Auto**, to find out whether audio reaches it over a wired connection.
* **HiBy firmware 1.40 or 1.41 images**, so the firmware history can be carried forward.
* **Original art of Hione drawn by a person.**
* Anywhere a number on screen isn't measured. That's treated as a bug, and a report of one is as
  useful as a patch.

One house rule, worth repeating on the site: **never display a value you didn't measure.** No
placeholder percentages, no bit depth inferred from a file extension, no BPM guessed from a title.
If the data isn't there, show a dash and say why.

---

## Do not publish

For whoever builds the pages. None of these go on any site, page, image caption or alt text.

* **Art that isn't in the asset list in WEB_HANDOFF.md.** Use those files and only those. Don't
  browse the private source tree for more, and don't describe or hint at anything outside the list.
* **Links to the old MikuOS and MikuMusic repos.** They're private. Also don't invent a HioneOS repo
  address: there isn't a public one.
* **The security plan document**, or anything taken from it. The security findings section above is
  the public version.
* **The clean-room spec team's private notes,** or any HiBy disassembly.
* **Keys of any kind, device serial numbers, IP addresses, and the names of the machines used for
  measuring.** The charts and screenshots listed have these left out or blurred. Keep it that way
  in anything new.
* **Anything about asking HiBy whether its firmware can be redistributed.** The public line stays
  the same: no firmware is hosted, bring your own.
