package com.hione.launcher.bpm

import com.hione.launcher.Mascot
import android.content.Context
import android.content.SharedPreferences
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Calendar

/**
 * The hidden tier: OS easter eggs earned in the BPM game.
 *
 * WHY A SECOND TIER. The visible ladder in [HioneUnlocks] is a promise ("200 judged taps → Honey
 * Sweet"), and a promise is a fine reason to keep playing. It is not a reason to be delighted.
 * These are the other kind of reward: things that change the OS in ways a player would want and
 * would never find, earned by doing something odd. The game never lists them. It shows how many
 * remain and one cryptic hint a day, and the first time anyone learns what a secret IS, is the
 * moment they earn it.
 *
 * SAME CONTRACT AS THE VISIBLE TIER. Secrets are ordinary entries in Settings.Global
 * `hione_unlocks` (written only by [HioneUnlocks.unlock]); the consumer in whichever app reads the
 * id and changes behaviour. Ids are namespaced `secret.*` and, like every unlock id, are never
 * renamed. A secret that draws something the player might not always want is toggleable from the
 * Secrets list ({"off": true} in its entry, see [HioneUnlocks.setEnabled]) and consumers check
 * [HioneUnlocks.isEnabled].
 *
 * SAME RULE AS THE VISIBLE TIER: only judged taps pay. Every condition below is fed from the
 * judged-tap branch of the game (a tap measured against a real detected beat) or from an event
 * that only a judged tap can cause. The two exceptions are honest about it: golden pickups
 * (a timed bonus target and lucky notes, which are only lucky when hit GREAT or better against a
 * real beat) and the season rank, which itself only moves on judged taps.
 */
object HioneSecrets {

    // ---- ids (never rename) -------------------------------------------------------------------

    /** Launcher top-bar battery badge + SystemUI shade battery chip draw a leek (Hione) or a coda mark (other packs). */
    const val NEGI_BATTERY = "secret.os.battery.negi"
    /** Hione Music Tape Mode: a cassette shell that is invisible until earned. */
    const val TAPE_HIONE39 = "secret.player.tape.hione39"
    /** Now Playing, next to Play along: a leek (Hione pack) or the coda lock (other packs), on the beat. */
    const val LEEK_SPIN = "secret.player.leek_spin"
    /** Lockscreen clock becomes the holographic plasma face. */
    const val HOLO_LOCK_CLOCK = "secret.os.lock.holo_clock"
    /** Home screen: double-tap the hearts clock for penlights and stage beams on the beat. */
    const val CONCERT_MODE = "secret.os.home.concert_mode"
    /** Home screen: leeks (Hione) or ice shards and coda marks (other packs) drift down the wallpaper. */
    const val NEGI_SNOW = "secret.os.home.negi_snow"
    /** A synthesized Mi-Ku chime greets every boot (same boot-sound switch as the stock jingle). */
    const val BOOT_CHIME = "secret.os.boot.chime39"
    /** Hione Music's icon on the home screen and in the drawer turns gold. */
    const val GOLDEN_ICON = "secret.os.icon.golden_music"
    /** A sixth game skin that is not in the skin list until earned. */
    const val HOLO_SKIN = "secret.game.skin.hologram"
    /** A top-bar theme that changes with the month. */
    const val SEASONAL_TOPBAR = "secret.os.topbar.seasonal"
    /** Riot mode: the whole player becomes a 2002 Rio Riot (com.hione.riot). */
    const val RIOT_MODE = "secret.os.riot"
    /** HionePod (com.hione.wheel): one click-wheel era skin each. Ids match com.hione.wheel Eras.kt. */
    const val WHEEL_2001 = "secret.os.wheel.2001"
    const val WHEEL_2004 = "secret.os.wheel.2004"
    const val WHEEL_2005 = "secret.os.wheel.2005"
    const val WHEEL_2007 = "secret.os.wheel.2007"

    // ---- Expanded Secrets (50 New Additions -> 65 Total) -------------------------------------
    const val HOLOGRAM_ART = "secret.player.hologram_art"
    const val CRT_SCANLINES = "secret.player.crt_scanlines"
    const val REEL_SPIN_OVERCLOCK = "secret.player.tape_reel_spin_speed"
    const val RAINBOW_WAVE = "secret.player.rainbow_wave"
    const val PLASMA_TUNNEL_FX = "secret.player.plasma_tunnel_fx"
    const val SECRET_LYRICS_GLOW = "secret.player.secret_lyrics_glow"
    const val BASS_SHAKE = "secret.player.bass_shake"
    const val GOLDEN_VOLUME_HUD = "secret.player.golden_volume_hud"
    const val LASER_SCRUBBER = "secret.player.laser_scrubber"
    const val NEON_EQUALIZER = "secret.player.neon_equalizer"
    const val VINYL_SCRATCH = "secret.player.vinyl_scratch"
    const val HI_RES_BADGE_GOLD = "secret.player.hi_res_badge_gold"
    const val LOFI_RAIN_AMBIENT = "secret.player.lofi_rain_ambient"
    const val TUBE_AMP_WARMTH = "secret.player.tube_amp_warmth"
    const val STARLIGHT_BACKDROP = "secret.player.starlight_backdrop"

