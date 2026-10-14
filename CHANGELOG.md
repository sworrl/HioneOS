# MikuOS and Miku Music changelog

MikuOS for the HiBy M500 x Hatsune Miku, and Miku Music, its native Kotlin/Compose player
(`com.miku.player`). Newest first. The README's What is verified table is the line between what
has been checked on the device and what has not.

## Pending flash: MikuOS 0.4.0

MikuOS 0.4.0, Miku Music 2.3.0, Miku Update 0.4.0.

The tree as of 2026-10-10, not flashed yet. Nothing here has been checked on the device, so all
of it is Not yet in the README until the flash. Items marked in progress are still being written.

### Mascot packs

- **Miku or Hione, switched live** in Settings, Theme & mascot, or with
  `settings put global miku_mascot_pack hione`. Every MikuOS app swaps colors, names, character
  art and home-screen app names at draw time. Miku Music shows as Hione Hits. The boot video, the
  SystemUI popups and the system app names still need the build-time swap
  (`MIKUOS_MASCOT=hione`).
- The Hione pack ships inside Miku Update, which serves pack files to the other apps. Later packs
  can come through Miku Update as content packs.
- **Seasonal and holiday wallpapers**, from the active pack's art. Holidays can be International,
  Local, Custom or Off, with a reminder on the day (the day before for memorial days). Seasons
  flip in the southern hemisphere. A wallpaper you picked yourself stays. The art so far is
  Hione's: two fall scenes and nine holidays.
- BPM game art for Hione (in progress).

### Audio and the DAC

- **The docs stop calling the audio path bit-perfect.** Android hands Miku Music's track to the HAL
  at the file's own rate with no mixer, and that is still true. Below it, PAL pins the wired
  outputs to 192kHz (`resourcemanager_bengal_idp.xml`) and the ADSP converts every track to 192kHz
  before the DACs. Found on 0.3.1 on 2026-10-10, and it is why all five filters measured the same.
- The Hardware app's DAC page draws each filter's response, says the DAC runs at 192kHz and the
  filters sound the same, and can lay a profile's EQ or a mic sweep of the speaker over it.
- The PAL half of native-rate output is drafted behind `MIKUOS_NATIVE_RATE`, off. It must stay off
  until the DAC's rate register follows the backend rate, which needs a kernel module patch.
- **Tempo** from the whole file with QM-DSP's TempoTrackV2, and live beats from BTrack on the
  player's own PCM. Both vendored unmodified, listed in ATTRIBUTIONS.md.
- The leek moved off the album art and the visualizer to sit next to Play along, and turns on the
  beat. Packs without Miku in them get Hione's coda lock there instead.
- Shell commands so a measurement tool can play a test file and set the DAC over adb. Hione uses
  them.

### FM

- **Seek and scan on the Si4705** (in progress). The old seek ran on the Qualcomm core, which has
  no antenna, so it stopped where it started. The new one steps the Si4705 channel by channel, with
  thresholds from a full band sweep on 0.3.1.
- The preset action is shared with MikuPod and Riot mode, and signal strength polls in the
  background.

### Weather (in progress)

- Rain radar from RainViewer, pollen where Open-Meteo has it (Europe), and a map that is free by
  default (OpenStreetMap data on CARTO tiles). Optional Windy keys add Windy's point forecast and
  nearby webcams. The keys are stored encrypted.

### System

- **Tap to wake.** Double-tap wake is on by default, with a switch in MikuSettings. HiBy ships it
  off.
- **No more screen wake on every charger plug and unplug.** MikuOS's overlay for this lost to
  Qualcomm's on 0.3.1 (priority 1 against 100). It now sits at 400.
- **System updates over the air, groundwork.** The image carries MikuOS's own payload keys in
  `otacerts.zip` (`MIKUOS_AB_OTA=1`), and Miku Update can hand a signed payload to
  `update_engine`. The 0.4.0 image goes on by wire. Installing, booting the new slot and falling
  back are untested.
