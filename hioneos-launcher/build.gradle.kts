import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Optional, gitignored local secrets file for the arcobocconotto RGB integration
// (direct HMAC key path / endpoint overrides for local dev). Never committed;
// see hioneos-launcher/arco.properties.example for the expected keys. Everything
// here defaults to an empty string when the file is absent — the app falls back
// to interactive mDNS discovery + SAS pairing at runtime in that case.
val arcoProps = Properties().apply {
    val f = rootProject.file("hioneos-launcher/arco.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun arcoProp(key: String) = arcoProps.getProperty(key, "")

// Gitignored local.properties (never committed) — seeds the media-ingestion / network-telemetry
// BuildConfig fields so no personal host/IP/subnet lives in source. All default to "" when absent;
// HioneIngestConfig then falls back to runtime auto-discovery or a neutral display.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String) = localProps.getProperty(key, "")

// The holiday calendar (hioneos/mascot/holidays/calendar.json) goes into assets/holidays/. Only
// that one file: holiday art stays in the mascot packs. Missing file = no holidays, seasons only.
val holidayCalendarDir = layout.buildDirectory.dir("generated/holidayCalendar")
val copyHolidayCalendar = tasks.register<Copy>("copyHolidayCalendar") {
    from(rootProject.file("../hioneos/mascot/holidays")) { include("calendar.json") }
    into(holidayCalendarDir.map { it.dir("holidays") })
}

android {
    sourceSets["main"].assets.srcDir(holidayCalendarDir)
    // Unit tests touch Mascot, whose object init builds an android.util.LruCache: let the
    // android.jar stubs return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }

    namespace = "com.hione.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hione.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 85
        versionName = "0.3.1"

        buildConfigField("String", "ARCO_HMAC_KEY_ID", "\"${arcoProp("ARCO_HMAC_KEY_ID")}\"")
        buildConfigField("String", "ARCO_HMAC_SECRET", "\"${arcoProp("ARCO_HMAC_SECRET")}\"")
        buildConfigField("String", "ARCO_LAN_URL", "\"${arcoProp("ARCO_LAN_URL")}\"")
        buildConfigField("String", "ARCO_PUBLIC_URL", "\"${arcoProp("ARCO_PUBLIC_URL")}\"")

        // Media ingestion server + network/VPN telemetry (see HioneIngestConfig). Sourced from the
        // gitignored local.properties; all default to "" (=> runtime auto-discovery / neutral display).
        // Example keys: hione.sync.host, hione.sync.port, hione.lan.hints, hione.home.ssids,
        // hione.vpn.endpoint, hione.vpn.assigned.ip, hione.vpn.routes (TEST-NET values, e.g. 192.0.2.10).
        buildConfigField("String", "HIONE_SYNC_HOST", "\"${localProp("hione.sync.host")}\"")
        buildConfigField("String", "HIONE_SYNC_PORT", "\"${localProp("hione.sync.port")}\"")
        buildConfigField("String", "HIONE_LAN_HINTS", "\"${localProp("hione.lan.hints")}\"")
        buildConfigField("String", "HIONE_HOME_SSIDS", "\"${localProp("hione.home.ssids")}\"")
        buildConfigField("String", "HIONE_VPN_ENDPOINT", "\"${localProp("hione.vpn.endpoint")}\"")
        buildConfigField("String", "HIONE_VPN_ASSIGNED_IP", "\"${localProp("hione.vpn.assigned.ip")}\"")
        buildConfigField("String", "HIONE_VPN_ROUTES", "\"${localProp("hione.vpn.routes")}\"")

        // Windy Point Forecast API key for the Hione weather tile (local.properties: hione.windy.key).
        // Empty => Open-Meteo only; the user can also paste a key in the tile's detail sheet.
        buildConfigField("String", "HIONE_WINDY_KEY", "\"${localProp("hione.windy.key")}\"")
    }

    val signingProps = Properties().apply {
        val candidates = listOf(
            rootProject.file("../keystore.properties"),
            rootProject.file("keystore.properties"),
            rootProject.file("local.properties")
        )
        for (f in candidates) {
            if (f.exists()) {
                f.inputStream().use { load(it) }
                break
            }
        }
    }
    val platformStorePath: String? = signingProps.getProperty("platform.storeFile")
    val platformStoreFile = platformStorePath?.let { path: String ->
        listOf(
            rootProject.file("../$path"),
            rootProject.file(path),
            file(path)
        ).firstOrNull { it.exists() }
    }
    val platformStorePass: String? = signingProps.getProperty("platform.storePassword")
    val platformKeyAlias: String = signingProps.getProperty("platform.keyAlias", "platform")
    val platformKeyPass: String? = signingProps.getProperty("platform.keyPassword")
    val hasPlatformSigning = platformStoreFile != null && platformStorePass != null && platformKeyPass != null

    signingConfigs {
        if (hasPlatformSigning) {
            create("platform") {
                storeFile = platformStoreFile
                storePassword = platformStorePass
                keyAlias = platformKeyAlias
                keyPassword = platformKeyPass
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (hasPlatformSigning) {
                signingConfig = signingConfigs.getByName("platform")
            }
        }
        release {
            if (hasPlatformSigning) {
                signingConfig = signingConfigs.getByName("platform")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // Correct-by-design for a platform-signed system launcher — not defects:
        //  QueryAllPackagesPermission: a launcher legitimately enumerates all apps.
        //  ProtectedPermissions: system/signature permissions on our own ROM.
        //  RestrictedApi: dispatchKeyEvent on Activity subclasses.
        disable += setOf("QueryAllPackagesPermission", "ProtectedPermissions", "RestrictedApi")
        // Accept remaining known issues (NewApi on API-34-only device, pre-granted runtime perms,
        // framework false-positives) via a baseline; NEW issues still fail the build.
        baseline = file("lint-baseline.xml")
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "HioneOS_Launcher-v${defaultConfig.versionName}.apk"
            }
        }
    }
}

dependencies {
    // Unit tests: holiday date rules and mascot pack switching (org.json is stubbed in android.jar).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0") // animated GIF decoding (HiBy onboarding GIFs)

    // media3 MediaController: the OS lockscreen (com.hione.launcher.lockscreen) connects to the
    // music app's MediaSession (com.hione.player/.PlaybackService) to read now-playing metadata /
    // artwork and drive transport, without importing any app classes.
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // arcobocconotto RGB fleet integration: JSON models + encrypted local credential storage.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Native WireGuard tunnel (embeddable, bundles wireguard-go arm64 backend + config parser).
    // Powers the in-launcher VPN client (com.hione.launcher.vpn) for reaching the user's home UDR.
    implementation("com.wireguard.android:tunnel:1.0.20230706")
}

tasks.named("preBuild") { dependsOn(copyHolidayCalendar) }