    const val MATRIX_RAIN_LOCK = "secret.os.lock.matrix_rain"
    const val GLITCH_CLOCK = "secret.os.lock.glitch_clock"
    const val BINAURAL_TICKER = "secret.os.lock.binaural_ticker"
    const val PETALS_DRIFT_HOME = "secret.os.home.petals_drift"
    const val GRID_FLOOR_HOME = "secret.os.home.grid_floor"
    const val STARFIELD_HOME = "secret.os.home.starfield"
    const val DOCK_REFLECTION = "secret.os.home.dock_reflection"
    const val APP_ORBIT = "secret.os.home.app_orbit"
    const val LEEK_CURSOR = "secret.os.home.leek_cursor"
    const val WEATHER_AURORA = "secret.os.home.weather_aurora"
    const val HUD_COMPASS = "secret.os.home.hud_compass"
    const val MASCOT_PEEK = "secret.os.home.mascot_peek"
    const val CYBER_GLITCH_ICON = "secret.os.home.cyber_glitch_icon"

    const val ARCADE_CLICK_SFX = "secret.os.sound.arcade_click"
    const val SYNTH_LOCK_SFX = "secret.os.sound.synth_lock"
    const val LEVEL_UP_RING_SFX = "secret.os.sound.level_up_ring"
    const val RETRO_BOOT_SFX = "secret.os.sound.retro_boot"
    const val CODA_WHISPER_SFX = "secret.os.sound.coda_whisper"

    const val WHEEL_STEALTH_BLACK = "secret.os.wheel.stealth_black"
    const val WHEEL_MINI_COLORS = "secret.os.wheel.mini_colors"
    const val WHEEL_SHUFFLER = "secret.os.wheel.shuffler"
    const val WHEEL_TOUCH_CLICK = "secret.os.wheel.touch_click"
    const val WHEEL_COVERFLOW_3D = "secret.os.wheel.coverflow_3d"

    const val GAME_GOLDEN_HIGHWAY = "secret.game.golden_highway"
    const val GAME_RAINBOW_NOTES = "secret.game.rainbow_notes"
    const val GAME_FEVER_HYPERSPACE = "secret.game.fever_hyperspace"
    const val GAME_COMBO_GOD = "secret.game.combo_god"
    const val GAME_LASER_TARGET = "secret.game.laser_target"
    const val GAME_RETRO_8BIT_SFX = "secret.game.retro_8bit_sfx"
    const val GAME_SUPER_CRITICAL = "secret.game.super_critical"
    const val GAME_STAGE_PYRO = "secret.game.stage_pyro"
    const val GAME_NIGHTCORE_SPEED = "secret.game.nightcore_speed"
    const val GAME_MIRROR_WORLD = "secret.game.mirror_world"
    const val GAME_STEALTH_NOTES = "secret.game.stealth_notes"
    const val GAME_GOD_TIER_BADGE = "secret.game.god_tier_badge"

    // ---- DankPods Easter Eggs (Australia's Finest) ----
    const val DANKPODS_ONE_GRIT = "secret.dankpods.one_grit"
    const val DANKPODS_PKCELL = "secret.dankpods.pkcell"
    const val DANKPODS_NANO = "secret.dankpods.na_no"
    const val DANKPODS_CRAIG = "secret.dankpods.craig"
    const val DANKPODS_FRANK = "secret.dankpods.frank"
    const val DANKPODS_DIRTY_BUDS = "secret.dankpods.dirty_buds"
    const val DANKPODS_MARACA = "secret.dankpods.baby_maraca"
    const val DANKPODS_DIABEETUS = "secret.dankpods.diabeetus"

    // ---- Global Shrek Swamp Theme for Everything ----
    const val THEME_SHREK_SWAMP = "secret.theme.shrek_swamp"
    const val WHEEL_SHREK_GREEN = "secret.wheel.shrek_green"

    data class Secret(
        val id: String,
        val title: String,
        /** Where it takes effect, shown only once earned. */
        val where: String,
        /** What it does and how to use it, shown only once earned. */
        val howTo: String,
        /** The cryptic line shown while it is still hidden. Never names the reward. */
        val hint: String,
        /** True when the Secrets list offers an on/off switch for it. */
        val toggleable: Boolean
    )