- **Android Auto** gets its fourteen runtime permissions at first boot, and MikuSettings has a
  setup card that opens its settings.
- System event sounds go through the system bridge, with a Sounds page in Settings.
- Locking from the power menu goes straight to the Miku lockscreen, and the PIN pad can scramble
  its digits.

## 2026-10-10: MikuOS 0.3.1

A rebuild of 0.3.0 on the author's M500, not published on its own. The 3.5mm measurements and the
192kHz finding above were made on it.

## 2026-10-10: DAC settings that actually apply, HiBy's 1.30 fixes, retro modes, FM in the car

MikuOS 0.3.0, Miku Music 2.2.0, Miku FM 1.2.0.

- **DAC settings reach the driver.** Before 0.2.0 nothing MikuOS set ever got to the DAC, because
  HiBy's boot service skips any device not named M500. The system bridge sets the values and a
  vendor init file writes the driver nodes. Confirmed from the driver's kernel log on both DACs.
  One DAC page (the Hardware app), and a status bar badge showing what was applied.
- **From HiBy 1.30:** their cs43198 driver (NOS fixed, both DACs written), the fuel gauge driver,
  the health HAL charger fix, and notification sounds back (1.20 stubbed `playSound()` out).
  Symbol CRCs checked against the 1.20 kernel. With only 1.20, the build patches the NOS mask.
- **FM:** the reach model stopped counting the same ridge twice, measured signal beats prediction,
  hardware buttons, Android Auto source, a new icon, RDS and song ID in the media session.
- **Riot mode and MikuPod** as BPM game unlocks, with haptics and the radio. No modern system UI
  while either is in front.
- Haptics across the OS. HiBy shipped touch vibration off.
- The Miku shade gets notifications and opens from any app.
- Screen lock with a Miku PIN pad, and a trust agent so it asks only after a restart.
- Boot setup moved into the system bridge. The old rc lines never ran under enforcing SELinux.
- CPU power profiles and a charge limit that actually write their nodes.
- adb needs an authorized key even on dev builds. `MIKUOS_RELEASE=1` strips the developer access.
- Android Auto bundled as a privileged system app.
- Album art: a cover shared by many different albums is ignored and looked up by artist and album.
- New apps: Clock, Calculator, Calendar, Camera, Gallery, Recorder, Miku Update, Voice Check.
- New boot video and popup art, voice clips that pass a speech check, a text pass across the apps.

## 2026-10-10: MikuOS 0.2.0

MikuOS 0.2.0, Miku Music 2.1.0, Miku FM 1.1.0.

Built the same day as 0.3.0 and published with it, not on its own. This is where the
DAC settings first went through the system bridge and `miku_audio.rc` instead of paths that never
reached the driver.

## 2026-10-10: MikuOS 0.1.17 and 0.1.18

Miku FM 1.0.13 and 1.0.14. Interim images on the author's M500.

- **FM RDS confirmed.** On 0.1.17 a strong station (WVAQ 101.9) showed its program type and
  RadioText, with the patched Si4705 driver from 0.1.16.

## 2026-10-09: RDS the driver was hiding, and playing to every output at once

MikuOS 0.1.16, Miku Music 2.0.311 to 2.0.314.

- **The Si4705 driver patch.** HiBy's driver copied only two of the four blocks of each RDS group
  to apps, so no app could show a station name or RadioText. It only armed RDS from `poll()`,
  which stock never calls, and it read the stereo pilot and threw it away.
  `os/patch_si4705_rds.py` fixes the copy length and exposes the pilot and blend, hash-checked
  against the stock module.
- **Playing to every output at once.** The old "dual audio matrix" sent vendor parameters that do
  not exist. Miku Music now plays the direct track to the best output and a synced copy to each of
  the others. Per-output volume sliders and timestamp-based sync came in 2.0.313.
- USB DAC routing through the new system bridge (`com.miku.sysbridge`), radio now-playing on the
  HUD and widgets.

