// Hione Voice Check: a dev tool for reviewing the generated voice clips and sound effects on the
// device itself. Not part of the image; installed with adb. It bundles the clips straight from
// ../hioneos/data at build time, so it always plays what the pipeline last rendered.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val soundsSrc = rootProject.file("../hioneos/data")
val genAssets = layout.buildDirectory.dir("generated/voicecheckAssets")
val copySounds by tasks.registering(Copy::class) {
    // os_sounds: the HioneOS system sounds (tools/sounds/build_os_sounds.py), chimes and voices.
    from(soundsSrc) { include("voice/**", "sfx/**", "sounds_index.json", "os_sounds/**/*.ogg", "os_sounds/index.json") }
    into(genAssets)
}

android {
    namespace = "com.hione.voicecheck"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.hione.voicecheck"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    sourceSets["main"].assets.srcDir(genAssets)
    // MediaPlayer plays straight from the APK via openFd, which needs the clips stored as-is.
    androidResources { noCompress += listOf("ogg", "opus") }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) outputFileName = "HioneVoiceCheck.apk"
        }
    }
}
tasks.matching { (it.name.startsWith("merge") && it.name.endsWith("Assets")) || it.name.contains("LintVital", ignoreCase = true) }.configureEach { dependsOn(copySounds) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
}
