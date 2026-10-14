# Attributions and licensing

This file records what is in this project, who made it, and under what license. It covers the
MikuOS repo and the [MikuMusic](https://github.com/sworrl/MikuMusic) repo, which ships the player,
launcher, system UI, settings and hardware apps.

Corrections are welcome. If something here is wrong, or something is missing, open an issue.

---

## 1. Our code

Everything written for this project is GPL-3.0-or-later. See [LICENSE](LICENSE).

Copyright holder: sworrl (github@falcontechnix.com).

---

## 2. Hatsune Miku and Crypton Future Media

Hatsune Miku, the character name, and the associated character designs are the property of
Crypton Future Media, Inc. This project is unaffiliated with Crypton and is not endorsed by
Crypton. It is a hobby project for a device Crypton licensed to HiBy Digital.

Crypton publishes the Piapro Character License (PCL) for non-commercial derivative works of their
characters. Original art made for this project is intended to sit inside that framework. The PCL
does not grant any right to redistribute artwork that a third party made under a separate license
from Crypton, which is the subject of section 3.2.

---

## 3. Artwork

Artwork in this project falls into three groups. They have different provenance and different
licensing positions, so they are listed separately.

### 3.1 Generated with an image model

A portion of the Miku artwork in this project was produced with a generative image model, not drawn
by a human artist. That includes the renders under `miku-assets/miku_renders/` and its `banners/`
subdirectory, the images under `miku-assets/reference_favorites/`, and several of the theme
wallpapers under `miku-assets/themes/`.

These are derivative works of Crypton's character design. They were made for this project and are
non-commercial. They are not the work of any human artist and are not presented as such.

We would rather ship art made by people. See [section 7](#7-we-are-looking-for-real-art).

### 3.2 Extracted from the stock M500 firmware

Some images in this project were taken off a stock HiBy Digital M500. They are HiBy's files,
depicting Crypton's character, and there is no license here to redistribute either layer.

Known instances:

| Location | Files | Origin |
|---|---|---|
| `miku-assets/official-device/` | `ic_sprite.png`, `miku_background_view.png`, `miku_default_cover.png`, `clock_widget_miku_preview.png` | Extracted from stock M500 firmware. **Removed from the tree on 2026-09-27.** Nothing in the build used them. They remain in git history |
| MikuMusic `app/src/main/res/drawable-nodpi/` | `wall_paper_0`..`9`, `miku_wall_00`..`09`, `miku_extra_00`..`17`, `miku_sprite`, `miku_cover`, `miku_clock_preview`, `miku_wallpaper` | Same, renamed and re-encoded |
| MikuMusic `app/src/main/res/drawable-nodpi/` | The `ic_*_miku` themed launcher icon set (about 60 files, including icons for WeChat, Zhihu, Bilibili, Ximalaya, Kugou, Kuwo, QQ and other Chinese-market apps) | Stock M500 theme set |

Owning the device licenses the copy on the device. It does not license redistribution, and gating
our code to M500 hardware does not change that.

The intended end state is that the build extracts these from the stock firmware the user already
owns, and the repos ship only art we made. That work is not done. Until it is, the accurate
statement is that these files are present and should not be.

**Takedown:** if you are HiBy Digital or Crypton Future Media and want any of this removed, open an
issue or email github@falcontechnix.com. It will be removed.

### 3.3 Original

`miku-assets/falcon_technix_logo.webp` is the project's own brand mark.

Screenshots under `docs/screenshots/` are captures of this software running on the author's own
device. They necessarily contain artwork covered by 3.1 and 3.2.

---

## 4. Fonts

All fonts are from Google Fonts and ship in the APKs. Each font's own license file is the
authority; the summary below is for orientation.

| Font | File | Designer | License |
|---|---|---|---|
| Audiowide | `audiowide.ttf` | Brian J. Bonislawski | SIL OFL 1.1 |
| Baloo 2 | `baloo2.ttf` | Ek Type | SIL OFL 1.1 |
| DotGothic16 | `dotgothic16.ttf` | Fontworks | SIL OFL 1.1 |
| Kalam | `kalam_bold.ttf` | Indian Type Foundry | SIL OFL 1.1 |
| Mochiy Pop One | `mochiypopone.ttf` | Fontworks | SIL OFL 1.1 |
| Monoton | `monoton.ttf` | Vernon Adams | SIL OFL 1.1 |
| Orbitron | `orbitron.ttf` | Matt McInerney | SIL OFL 1.1 |
| Permanent Marker | `permanent_marker.ttf` | Font Diner | Apache License 2.0 |
| Righteous | `righteous.ttf` | Astigmatic | SIL OFL 1.1 |

---

## 5. Libraries

### 5.1 Linked at build time

| Library | License | Notes |
|---|---|---|
| AndroidX and Jetpack Compose | Apache-2.0 | Google |
| Kotlin standard library and coroutines | Apache-2.0 | JetBrains |
| Media3 / ExoPlayer | Apache-2.0 | Google. One class is forked, see 5.3 |
| OkHttp (`com.squareup.okhttp3`) | Apache-2.0 | Square |
| Coil (`io.coil-kt`, `coil-compose`, `coil-gif`) | Apache-2.0 | Coil Contributors |
| Haze (`dev.chrisbanes.haze`) | Apache-2.0 | Chris Banes |
| WireGuard for Android (`com.wireguard.android:tunnel`) | Apache-2.0 | "WireGuard" is a registered trademark of Jason A. Donenfeld |
| jaudiotagger (`net.jthink`) | LGPL-2.1-or-later | Used as a library, not modified |
| libVLC and medialibrary (`org.videolan.android`) | LGPL-2.1-or-later | VideoLAN. Used as a library, not modified |

### 5.2 Vendored source

| Component | Location | License | Notes |
|---|---|---|---|
| libprojectM | `miku-player-kotlin/app/src/main/cpp/vendor/projectm` | LGPL-2.1 | Tracked at upstream master (4.2.0). Built as a library and linked; not modified |
| fastboot.js (`android-fastboot` 1.1.3) | `tools/web-installer/js/vendor/` | MIT, Copyright (c) 2021 Danny Lin | Byte-identical to the npm `dist/fastboot.min.mjs`, not modified. Its own `LICENSE.fastboot.js.txt` and `NOTICE.txt` ship alongside it |
| QM-DSP (subset: DetectionFunction, PhaseVocoder, FFT, MathUtilities, TempoTrackV2) | `miku-player-kotlin/app/src/main/cpp/mikubeat/qm-dsp` | GPL-2.0-or-later, Copyright Centre for Digital Music, Queen Mary University of London, and the named authors (TempoTrackV2: Matthew Davies) | From github.com/c4dm/qm-dsp at e34a3cc. Whole-track tempo in Miku Music, the same tracker Mixxx uses. Files are unmodified and keep their headers. `COPYING` ships alongside |
| KISS FFT 1.3.0 | `miku-player-kotlin/app/src/main/cpp/mikubeat/qm-dsp/ext/kissfft` | BSD-3-Clause, Copyright (c) 2003-2010 Mark Borgerding | The copy bundled with QM-DSP, unmodified. Built in double precision and shared by QM-DSP and BTrack. `COPYING` ships alongside |
| BTrack | `miku-player-kotlin/app/src/main/cpp/mikubeat/btrack` | GPL-3.0-or-later, Copyright (C) 2008-2014 Queen Mary University of London, Adam Stark | From github.com/adamstark/BTrack at 9d61276. Live beat tracking on the player's PCM. Files are unmodified. Its one libsamplerate call is served by our own `shim/samplerate_shim.c` instead of libsamplerate. `LICENSE.txt` ships alongside |

### 5.3 Forked code

`androidx.media3.exoplayer.audio.MikuDirectAudioSink` is a modified copy of Media3's
`DefaultAudioSink` (Apache-2.0, Google), carried in our source tree under the original package name
so it can reach package-private members. The modification is the buffer sizing that keeps the track
out of Android's deep-buffer path. Apache-2.0 terms apply to the original code.

### 5.4 Visualizer presets

The preset pack shipped in the player's assets is drawn from the MilkDrop and projectM preset
community. Individual presets carry their own authorship and terms, which vary and in many cases are
not stated. Presets written for this project are named `Miku - *.milk`. If you authored a preset in
the pack and want it credited differently or removed, open an issue.

### 5.5 Weather data, maps and ported code

The launcher's weather screen (`mikuos-launcher/.../weather/`) pulls from these services at run time.
Nothing from them is bundled. Credits are shown on screen next to the data, as each provider asks.

| Source | What it supplies | License or terms | Credit shown |
|---|---|---|---|
| [Open-Meteo](https://open-meteo.com) | Forecast, 15-minute precipitation, air quality and pollen (CAMS) | Data under CC BY 4.0. Free API for non-commercial use | "Open-Meteo" on the forecast surfaces |
| [RainViewer](https://www.rainviewer.com) | Radar frames for the nowcast and the map | RainViewer API terms: free for personal and non-commercial use with credit | "Radar: RainViewer" on the radar and map |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | Map data under the basemap tiles | ODbL 1.0, © OpenStreetMap contributors | "© OpenStreetMap contributors" on every map |
| [Esri](https://www.esri.com) Dark Gray Canvas | The basemap tiles themselves (base and labels) | Esri terms of use, with the credit Esri asks for | "Esri, HERE, Garmin" on every map |
| [Windy](https://www.windy.com) | Point Forecast, Webcams and the embed map layers. Only with the user's own API key | Windy API terms (api.windy.com). Keys belong to the user and are never shipped | "Windy.com" on Windy layers, "Webcams provided by windy.com" on webcams |
| National Weather Service (api.weather.gov) | Observations and alerts (US) | US government work, public domain | "NWS" in the sources line |

The weather logic (rain nowcast and storm motion from radar frames, the dBZ palette decode, arrival
estimate, frost outlook, pollen, location accuracy guard, Windy webcams and point forecast) was
ported to Kotlin from sworrl's own KDE Plasma widget, Windy Weather 3.2.0
(`~/Documents/GitHub/kde_widg/windy-weather`). That repo has no LICENSE file; its `metadata.json`
declares GPL-2.0-or-later, which is compatible with this project's GPL-3.0-or-later. Same author.

---

## 6. Not redistributed

These are required to build or run MikuOS and are deliberately NOT in either repo. You supply them
from the device you own.

- **HiBy Digital**: the stock firmware, bootloader, vendor partition, audio HAL, and HiBy's own
  applications. The build scripts expect you to provide your own extracted copy.
- **Qualcomm**: proprietary components in the vendor image, under their own licenses.
- **Google**: GMS and related components, under their own licenses.

No prebuilt MikuOS super image is published for download, because a super image would contain the
above.

---

## 7. We are looking for real art

The Miku artwork in this project is either generated by a model or taken off the stock device.
Neither is where it should end up.

If you draw, and you would like your work in an OS that a small number of people use daily on a
music player, this is an open invitation. What is needed:

- **Wallpapers** at 720x1280, the panel's native resolution.
- **A default album-art placeholder**, square.
- **Launcher icon themes**. The current themed icon set is HiBy's and has to go.
- **A lockscreen and always-on-display treatment.**
- **Boot splash art.**

Terms, so there are no surprises: the project is GPL-3.0-or-later and non-commercial, and it is a
derivative work of Crypton's character, so contributed Miku art needs to sit within the Piapro
Character License. You keep your copyright. You are credited here by whatever name you want, and
you can ask for your work to be pulled at any time and it will be.

Open an issue or a pull request.

---

## 8. Reporting a problem with this file

If a license here is wrong, an attribution is missing, or something is in this project that should
not be, open an issue or email github@falcontechnix.com.

### Blackjack meme (art/blackjack/)

`blackjack-meme.png`, `.jxl` and `.webp` are a fan meme made by sworrl from a Futurama frame of Bender,
with the caption changed to be about our audio driver. Futurama and Bender belong to their owners.
It's used here as parody, isn't sold, and isn't an endorsement. It's shown in the README, the press
kit, and the MikuOS Settings About page.