## 2026-10-09: FM that plays

MikuOS 0.1.15, Miku FM 1.0.11.

- **The tuner is audible.** The Si4705 starts in a 24-bit mono output format and PAL reads that
  backend as 48kHz 16-bit stereo, so the chip fed misaligned samples into the loopback. That is
  white noise, not quiet audio. Setting the channel mode at power-on, the way stock does, fixed it.
- One writer for the tuner gain, so the HAL no longer gets 1.0 from the media stream at start.
- "TUNER ERROR" after a flash was an adb-installed copy of `com.caf.fmradio` in `/data` shadowing
  the bundled one. `adb uninstall com.caf.fmradio` goes back to the bundled app.

## 2026-10-09: MikuOS 0.1.14

- First boot grants the hardware app fine, coarse and background location. Without background, a
  background process gets an empty Wi-Fi scan list rather than an error, and the fused position
  published nothing for a whole image.
- The hardware app builds with the other modules instead of being injected from whatever APK was
  on disk.
- The FM tuner gain is anchored to the one value measured audible.

## 2026-10-09: MikuOS 0.1.3 to 0.1.13

Test images flashed during the FM work that day, one after another. They are not described one by
one here. The commit log has them.

## 2026-10-09 — the FM tuner makes sound, and gets a control surface

MikuOS 0.1.2, Miku FM 1.0.2.

Two corrections from later that day, left here rather than rewriting the entry. The tuner that
receives is the Si4705, not the Qualcomm WCN core (the "Also" list below has it backwards). And
the station did not come through cleanly until the chip's output format was fixed in 0.1.15,
which is the version the README counts as the first one where FM is audible.

### The tuner is audible for the first time

It had been tuning since 0.1.1 and producing silence. Three separate causes, all in the audio
path rather than the tuner:

- **`handle_fm=1` was telling the HAL to stop FM.** That parameter carries an output-device
  bitmask, and the HAL starts its FM session only when `AUDIO_DEVICE_OUT_FM` (`0x100000`) is
  set in it. The correct value is the HiBy device code OR'd with that bit: `0x100003` for the
  4.4 mm balanced output, `0x100004` for the 3.5 mm jack, `0x100008` for Bluetooth, `0x100002`
  for the speaker. The balanced output is only distinguishable from the 3.5 mm one by its
  `productName`, which is the literal string `balance`.
- **`FmReceiver.EnableSlimbus(1)` was never called.** SLIMbus is what carries the FM core's
  audio to the codec.
- **The driver's own mute was never cleared.** It lives in HiBy's V4L2 layer, and
  `FmReceiver.setMuteMode()` does not touch it; `FmReceiverJNI.setV4L2RadioFmMute(0)` does.
  It is now cleared 300 ms after the route is built, which is also what removes the power-on pop.
- **And tuning was going to the wrong layer.** `FmReceiver.setStation()` returns true and fires
  a tune callback, and after it the V4L2 read-back still showed the bottom of the band. Stock
  tunes with `FmReceiverJNI.setV4L2RadioFrequency(kHz * 16)` and synthesises the callback
  itself. Both calls are issued now, and the V4L2 read-back is reported in diagnostics so the
  two can be compared rather than assumed equal.

`fm_status` turns out to be a *get* key, not a set key. The old code wrote it, which did
nothing. Read back, it is the only root-free confirmation that the FM session really started,
and it is now what the diagnostics panel reports. `fm_active`, `fm_route` and
`vendor.audio.hw.fm.mode` were invented keys and are gone.

The `AudioRecord(RADIO_TUNER)` to `AudioTrack` bridge is now Bluetooth-only, which is what the
hardware wants: on every other output the ADSP loops FM straight to the DAC, and a second
software copy would have been an echo one capture buffer behind. The capture still runs for the
live spectrum and the recorder.

### Controls that were not there

- **Power, mute and stereo** as real buttons. The tuner no longer switches itself on when you
  open the app, because doing so takes audio focus and would stop whatever you were listening to.
