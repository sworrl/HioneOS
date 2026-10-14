import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.caf.fmradio"
    compileSdk = 34

    defaultConfig {
        // CRITICAL: the package name MUST be com.caf.fmradio — the device's SELinux
        // seapp_contexts grants the vendor_fm_app domain (the only one allowed to
        // open /dev/radio0) by matching name=com.caf.fmradio + seinfo=platform. Any
        // other applicationId gets untrusted_app and every tuner open() is denied.
        applicationId = "com.caf.fmradio"
        minSdk = 26
        targetSdk = 34
        versionCode = 1016
        versionName = "1.2.0-hioneos"

        // projectM background (FmProjectMBackground.kt). arm64 only, like the vendor JNI.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -O3 -ffast-math -fexceptions -frtti")
                arguments("-DANDROID_STL=c++_static")
            }
        }
    }

    // libfmprojectm builds the SAME vendored libprojectM tree as Hione Music
    // (app/src/main/cpp/vendor/projectm, referenced by relative path, not copied). Only the JNI
    // wrapper lives here. See src/main/cpp/CMakeLists.txt.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Platform signing (seinfo=platform is required alongside the package name).
    val signingProps = Properties().apply {
        for (f in listOf(rootProject.file("../keystore.properties"),
                         rootProject.file("keystore.properties"),
                         rootProject.file("local.properties"))) {
            if (f.exists()) { f.inputStream().use { load(it) }; break }
        }
    }
    val storePath: String? = signingProps.getProperty("platform.storeFile")
    val storeFileResolved = storePath?.let { p ->
        listOf(rootProject.file("../$p"), rootProject.file(p), file(p)).firstOrNull { it.exists() }
    }
    val hasPlatform = storeFileResolved != null &&
        signingProps.getProperty("platform.storePassword") != null &&
        signingProps.getProperty("platform.keyPassword") != null

    signingConfigs {
        if (hasPlatform) {
            create("platform") {
                storeFile = storeFileResolved
                storePassword = signingProps.getProperty("platform.storePassword")
                keyAlias = signingProps.getProperty("platform.keyAlias", "platform")
                keyPassword = signingProps.getProperty("platform.keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasPlatform) signingConfig = signingConfigs.getByName("platform")
            isMinifyEnabled = false
        }
        debug {
            if (hasPlatform) signingConfig = signingConfigs.getByName("platform")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; aidl = true }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "FMRadio-com.caf.fmradio-v${defaultConfig.versionName}.apk"
            }
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    // FmReachTest touches FmFresnel, which logs through android.util.Log.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Exact-signature stubs of the device qcom.fmradio.jar (compile only; runtime = uses-library).
    compileOnly(project(":qcom-fmradio-stubs"))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