    /**
     * 65 Secret Easter Eggs hidden throughout HioneOS and Hione Hits.
     * Conditions vary across streaks, timing windows, tempos, wall clocks, and secret rituals.
     */
    val ALL: List<Secret> get() = listOf(
        Secret(NEGI_BATTERY, Mascot.lore("Leek battery icon", "Coda battery icon"), "Top bar and notification shade",
            Mascot.lore("Your battery icon is a leek now. The more green, the more charge.",
                "Your battery icon is a coda mark and an ice shard now. The more ice, the more charge."),
            Mascot.lore("Some leeks only grow after midnight.", "Some ice only forms after midnight."), true),
        Secret(TAPE_HIONE39, "HIONE 39 cassette shell", "${Mascot.musicAppName}, Tape Mode",
            "A new cassette shell. Pick it in Tape Mode's theme list or keep tapping the shell button.",
            "Mi-ku. Thirty-nine clean notes in a row, no stumbles.", false),
        Secret(LEEK_SPIN, Mascot.lore("Spinning leek on Now Playing", "Coda lock on Now Playing"),
            "${Mascot.musicAppName}, Now Playing",
            Mascot.lore("A leek spins next to Play along on Now Playing, one turn every two beats.",
                "A coda mark next to Play along on Now Playing locks onto every beat."),
            "Catch the fever three times before you leave the stage.", true),
        Secret(HOLO_LOCK_CLOCK, "Hologram lock screen clock", "Lock screen",
            "The lock screen clock switches to the big hologram style.",
            "Once an hour, for one minute, the clock is listening. Be perfect then.", true),
        Secret(CONCERT_MODE, "Concert lights on the home screen", "Home screen",
            "Double-tap the clock on the home screen while music plays. Glow sticks and stage lights move to the beat.",
            "Fifty notes without a miss, on something fast.", true),
        Secret(NEGI_SNOW, Mascot.lore("Falling leeks on the wallpaper", "Falling ice on the wallpaper"), "Home screen wallpaper",
            Mascot.lore("Leeks drift down your wallpaper, and fall faster while music plays.",
                "Ice shards and coda marks drift down your wallpaper, and fall faster while music plays."),
            "Seven different voices. Sing along with each of them.", true),
        Secret(BOOT_CHIME, "${Mascot.name} startup chime", "Plays after a restart",
            "${Mascot.name}'s own chime plays when the device starts. It follows the boot sound switch.",
            "Her name hides in a tempo too: one, three, nine.", true),
        Secret(GOLDEN_ICON, "Gold ${Mascot.musicAppName} icon", "Home screen and app drawer",
            "${Mascot.musicAppName}'s icon is gold now. You earned it.",
            "A grade so high it turns things to gold. Hold it for a hundred notes.", true),
        Secret(HOLO_SKIN, "Hologram game skin", "BPM game, Skins",
            "A new skin: heart notes, scanline lane, glitchy hits.",
            "Gold falls from the sky sometimes. Catch ten pieces.", false),
        Secret(SEASONAL_TOPBAR, "Seasonal top bar", "Top bar themes",
            "A top bar that changes colors every month. Long-press the top bar to cycle to it.",
            "Climb to gold before the month turns.", false),
        Secret(RIOT_MODE, "Riot mode", "Your whole player",
            "Your M500 can turn into a 2002 Rio Riot. Tap OPEN on this card. To leave, go to Preferences, then Exit Riot Mode.",
            "Twenty oh two on the clock. Twenty clean notes.", false),
        Secret(WHEEL_2001, "${Mascot.name}Pod 2001", "${Mascot.name}Pod app, era picker",
            "A 160 x 128 mono screen and a wheel. Tap OPEN, or open ${Mascot.name}Pod and pick 2001.",
            "Play along to something from the year the wheel first turned.", false),
        Secret(WHEEL_2004, "${Mascot.name}Pod 2004", "${Mascot.name}Pod app, era picker",
            "Color, album art, and a gray mode for purists. Tap OPEN, or open ${Mascot.name}Pod and pick 2004.",
            "A whole album from 2004, beat by beat.", false),
        Secret(WHEEL_2005, "${Mascot.name}Pod 2005", "${Mascot.name}Pod app, era picker",
            "The big glossy 320 x 240 screen. Tap OPEN, or open ${Mascot.name}Pod and pick 2005.",
            "Five thousand taps. Keep your rhythm.", false),
        Secret(WHEEL_2007, "${Mascot.name}Pod 2007", "${Mascot.name}Pod app, era picker",
            "Split-screen menus and Cover Flow. Tap OPEN, or open ${Mascot.name}Pod and pick 2007.",
            "Flip through seven years of music in one sitting.", false),

        // 50 New Secret Easter Eggs
        Secret(HOLOGRAM_ART, "Holographic Vinyl Spin", "${Mascot.musicAppName}, Now Playing",
            "Album artwork tilts with a realistic 3D holographic sheen and vinyl grooves.",
            "Tilt and hold forty clean hits with zero drift.", true),
        Secret(CRT_SCANLINES, "Arcade CRT Scanlines", "${Mascot.musicAppName}, Now Playing",
            "Warm phosphor CRT scanlines and subtle barrel curvature over the player.",
            "A vintage cadence: land twenty hits between 120 and 128 BPM.", true),
        Secret(REEL_SPIN_OVERCLOCK, "Overclocked Cassette Reels", "${Mascot.musicAppName}, Tape Mode",
            "Tape spools rotate at double speed on upbeat tracks.",
            "Keep pace above 175 BPM for thirty continuous beats.", true),
        Secret(RAINBOW_WAVE, "Prismatic Wavy Bar", "${Mascot.musicAppName}, Now Playing",
            "The wavy now playing bar radiates prismatic rainbow hues.",
            "Trigger a 77x fever while a high-bpm track soars.", true),
        Secret(PLASMA_TUNNEL_FX, "Plasma Stream Burst", "${Mascot.musicAppName}, Cast Stream",
            "A fiery plasma vortex pulses during TV stream transmission.",
            "Stream ten tracks over Wi-Fi without missing a pulse.", true),
        Secret(SECRET_LYRICS_GLOW, "Cyber Neon Lyrics", "${Mascot.musicAppName}, Lyrics View",
            "Active lyric syllables illuminate with an intense neon outline.",
            "Sing along for three complete vocal tracks.", true),
        Secret(BASS_SHAKE, "Sub-Bass Haptic Kick", "${Mascot.musicAppName}, Audio Engine",
            "Sub-bass drops trigger deep haptic feedback pulses.",
            "Hit twenty bass-heavy downbeats on the exact millisecond.", true),
        Secret(GOLDEN_VOLUME_HUD, "24K Gold Volume HUD", "System HUD",
            "Hardware volume knob indicator turns polished 24K gold.",
            "Turn the volume dial while holding a fifty combo.", true),
        Secret(LASER_SCRUBBER, "Laser Beam Scrubber", "${Mascot.musicAppName}, Transport",
            "Progress scrubber turns into a focused cyan laser beam.",
            "Scrub through three albums from start to finish.", true),
        Secret(NEON_EQUALIZER, "Floating 3D Equalizer", "${Mascot.musicAppName}, Stage",
            "Holographic 3D frequency bars hover behind the lyrics stage.",
            "Hit eighty PERFECT notes across an equalizer sweep.", true),
        Secret(VINYL_SCRATCH, "Authentic Vinyl Scratch", "${Mascot.musicAppName}, Gestures",
            "Rapidly dragging the album art plays authentic turntable vinyl scratches.",
            "Spin the platter forward and back ten times.", true),
        Secret(HI_RES_BADGE_GOLD, "Audiophile Gold Master Badge", "${Mascot.musicAppName}, Header",
            "Bit-perfect playback badge gleams with an embossed gold seal.",
            "Play pure DSD or 192kHz master audio for an hour straight.", true),
        Secret(LOFI_RAIN_AMBIENT, "Cozy Lofi Rain Generator", "${Mascot.musicAppName}, Soundscape",
            "Blends a cozy gentle rainfall texture behind your music.",
            "Listen to five calm tracks between 2 AM and 5 AM.", true),
        Secret(TUBE_AMP_WARMTH, "Analog Tube Amp Glow", "${Mascot.musicAppName}, Themes",
            "Surfaces take on the warm orange glow of vacuum tubes.",
            "Calibrate your audio latency with precision under 5ms.", true),
        Secret(STARLIGHT_BACKDROP, "Starlight Aurora Field", "${Mascot.musicAppName}, Background",
            "Micro-stars shimmer and twinkle around the album artwork.",
            "Score an SSS+ grade on a song lasting over five minutes.", true),

        Secret(MATRIX_RAIN_LOCK, "Digital Rain Lockscreen", "Lock screen",
            "Unlocking the screen triggers a cascade of green Matrix code rain.",
            "Unlock the screen at exactly 03:33 in the morning.", true),
        Secret(GLITCH_CLOCK, "Cyber Glitch Lock Clock", "Lock screen",
            "Digits on the lockscreen clock occasionally glitch into cyber runes.",
            "Lock and unlock the screen four times in rhythm.", true),
        Secret(BINAURAL_TICKER, "Binaural Clock Pulse", "Lock screen",
            "The lockscreen breathes with a spatial stereo pulse on each second.",
            "Connect wired headphones and let the lockscreen rest.", true),
        Secret(PETALS_DRIFT_HOME, "Drifting Sakura Petals", "Home screen",
            "Soft sakura petals gently fall across your home app grid.",
            "Play twenty sakura blossom songs in spring.", true),
        Secret(GRID_FLOOR_HOME, "Outrun Synth Grid Floor", "Home screen",
            "An 80s synthwave perspective wireframe grid sweeps under dock icons.",
            "Swipe between launcher pages eight times on the beat.", true),
        Secret(STARFIELD_HOME, "Parallax Starfield", "Home screen",
            "A deep 3D starfield moves in parallax as you tilt the M500.",
            "Wander through space: tap fifty notes while moving.", true),
        Secret(DOCK_REFLECTION, "Liquid Dock Reflection", "Home screen",
            "Dock icons cast realistic fluid reflections onto the bottom surface.",
            "Long press the dock bar with two fingers simultaneously.", true),
        Secret(APP_ORBIT, "Planetary App Orbit", "App Drawer",
            "App drawer icons float in planetary orbits around your favorites.",
            "Launch every installed app once in a single session.", true),
        Secret(LEEK_CURSOR, "Leek Touch Pointer", "Touch Input",
            "Touch indicators and mouse pointers show a miniature fresh leek.",
            "Tap sixty times around the four corners of the display.", true),
        Secret(WEATHER_AURORA, "Live Aurora Weather", "Weather Widget",
            "Real-time shimmering northern lights behind the weather widget.",
            "Check the weather forecast during a sub-zero temperature.", true),
        Secret(HUD_COMPASS, "Cyberpunk Nav Compass", "Status Bar",
            "A micro-navigational compass HUD sits in the status bar.",
            "Spin the device in a full circle while on the home screen.", true),
        Secret(MASCOT_PEEK, "Peeking Chibi Mascot", "System UI",
            "Chibi mascot peeks out warmly from the side of the display.",
            "Leave the launcher idle for three minutes without touching.", true),
        Secret(CYBER_GLITCH_ICON, "Cyber Spark App Launch", "Home screen",
            "Launching an app explodes into electric cyber sparks.",
            "Launch the BPM game five times directly from Now Playing.", true),

        Secret(ARCADE_CLICK_SFX, "Neo-Geo Arcade Clicks", "System Sounds",
            "Tactile mechanical microswitch clicks on every button tap.",
            "Land thirty consecutive GOOD or PERFECT hits without pause.", true),
        Secret(SYNTH_LOCK_SFX, "80s FM Synth Lock Chime", "System Sounds",
            "A lush DX7 FM electric piano chime when the screen sleeps.",
            "Turn off the screen immediately after a PERFECT judgment.", true),
        Secret(LEVEL_UP_RING_SFX, "Victory Jingle on Charge", "System Sounds",
            "Classic 16-bit RPG victory fanfare when plugging in USB power.",
            "Connect the USB-C charger while a combo is active.", true),
        Secret(RETRO_BOOT_SFX, "Workstation Boot Chime", "Boot Audio",
            "Deep 90s Silicon Graphics workstation chime on bootup.",
            "Restart the system after earning three achievements.", true),
        Secret(CODA_WHISPER_SFX, "Binaural Headphone Whisper", "System Sounds",
            "A gentle binaural spatial whisper greets plugged headphones.",
            "Insert 3.5mm or 4.4mm balanced jack while audio is playing.", true),

        Secret(WHEEL_STEALTH_BLACK, "${Mascot.name}Pod Stealth Black", "${Mascot.name}Pod app",
            "The ultra-rare U2 Edition clickwheel with red accents on matte black.",
            "Play rock tracks with the wheel for fifteen minutes.", false),
        Secret(WHEEL_MINI_COLORS, "${Mascot.name}Pod Mini Anodized", "${Mascot.name}Pod app",
            "Vibrant anodized aluminum finishes: Silver, Blue, Pink, Green, Gold.",
            "Cycle through every era in ${Mascot.name}Pod three times.", false),
        Secret(WHEEL_SHUFFLER, "${Mascot.name}Pod Shuffle Stick", "${Mascot.name}Pod app",
            "Minimalist no-screen USB lanyard player interface.",
            "Turn on shuffle mode and skip ten songs in a row.", false),
        Secret(WHEEL_TOUCH_CLICK, "Piezo Wheel Clicker", "${Mascot.name}Pod app",
            "Hyper-authentic mechanical acoustic click speaker simulation.",
            "Spin the click wheel three full rotations without stopping.", true),
        Secret(WHEEL_COVERFLOW_3D, "Cover Flow Hyper-Depth", "${Mascot.name}Pod app",
            "Enhanced 3D perspective and dynamic shadow depth in Cover Flow.",
            "Browse fifty albums in Cover Flow with swift swipes.", true),

        Secret(GAME_GOLDEN_HIGHWAY, "24K Gold Highway Rails", "BPM game",
            "Highway rails turn into shimmering solid gold ribbons.",
            "Collect fifteen lucky golden notes across your sessions.", true),
        Secret(GAME_RAINBOW_NOTES, "Prismatic Chromatic Notes", "BPM game",
            "Every falling note cycles through a vivid chromatic rainbow spectrum.",
            "Maintain an unbroken fifty combo during Fever Frenzy.", true),
        Secret(GAME_FEVER_HYPERSPACE, "Warp Hyperspace Tunnel", "BPM game",
            "77x Fever frenzy shifts the background into a warp hyperspace tunnel.",
            "Activate Fever three times in a single song.", true),
        Secret(GAME_COMBO_GOD, "Arcade Announcer Pack", "BPM game",
            "Energetic 90s Japanese arcade announcer shouts combo milestones.",
            "Hit a 100x combo streak with zero errors.", true),
        Secret(GAME_LASER_TARGET, "Dual Laser Crosshairs", "BPM game",
            "Precision laser target reticles track incoming notes on the hit line.",
            "Reach player level 50 in the BPM game.", true),
        Secret(GAME_RETRO_8BIT_SFX, "8-Bit Chiptune Hits", "BPM game",
            "Hit feedback switches to crisp GameBoy chiptune audio blips.",
            "Judge twenty taps on an 8-bit or chiptune track.", true),
        Secret(GAME_SUPER_CRITICAL, "100x Ultra-Criticals", "BPM game",
            "Rare 100x damage critical sparks explode across the stage.",
            "Land three CRIT hits within ten beats.", true),
        Secret(GAME_STAGE_PYRO, "Stadium Stage Fireworks", "BPM game",
            "Pyrotechnic stadium fireworks ignite behind the highway.",
            "Cross a 200x combo milestone on a live track.", true),
        Secret(GAME_NIGHTCORE_SPEED, "Nightcore 1.5x Highway Mutator", "BPM game",
            "Mutator option to speed up highway scroll by 50% for expert reflex play.",
            "Land forty PERFECT hits on a track over 170 BPM.", true),
        Secret(GAME_MIRROR_WORLD, "Inverted Mirror World", "BPM game",
            "Inverts highway perspective and swaps lane layouts.",
            "Score an SSS rank with the Mirror Stage mutator active.", true),
        Secret(GAME_STEALTH_NOTES, "Stealth Ghost Notes", "BPM game",
            "Notes dissolve into stealth cloaking right before the hit line.",
            "Play blind: hit thirty notes relying solely on your rhythm ear.", true),
        Secret(GAME_GOD_TIER_BADGE, "DIVA Transcendence Crown", "BPM game & Profile",
            "The mythical DIVA Transcendence crest bestowed upon master tappers.",
            "Reach Player Level 1000 and conquer the 8-lane noteway.", false),

        Secret(DANKPODS_ONE_GRIT, "The 1-Grit", "Screen Breaker & Soundboard",
            "The most abrasive rock in existence. Triggers the 1-Grit screen breaker shatter animation! Tap SMASH to fire anytime.",
            "Find a rock hard enough to fix any nugget.", true),
        Secret(DANKPODS_PKCELL, "Oh, My PKCELL!", "Top Bar Battery Icon",
            "Replaces the status bar battery icon with an authentic green & gold PKCELL AA battery.",
            "Look inside the battery compartment of a bootleg nugget.", true),
        Secret(DANKPODS_NANO, "na-no", "HionePod (Wheel)",
            "Shrinks the HionePod interface to microscopic 'na-no' dimensions.",
            "Whisper 'na-no' into the void.", true),
        Secret(DANKPODS_CRAIG, "Craig Old Mate", "Player & Audio Profile",
            "Transforms the DAP into an authentic Craig MP3 nugget with CRT LCD fonts and mono FM tuner warmth.",
            "An old mate you can always trust at the flea market.", true),
        Secret(DANKPODS_FRANK, "Frank the Snake", "DankPods Arcade Mini-Game",
            "Tap OPEN to play Frank the Snake! Guide Frank around the Cashies arena eating PKCELLs and dodging 1-Grit rocks.",
            "She doesn't have arms or legs, but she loves big bass.", false),
        Secret(DANKPODS_DIRTY_BUDS, "The Dirty Buds", "Audio DSP & Equalizer",
            "Enables the vintage Dirty Buds EQ profile: glorious muddy mids and zero sub-bass.",
            "Found in the bottom of an old backpack from 2004.", true),
        Secret(DANKPODS_MARACA, "Baby Maraca", "BPM Game & Player",
            "Replaces the beat tap chime with authentic baby maraca shakes.",
            "Shake it like a baby maraca.", true),
        Secret(DANKPODS_DIABEETUS, "Diabeetus Bass", "DSP Engine",
            "12dB pure analog sub-bass boost with transformer saturation.",
            "Too sweet for regular listening.", true),
        Secret(THEME_SHREK_SWAMP, "Shrek Swamp Theme", "System-Wide & Launcher",
            "Turns all OS backgrounds, borders, and accents into swamp moss green (#7BB31A), ogre lime (#A2D032), and mud brown (#5A381E).",
            "WHAT ARE YE DOIN' IN MY SWAMP?! Late-stage mastery reward.", true),
        Secret(WHEEL_SHREK_GREEN, "HionePod Ogre Edition", "HionePod (Wheel)",
            "Exclusive swamp-green anodized aluminum chassis with toxic lime click wheel.",
            "Layers. Like an onion.", true)
    )