- **Background playback.** A foreground service owns the tuner now, with seek, mute and off in
  the notification. Leaving the screen used to power the radio down.
- **Volume**, following `STREAM_MUSIC` and pushed to the HAL as the real linear gain the volume
  curve assigns to that index on that output.
- **Band scan** over the whole band, built from repeated hardware seeks because the vendor jar
  gives no way to read a station list back out. Results list with the signal measured at each
  stop, tap to tune, star to keep.
- **Direct frequency entry** on a keypad.
- **Region**: US/Canada, Europe, Japan and Japan wide, each setting band limits, channel
  spacing, de-emphasis and RDS standard. Changing it power-cycles the tuner, because the chip
  only reads those at enable time.
- **Seek sensitivity**, **soft mute** and **RDS alternative-frequency following** as switches.
- **Presets** are a row you can tap to tune and long-press to remove, and they appear as ticks
  on the band strip alongside scan hits.
- **RDS** now shows the programme type as well as the station name, from the correct table for
  the region (RBDS and RDS assign the same codes to different genres).

### Readouts that are measurements

The signal strip is RSSI, SNR, multipath and the live PCM level, read from
`FmReceiverJNI.getV4L2RadioFmSignal()` rather than the HCI round trip the old 2-second poll was
making. A diagnostics sheet reports the JNI load state, SoC name, chip state, antenna, the
audio route and the exact `handle_fm` value written, the HAL's own `fm_status`, the SLIMbus
acknowledgement, both mutes, and the RDS PI and PTY. Every one of those is a read; a dash means
the question was asked and nothing came back, not that the value is zero.

The antenna chip says `INTERNAL ANTENNA` only when `getInternalAntenna()` says so, and otherwise
reflects whether a cable is actually plugged in, because on this board the cable is the antenna.

### Also

- The tuner is documented correctly at last: it is the Qualcomm WCN SoC FM core over the shared
  Bluetooth HCI transport (`getSocName()` = `cherokee`), not the Si4705 the comments claimed.
- Recording moved out of `AudioTrackHelper` into its own WAV writer, so it works on the wired
  outputs where no AudioTrack exists.
- The FM module's unused `RootShell` is gone.

## A gap you should know about

Every entry below is from 2026-08-15 and stops at 0.9.17. The player was on
2.0.309 when this note was written (2026-10-09). The file was written in one
sitting and then never kept up, while roughly a hundred releases happened
without it.

Those hundred are not reconstructed here. Writing them now would mean inventing
dates and attributing changes from memory, and a changelog that cannot be
trusted is worse than one that admits where it ends. The commit log is the real
history and it is written to be read:

    git log --oneline
    git log --stat <path>

What landed in the gap, in outline, so the shape is not a mystery: DIRECT
output at the file's own rate with no Android mixer (the driver below still runs
the DACs at 192kHz, see the 0.4.0 entry); a Last.fm scrobbler with an offline
queue; listening statistics and the Miku Rewind recap; the Miku Shaders GLES2
visualizer engine alongside native libprojectM with 9,825 presets; a BLE remote
peripheral; CUE-sheet splitting; artist photography from Wikidata; an alarm
clock; an artwork and booklet viewer; a taste model with a radio station mode;
a TV cast companion that sends the decoded PCM byte for byte; and four
successive sweeps that removed telemetry the UI was presenting as measured when
nothing had measured it.

The entries below are kept as written.

## 0.9.17 — 2026-08-15 — tape materials + OS fn-lock

### Tape mode — de-slopped, themed
- Rebuilt as a real cassette: gold **metal shell** with fine engraved ribbing + directional gradient,
  a **recessed beveled window** showing the album art with **projectM behind it** (now more visible),
  and reels with genuine depth — drop shadow, metallic radial-gradient hub, glossy tape pack, and the
  correct Compact-Cassette spindle (18-hole flange ring + 6-tooth splined drive + centre hole).
