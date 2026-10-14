package com.hione.update.ota

import com.hione.update.Mascot

/**
 * Fixed facts about the OTA system. The server layout and the manifest format are documented in
 * hioneos/ota/README.md; keep the two in step.
 */
object OtaConfig {
    /** Where the channels live: <base>/<channel>/manifest.json and manifest.json.sig. */
    const val DEFAULT_BASE_URL = "https://hioneos.falcontechnix.com/ota/"

    /** Where a full image goes on. Used when a manifest does not name its own installer_url. */
    const val DEFAULT_INSTALLER_URL = "https://hioneos.falcontechnix.com/install/"

    val CHANNELS = listOf("stable", "beta", "dev")
    const val DEFAULT_CHANNEL = "stable"

    /** The manifest schema this app understands. A higher number is refused, not guessed at. */
    const val SCHEMA_VERSION = 1

    /** Assets copied from hioneos/ota/keys at build time (see build.gradle.kts). */
    val KEY_ASSETS = listOf(
        "primary" to "ota-keys/ota-signing-primary-p521.pub.pem",
        "backup" to "ota-keys/ota-signing-backup-p521.pub.pem",
    )

    /** Hard cap on the manifest download. A real one is a few KB. */
    const val MAX_MANIFEST_BYTES = 512 * 1024
    const val MAX_SIGNATURE_BYTES = 4 * 1024

    /**
     * Packages that must only ever come from the system image. A /data copy of the FM app loses
     * the shared linker namespace its JNI needs and the tuner stops working, so it is refused here
     * whatever a manifest says, and the publish script refuses to list it too.
     */
    val NEVER_UPDATE = setOf("com.caf.fmradio")

    /** Packages this app may update: HioneOS's own apps and nothing else. */
    fun isUpdatable(pkg: String): Boolean {
        if (pkg in NEVER_UPDATE) return false
        return pkg.startsWith("com.hione.") || pkg == "com.m500.hardware"
    }

    /** Friendly names for the list. Unknown packages fall back to the package name. */
    fun displayName(pkg: String): String = when (pkg) {
        "com.hione.player" -> "${Mascot.musicAppName}"
        "com.hione.launcher" -> "${Mascot.name} Launcher"
        "com.hione.settings" -> "${Mascot.settingsName}"
        "com.hione.systemui" -> "${Mascot.osName} System UI"
        "com.m500.hardware" -> "Hardware Settings"
        "com.hione.sysbridge" -> "System Bridge"
        "com.hione.tools" -> "${Mascot.name} Tools"
        "com.hione.media" -> "${Mascot.name} Media"
        "com.hione.update" -> "${Mascot.updateName}"
        "com.hione.tv" -> "${Mascot.name} TV"
        else -> pkg
    }
}