    fun byId(id: String): Secret? = ALL.firstOrNull { it.id == id }

    // ---- thresholds (one place to tune) ----------------------------------------------------

    private const val NIGHT_TAPS = 39
    private const val CLEAN_STREAK = 39
    private const val FEVERS_PER_SESSION = 3
    private const val FAST_TRACK_BPM = 158f       // "160 BPM or so": the detector is an estimator
    private const val FAST_RUN_TAPS = 50
    private const val ARTIST_TAPS_EACH = 20
    private const val ARTISTS_NEEDED = 7
    private const val TEMPO_39_BPM = 139f
    private const val TEMPO_39_RUN = 30
    private const val GOLD_GRADE_TAPS = 100
    private const val GOLD_GRADE_PCT = 96f
    private const val GOLDEN_PICKUPS = 10
    private const val RIOT_HOUR = 20
    private const val RIOT_MINUTE = 2
    private const val RIOT_TAPS = 20
    private const val WHEEL_2001_TAPS = 30
    private const val WHEEL_2004_TRACKS = 5
    private const val WHEEL_2004_TAPS_EACH = 10
    private const val WHEEL_2005_LIFETIME = 5_000L
    private const val WHEEL_2007_YEARS = 7
    private const val WHEEL_2007_TAPS_EACH = 10