- Correct tape transfer (top supply empties, bottom take-up fills, chronological to the track).
- Tap cycles themes: GOLD / MEMOREX / CHROME. Corner screw bosses + head mechanism.

### Pocket lock — OS-level (fn = Both)
- Discovered the M500's fn-lock is driven by the global setting `fn_settings` (modes: `key_lock`,
  `touch_lock`, `Both`, `none`) + `fn_status`. Set it to **`Both`** so the fn button locks the
  touchscreen AND the physical buttons together (system-wide). Removed the app-level touch-lock
  overlay (a press-and-hold is useless when a pocket holds it by itself). Power-button lock still
  needs a framework/keylayout patch (recorded).

## 0.9.14 — 2026-08-15 — visualizer overhaul, tape rebuild, taste groundwork

### Visualizer
- **130 real, descriptively-named presets** (from the named projectM library) replace the generic
  `preset_NNN` pack — so the on-screen name is the actual preset — plus **10 original Miku-themed
  `.milk` presets** (teal/pink, twin-tail tunnels, 39-pulse, spectrum, etc.).
- **Runs every preset on the slow GPU** (no presets dropped for weight): mesh lowered to 24×18 and
  the render buffer capped at 800px (upscaled), holding ~60fps.
- **Fullscreen was silent → fixed**: a single shared `AudioCapture` (one Visualizer for the session)
  instead of two instances fighting over the audio session.
- **No more preset-jump entering fullscreen**: the enum "setPreset" no longer advances the playlist,
  and the playlist position is saved/restored across the per-surface projectM re-create.
- **Fullscreen controls reworked**: the viz stays full-bleed; tap reveals a compact translucent glass
  card (title/artist/preset, embossed scrubber, transport + preset-cycle) with a **pin** to keep it up.
- Live preset name reads fresh (mutex-guarded playlist query).

### Now Playing
- Header reads **MIKU MUSIC PLAYER**; album art no longer clipped (Fit); **blurred album-art +
  Miku background**; the old "Location & Format" card is now an **artistic art + data panel**
  (art, album/year, format/bitrate/quality-tier chips).
- **Embossed, color-shifting scrubber** — recessed groove, raised glossy fill that drifts teal→pink
  with progress and dims when paused, travelling shimmer, raised knob. Feels like a physical control.

### Tape mode — rebuilt to match a real deck
- Vertical cassette filling the screen (the "sideways" tape): **album art as the tinted background**,
  two stacked reels with metallic 6-hole hubs that turn while playing (top empties, bottom fills),
  centre teeth + tape strands, vertical serif title clear of the reels, teal artist·album, MIKU mark,
  times by each reel, corner screws.

### Likes / library / QoL
- **Artist likes** (third tier alongside song + album likes), rainbow heart in artist rows + detail.
- **Albums list chronologically** (by year); tracks stay in track-number order.
- **Animations work with the device's animations disabled** (`animator_duration_scale = 0`): rainbow
  hearts, equalizer bars, and scrubber shimmer are now driven by manual time loops — hearts were
  stuck red before; verified shifting teal→magenta on-device.
- **Playing-track indicator**: the currently-playing row shows an animated equalizer over its art +
  a teal title.
- **Exact resume after an app update**: restores the real last track + position and only auto-resumes
  if it was playing (a full reinstall still kills the process — audio can't literally survive it).
- **Portrait-locked** (no rotation; also ends the rotation queue-clobber).
- Fixed a launch crash: the MediaSessionService is now started from a foreground context, not onCreate.

## 0.9.7 — 2026-08-15

### Now-playing "stops updating / stuck on an old track"
- Now-playing now re-syncs to the player's **actual** current item (`currentMediaItem`) on any
  item/timeline change, instead of trusting the transition event's passed item (which could go
  stale and leave the bar frozen on a previously-played track).
- Position poll loops (mini-bar + full screen) are wrapped so a transient player error can't kill
  the `while` loop and freeze the UI.
