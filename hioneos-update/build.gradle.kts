import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The OTA public keys live in one place, hioneos/ota/keys, next to the publish tooling. They are
// copied into the APK's assets at build time by exact file name. Never a wildcard: nothing else
// in that directory (or anything someone drops there by mistake) can end up inside the APK.
// The A/B payload certificates go in too: the app compares them with the image's
// /system/etc/security/otacerts.zip to know whether update_engine will take a HioneOS payload.
val otaKeyNames = listOf(
    "ota-signing-primary-p521.pub.pem", "ota-signing-backup-p521.pub.pem",
    "ab-payload-primary.x509.pub.pem", "ab-payload-backup.x509.pub.pem",
)
val otaKeyAssetsDir = layout.buildDirectory.dir("generated/otaKeys")
val copyOtaKeys = tasks.register<Copy>("copyOtaKeys") {
    val keyDir = rootProject.file("../hioneos/ota/keys")
    doFirst {
        otaKeyNames.forEach { name ->
            val f = File(keyDir, name)
            require(f.isFile) { "missing OTA public key ${f.path}" }
            val text = f.readText()
            require((text.contains("BEGIN PUBLIC KEY") || text.contains("BEGIN CERTIFICATE")) && !text.contains("PRIVATE")) {
                "${f.path} is not a public key or certificate PEM; refusing to package it"
            }
        }
    }
    from(keyDir) { otaKeyNames.forEach { include(it) } }
    into(otaKeyAssetsDir.map { it.dir("ota-keys") })
}

// The built-in mascot packs (hioneos/mascot/packs/<id>) go into assets/mascot/<id>, served to the
// other apps by MascotPackProvider. Only what the apps read at run time: pack.json, art, the
// character drawables, the recolored theme icons, the big launcher icon and the voice clips.
// Popup frames and the boot video are image-only and stay out. So do source photos over 1 MB.
val mascotPackIds = listOf("hione")
val mascotAssetsDir = layout.buildDirectory.dir("generated/mascotPacks")
val copyMascotPacks = tasks.register<Sync>("copyMascotPacks") {
    val packsDir = rootProject.file("../hioneos/mascot/packs")
    mascotPackIds.forEach { id ->
        from(File(packsDir, id)) {
            include("pack.json", "art/**", "res/drawable*/**", "res/icons-xxxhdpi/**", "res/mipmap-xxxhdpi/**",
                "voice/raw/**", "voice/boot/**")
            exclude { it.isDirectory.not() && it.name.lowercase().let { n -> n.endsWith(".jpg") || n.endsWith(".jpeg") } && it.file.length() > 1_000_000 }
            into(id)
        }
        // "shared_art" in pack.json: dirs under hioneos/mascot (the season and holiday art that
        // holidays/calendar.json names) whose 720x1280 JPEGs ship at the same relative path.
        val meta = groovy.json.JsonSlurper().parse(File(packsDir, "$id/pack.json")) as Map<*, *>
        (meta["shared_art"] as? List<*>)?.forEach { dir ->
            from(File(packsDir.parentFile, dir.toString())) {
                include("**/*_720.jpg")
                into("$id/$dir")
            }
        }
    }
    into(mascotAssetsDir.map { it.dir("mascot") })
}

// android.os.UpdateEngine is @SystemApi, so it is missing from the public android.jar. Two small
// stubs in src/systemapi are compiled into a jar that is only on the compile classpath; the APK
// never carries them and the real classes come from the framework at run time.
val systemApiStubClasses = tasks.register<JavaCompile>("compileSystemApiStubs") {
    source = fileTree("src/systemapi/java")
    classpath = files("${android.sdkDirectory.path}/platforms/android-34/android.jar")
    destinationDirectory.set(layout.buildDirectory.dir("systemApiStubs/classes"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
val systemApiStubJar = tasks.register<Jar>("systemApiStubJar") {
    from(systemApiStubClasses)
    archiveFileName.set("systemapi-stubs.jar")
    destinationDirectory.set(layout.buildDirectory.dir("systemApiStubs"))
}

android {
    // Launcher icons for the mascot label aliases (ic_launcher_hione*), one shared copy for every
    // app that has an alias. See Mascot.kt and hioneos/mascot/runtime.
    sourceSets["main"].res.srcDir(rootProject.file("../hioneos/mascot/runtime/res"))

    namespace = "com.hione.update"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hione.update"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.4.1"
    }

    sourceSets["main"].assets.srcDir(otaKeyAssetsDir)
    sourceSets["main"].assets.srcDir(mascotAssetsDir)

    // Pack files stay uncompressed in the APK so the provider can hand out a real fd for them.
    androidResources {
        noCompress += listOf("webp", "png", "jpg", "jpeg", "ogg", "json")
    }

    // Same signing pattern as hione-sysbridge: the platform key, because this app runs as the
    // system UID (sharedUserId android.uid.system) and that only installs when platform-signed.
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
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += setOf(
            "ProtectedPermissions", "MissingPermission", "NewApi", "RestrictedApi",
            "UnspecifiedRegisterReceiverFlag", "QueryAllPackagesPermission", "RequestInstallPackagesPolicy"
        )
        abortOnError = false
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "HioneUpdate.apk"
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(copyOtaKeys, copyMascotPacks) }

dependencies {
    // PackFilesTest, ContentPackParseTest (org.json is stubbed in android.jar).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    compileOnly(files(systemApiStubJar.flatMap { it.archiveFile }).builtBy(systemApiStubJar))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.zxing:core:3.5.3")
}