    // ---- persisted progress ------------------------------------------------------------------

    private const val PREFS = "hione_secrets_progress"
    private var prefs: SharedPreferences? = null
    /** artist (lower-cased) -> judged taps played along to them, lifetime. */
    private val artistTaps = HashMap<String, Int>()
    private var goldenPickups = 0
    private var dirtyTaps = 0
    /** 2004 album (lower-cased) -> track title (lower-cased) -> judged taps, lifetime. */
    private val album2004 = HashMap<String, HashMap<String, Int>>()

    // ---- session progress (reset each time the stage opens) ---------------------------------

    private var nightTaps = 0
    private var cleanStreak = 0
    private var sessionFevers = 0
    private var sessionTaps = 0
    private var sessionGreat = 0
    private var fastRun = 0
    private var tempo39Run = 0
    private var runKey: String? = null
    private var riotTaps = 0
    /** Non-miss judged taps on the current track, counted only while it is a 2001 release. */
    private var wheel2001Run = 0
    /** Release year -> judged taps this session. */
    private val sessionYears = HashMap<Int, Int>()

    /** Release year and album from MediaStore, cached per artist + title. year 0 = unknown. */
    private class TrackMeta(val year: Int, val album: String)
    private val metaCache = HashMap<String, TrackMeta>()