- Removed another dead per-composition `loadLikedTracks` disk read in the mini-bar.

## 0.9.6 — 2026-08-15 — UI/QoL round + real tape mode

### Performance (interface felt laggy)
- **`TrackRow` read SharedPreferences from disk on every row composition** (a dead, unused `liked`
  var) — removed. This was the main scroll jank.
- Metric `AnnotatedString`s are now built once per track (`remember`), not every recomposition.
- `tracks.artists()` / `tracks.albums()` were regrouping the whole library on every recomposition;
  now memoized per library change. Lazy lists all carry stable keys.

### Play history + track metrics (were nowhere to be found)
- New play history + per-track play count + last-played timestamp (`PlayerPreferences`), recorded
  on every track that starts.
- Home gets a **Recently played** shelf (horizontal art cards).
- **Long-press any track → a metrics sheet**: art, title/artist/album/year, format, bitrate,
  duration, size, play count, last-played (relative), and full file path.

### Tape mode — rebuilt
- Replaced the placeholder cassette with a realistic Maxell/Sony-style deck: smoke shell with
  gloss, cream label + teal/pink header stripes + "A" badge, a real tape window with two wound
  reels (concentric tape texture) that fill left→right with progress and spin while playing,
  the exposed tape strand, bottom head-access + capstan cutouts, and 5 slotted screws.

## 0.9.4 — 2026-08-15 — code-vet pass

Full multi-agent review of the codebase; fixed the real bugs found (crashes/leaks/lifecycle/correctness).

### Crashes / threading (visualizer)
- **Off-thread projectM call** — `AndroidView`'s factory/update lambdas run on the UI thread and
  reached `setPreset → projectm_playlist_play_next` (projectM is GL-thread-only) on every
  recomposition → latent SIGSEGV. `setPreset` now hops to the GL thread via `queueEvent` and
  skips no-op repeats; the switch is covered by the same skip-render guard as gestures.
- **Cross-thread use-after-free** — during a fullscreen transition two GL threads can briefly
  coexist; the old thread's render could race the new thread's destroy+recreate of the global
  projectM handle. Added a native `std::mutex` serializing all handle access.

### Leaks
- `MediaMetadataRetriever` was released only on success → native-context leak on every art-less
  track; now released in `finally`.
- `Haptics` cached a `Vibrator` from a possibly-Activity Context (process-lifetime leak); now
  uses `applicationContext`.
- `rescan` Toast/Handler ran through the Activity context; now app-context.

### Lifecycle / correctness
- **Queue clobber on Activity recreation** — restore-on-launch keyed only on `currentTrack == null`
  (fresh Compose state), so a rotation/config-change rebuilt the queue with the whole library and
  seeked backwards. Now it rebuilds only when the player is empty; otherwise it reflects the
  current item. `isPlaying`/`currentTrack` are seeded from ongoing playback.
- MediaStore query moved off the main thread (`Dispatchers.IO`); `rescan`'s pre-count too.
- **Scan "found N new" under-reporting** — replaced the 3.5s guessed delay with a real
  MediaScanner completion callback (report once when the last path finishes; 20s fallback).
- Play/pause glyph now updates instantly via a `Player.Listener` instead of the 300ms poll;
  removed a dead duplicate `liked` state in the now-playing screen.
- `PlayerHolder.ensure/ensureSession/release` are now `@Synchronized`.

### Library grouping
- Artists group case-insensitively ("Gwar" == "GWAR"), displaying the most common casing.
- Albums group by `albumId` (distinct albums sharing a title stay separate) instead of by name.