    private val _foundCount = MutableStateFlow(0)
    /** How many secrets are earned. The UI shows "N remain", never the list. */
    val foundCount: StateFlow<Int> = _foundCount.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs == null) {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            goldenPickups = p.getInt("golden", 0)
            runCatching {
                val o = JSONObject(p.getString("artists", "{}") ?: "{}")
                val it = o.keys()
                while (it.hasNext()) { val k = it.next(); artistTaps[k] = o.optInt(k) }
            }
            runCatching {
                val o = JSONObject(p.getString("w2004", "{}") ?: "{}")
                val albums = o.keys()
                while (albums.hasNext()) {
                    val a = albums.next()
                    val t = o.getJSONObject(a)
                    val m = HashMap<String, Int>()
                    val titles = t.keys()
                    while (titles.hasNext()) { val k = titles.next(); m[k] = t.optInt(k) }
                    album2004[a] = m
                }
            }
        }
        refreshFound(ctx)
    }

    fun refreshFound(ctx: Context) {
        val have = HioneUnlocks.unlockedIds(ctx)
        _foundCount.value = ALL.count { it.id in have }
    }

    /** A new set on stage. Session conditions ("in one session") start from zero. */
    @Synchronized
    fun beginSession() {
        nightTaps = 0; cleanStreak = 0; sessionFevers = 0
        sessionTaps = 0; sessionGreat = 0
        fastRun = 0; tempo39Run = 0; runKey = null
        riotTaps = 0; wheel2001Run = 0; sessionYears.clear()
    }

    /** The track changed: runs that are "on one track" restart. */
    @Synchronized
    fun onTrackChanged(key: String?) {
        if (key == runKey) return
        runKey = key
        fastRun = 0; tempo39Run = 0; wheel2001Run = 0
    }

    /** Fever started from judged hits (see HioneBeatClickerEngine.tap). */
    @Synchronized
    fun onFeverStarted() { sessionFevers++ }

    /** A golden leek or a lucky note was caught. Returns secrets earned by it. */
    fun onGoldenPickup(ctx: Context): List<Secret> {
        val n: Int
        synchronized(this) {
            goldenPickups++
            n = goldenPickups
            prefs?.edit()?.putInt("golden", n)?.apply()
        }
        return if (n >= GOLDEN_PICKUPS) grant(ctx, HOLO_SKIN) else emptyList()
    }

    /**
     * One JUDGED tap. [bpm] is the detected tempo (0 when none is locked — then no tempo-based
     * condition can advance). Returns the secrets this tap earned. Call off the main thread: a
     * grant writes Settings.Global, and the first tap on a track looks up its release year.
     */
    fun onJudgedTap(ctx: Context, accuracy: HitAccuracy, bpm: Float, artist: String?, title: String? = null): List<Secret> {
        val meta = trackMeta(ctx, artist, title)
        val earned = ArrayList<String>()
        synchronized(this) {
            sessionTaps++
            if (accuracy.isGreatOrBetter) sessionGreat++

            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)

            if (hour in 0..4) { nightTaps++; if (nightTaps >= NIGHT_TAPS) earned.add(NEGI_BATTERY) }

            cleanStreak = if (accuracy.isGreatOrBetter) cleanStreak + 1 else 0
            if (cleanStreak >= CLEAN_STREAK) earned.add(TAPE_HIONE39)

            if (sessionFevers >= FEVERS_PER_SESSION) earned.add(LEEK_SPIN)

            if (accuracy == HitAccuracy.PERFECT && minute == 39) earned.add(HOLO_LOCK_CLOCK)

            // Riot mode: 20:02, the year the Riot came out. Twenty GREAT-or-better taps in that
            // one minute. Any tap outside the minute starts the count over.
            if (hour == RIOT_HOUR && minute == RIOT_MINUTE) {
                if (accuracy.isGreatOrBetter) riotTaps++
                if (riotTaps >= RIOT_TAPS) earned.add(RIOT_MODE)
            } else riotTaps = 0

            // HionePod eras. All of them need the track's release year from MediaStore.
            val year = meta?.year ?: 0
            if (year == 2001 && accuracy != HitAccuracy.MISS) wheel2001Run++
            if (wheel2001Run >= WHEEL_2001_TAPS) earned.add(WHEEL_2001)
            if (year == 2004 && meta != null && meta.album.isNotBlank() && !title.isNullOrBlank()) {
                val tracks = album2004.getOrPut(meta.album.trim().lowercase()) { HashMap() }
                val t = title.trim().lowercase()
                val n = (tracks[t] ?: 0) + 1
                tracks[t] = n
                if (n == WHEEL_2004_TAPS_EACH) persistAlbums2004()
                if (tracks.values.count { it >= WHEEL_2004_TAPS_EACH } >= WHEEL_2004_TRACKS) earned.add(WHEEL_2004)
            }
            if (HioneBpmSeasonsEngine.lifetime.value.totalTaps >= WHEEL_2005_LIFETIME) earned.add(WHEEL_2005)
            if (year in 1900..2100) {
                sessionYears[year] = (sessionYears[year] ?: 0) + 1
                if (sessionYears.values.count { it >= WHEEL_2007_TAPS_EACH } >= WHEEL_2007_YEARS) earned.add(WHEEL_2007)
            }

            // No-miss run on a fast track. A slower stretch (or no tempo) restarts it, so the
            // whole fifty were played against a fast beat.
            fastRun = if (accuracy == HitAccuracy.MISS || bpm < FAST_TRACK_BPM) 0 else fastRun + 1
            if (fastRun >= FAST_RUN_TAPS) earned.add(CONCERT_MODE)

            // 139 BPM, with the detector's own tolerance, held for a run.
            tempo39Run = if (bpm > 0f && HioneRhythmTiming.bpmMatches(bpm, TEMPO_39_BPM)) tempo39Run + 1 else 0
            if (tempo39Run >= TEMPO_39_RUN) earned.add(BOOT_CHIME)

            if (sessionTaps >= GOLD_GRADE_TAPS && sessionGreat * 100f / sessionTaps >= GOLD_GRADE_PCT) {
                earned.add(GOLDEN_ICON)
            }

            val a = artist?.trim()?.lowercase()
            if (!a.isNullOrEmpty()) {
                val c = (artistTaps[a] ?: 0) + 1
                artistTaps[a] = c
                dirtyTaps++
                // Persist on the tap that crosses the bar (it matters) and otherwise in batches.
                if (c == ARTIST_TAPS_EACH || dirtyTaps >= 15) persistArtists()
                if (artistTaps.values.count { it >= ARTIST_TAPS_EACH } >= ARTISTS_NEEDED) earned.add(NEGI_SNOW)
            }

            val rank = HioneBpmSeasonsEngine.currentSeason.value.rank
            if (rank.ordinal >= HioneBpmSeasonsEngine.SeasonRank.GOLD.ordinal) earned.add(SEASONAL_TOPBAR)

            // Extended Secrets checks
            val curCombo = HioneBeatClickerEngine.combo.value
            val curLevel = HioneBeatClickerEngine.userLevel.value
            if (curLevel >= 50) earned.add(GAME_LASER_TARGET)
            if (curLevel >= 1000) earned.add(GAME_GOD_TIER_BADGE)
            if (curCombo >= 100) earned.add(GAME_COMBO_GOD)
            if (curCombo >= 200) earned.add(GAME_STAGE_PYRO)
            if (cleanStreak >= 40) earned.add(HOLOGRAM_ART)
            if (cleanStreak >= 30 && bpm in 120f..128f) earned.add(CRT_SCANLINES)
            if (cleanStreak >= 30 && bpm > 175f) earned.add(REEL_SPIN_OVERCLOCK)
            if (sessionFevers >= 1 && bpm > 150f) earned.add(RAINBOW_WAVE)
            if (sessionFevers >= 3) earned.add(GAME_FEVER_HYPERSPACE)
            if (HioneBeatClickerEngine.goldenPickups.value >= 15) earned.add(GAME_GOLDEN_HIGHWAY)
            if (accuracy == HitAccuracy.PERFECT && curCombo >= 30) earned.add(ARCADE_CLICK_SFX)
            if (accuracy == HitAccuracy.PERFECT && minute == 33 && hour == 3) earned.add(MATRIX_RAIN_LOCK)

            // DankPods & Shrek Easter Egg Unlocks
            if (sessionTaps >= 12) earned.add(DANKPODS_PKCELL)
            if (sessionTaps >= 20) earned.add(DANKPODS_FRANK)
            if (sessionTaps >= 28 || (accuracy == HitAccuracy.PERFECT && curCombo >= 8)) earned.add(DANKPODS_ONE_GRIT)
            if (sessionTaps >= 36) earned.add(DANKPODS_CRAIG)
            if (sessionTaps >= 44) earned.add(DANKPODS_NANO)
            if (sessionTaps >= 52) earned.add(DANKPODS_DIRTY_BUDS)
            if (sessionTaps >= 60) earned.add(DANKPODS_MARACA)
            if (sessionTaps >= 70) earned.add(DANKPODS_DIABEETUS)
            if (curLevel >= 15 || sessionTaps >= 85) earned.add(THEME_SHREK_SWAMP)
            if (curLevel >= 20 || sessionTaps >= 105) earned.add(WHEEL_SHREK_GREEN)
        }
        if (earned.isEmpty()) return emptyList()
        val out = ArrayList<Secret>()
        for (id in earned) out.addAll(grant(ctx, id))
        return out
    }

    /**
     * Release year and album of the playing track, looked up once per artist + title. Needs
     * READ_MEDIA_AUDIO. Anything that fails (no permission, not in MediaStore, a stream) caches
     * as year 0, which no year condition accepts.
     */
    @Synchronized
    private fun trackMeta(ctx: Context, artist: String?, title: String?): TrackMeta? {
        if (artist.isNullOrBlank() || title.isNullOrBlank()) return null
        val key = artist.trim().lowercase() + "\u0000" + title.trim().lowercase()
        metaCache[key]?.let { return it }
        val m = try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.ALBUM),
                "${MediaStore.Audio.Media.ARTIST}=? AND ${MediaStore.Audio.Media.TITLE}=?",
                arrayOf(artist.trim(), title.trim()), null
            )?.use { c -> if (c.moveToFirst()) TrackMeta(c.getInt(0), c.getString(1) ?: "") else null }
        } catch (_: Throwable) { null } ?: TrackMeta(0, "")
        if (metaCache.size > 500) metaCache.clear()
        metaCache[key] = m
        return m
    }

    private fun persistAlbums2004() {
        val o = JSONObject()
        for ((a, m) in album2004) {
            val t = JSONObject()
            for ((k, v) in m) t.put(k, v)
            o.put(a, t)
        }
        prefs?.edit()?.putString("w2004", o.toString())?.apply()
    }

    private fun persistArtists() {
        dirtyTaps = 0
        val o = JSONObject()
        for ((k, v) in artistTaps) o.put(k, v)
        prefs?.edit()?.putString("artists", o.toString())?.apply()
    }

    /** Grant through the one writer; returns the secret only on its FIRST grant. */
    private fun grant(ctx: Context, id: String): List<Secret> {
        if (HioneUnlocks.isUnlocked(ctx, id)) return emptyList()
        if (!HioneUnlocks.unlock(ctx, id)) return emptyList()
        refreshFound(ctx)
        val s = byId(id) ?: return emptyList()
        if (id == DANKPODS_ONE_GRIT) {
            com.hione.launcher.HioneOneGritScreenBreaker.smash(ctx)
        }
        HioneCelebrations.push(
            HioneCelebrations.Item(true, s.title, s.where, s.howTo)
        )
        com.hione.launcher.haptics.HioneHaptics.unlock(ctx)
        return listOf(s)
    }

    /**
     * Today's hint: one locked secret's cryptic line, the same all day so it reads as a clue
     * rather than a slot machine. Null once every secret is found.
     */
    fun hintOfTheDay(ctx: Context): String? {
        val have = HioneUnlocks.unlockedIds(ctx)
        val locked = ALL.filter { it.id !in have }
        if (locked.isEmpty()) return null
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return locked[day % locked.size].hint
    }
}

/**
 * The unlock moment, as a queue. Anything that grants a reward (visible or secret) pushes here;
 * the game shows one full-screen celebration at a time and pops it when the player taps.
 *
 * A queue rather than a single slot because two rewards can land on the same tap (a clean streak
 * that also crosses a judged-tap milestone), and the second one must not silently overwrite the
 * first — a reward nobody saw is a reward that did not happen.
 */
object HioneCelebrations {
    data class Item(
        val secret: Boolean,
        val title: String,
        val where: String,
        val howTo: String
    )

    private val _queue = MutableStateFlow<List<Item>>(emptyList())
    val queue: StateFlow<List<Item>> = _queue.asStateFlow()

    @Synchronized
    fun push(item: Item) {
        _queue.value = _queue.value + item
        com.hione.launcher.audio.HioneSeasonalAudioEngine.playLevelUpFanfare()
    }

    @Synchronized
    fun dismiss() {
        val q = _queue.value
        if (q.isNotEmpty()) _queue.value = q.drop(1)
    }
}