### Reviewed & deferred (not bugs / by-design)
- Collab-splitter still trims "Earth, Wind & Fire" → "Earth" (known feat-stripping tradeoff).
- Album art cache keyed per-track not per-album (works; duplicates art across an album's tracks).

## 0.9.3 — 2026-08-15

### Fix
- **Play/pause "looping" on the physical keys** — once we added the MediaSession, it became the
  system media-button owner AND `onKeyDown` was also toggling, so each hardware press acted twice
  (play→pause→play). `onKeyDown` now only fires the haptic and passes the event through; the
  session performs the single transport action. Verified: one press = one state change.

## 0.9.2 — 2026-08-15

### Lockscreen / notification media control
- Added a Media3 `MediaSession` + `PlaybackService` (foreground `mediaPlayback`). Our playback
  now owns the lockscreen + notification media control — title, artist, and album art come from
  each track's metadata — instead of leaving the lockscreen to Spotify. Verified: our session
  becomes the system "media button session" and posts the foreground media notification.
- MediaItems now carry full `MediaMetadata` (title/artist/album + album-art URI).
- Player + session live in a process-wide `PlayerHolder` so playback and the lockscreen control
  survive the Activity; released on task-removal when idle.
- Physical media keys now route to us system-wide (via the session), not just while foregrounded.

### Known / next
- Cross-app interception (re-skinning *other* apps' media, e.g. Spotify, in Miku style) needs a
  `NotificationListenerService` + manual notification-access grant — separate follow-up.

## 0.9.1 — 2026-08-15

### Fixes
- **Double-tap visualizer crash fixed** — after a preset switch projectM briefly has no
  active preset; rendering that frame null-deref'd (SIGSEGV). Now skips ~5 render frames
  after a switch so the new preset loads first. Survives sustained double-tap spam.
- **Play/pause icon** now reflects real state (polls `player.isPlaying`); was stuck on ▶.

### Feedback & controls
- Every button is now a `HapticIconButton`: vibrator tick + springy scale-down on press.
- Physical transport keys (M500 side buttons / headset: next/prev/play/pause/play-pause)
  handled in `onKeyDown`, each with a haptic tick.

### Branding
- Title bar: teal gradient, Miku launcher icon as the app glyph, faded Miku watermark
  burned into the trailing edge.

### Known / next
- App-side MediaSession so lockscreen/widget show *our* now-playing (lockscreen still shows
  Spotify's session) and physical keys work while backgrounded.
- Hi-res video player that can push the amps/DAC (low priority).

## 0.9.0 — 2026-08-15

First tracked version. Everything below is on-device and working.

### Playback & library
- Media3/ExoPlayer playback; resume last track/position on launch.
- MediaStore library with manual rescan (MediaScanner across all volumes, "found N new" toast).
- Tab nav: Home / Songs / Artists / Albums / Library, with Artist → Album → Track drill-down.
- Album tiles show year + album-level format/bitrate; artist-name normalization (feat./collab stripping).
- Working scrub bar (position poll + drag-to-seek); shuffle & repeat wired to ExoPlayer.

### Visualizer (real libprojectM 4.2.0)
- Real libprojectM cross-compiled under the NDK (arm64), audio-reactive via the Android Visualizer API.
- 120 bundled Milkdrop `.milk` presets, auto-cycling.
- Fullscreen: tap = show/hide controls, double-tap = next preset; real preset-name toast.
- Crash-hardened: C++ exception guards at the JNI boundary, `-fexceptions`, per-GL-context
  projectM re-create (fixed the fullscreen crash), thread-safe action queue (fixed touch crash),
  removed the lock gesture that could freeze the app.

### Likes, UI
- Shared `LikeStore` so song hearts sync across rows, mini-bar, and now-playing.
- Album likes (distinct from song likes).
- Dynamic per-tier metric colors; live animated track count.
- Redesigned floating mini-bar (progress + like-heart + transport).
- Tape mode (cassette view, spinning reels) — placeholder; to be replaced with a HiBy-Tape-derived deck.

### Known / next
- projectM 60fps pass across all presets.
- Scrub bar + app made more dynamic (color/shape/size/interaction).
- Tape mode rebuilt from HiBy Tape source.
- Queue menus, online art/lyrics enrichment, achievements, DAC profiles, MediaSession for widget/lockscreen.
