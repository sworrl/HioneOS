import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // Launcher icons for the mascot label aliases (ic_launcher_hione*), one shared copy for every
    // app that has an alias. See Mascot.kt and hioneos/mascot/runtime.
    sourceSets["main"].res.srcDir(rootProject.file("../hioneos/mascot/runtime/res"))

    namespace = "com.hione.systemui"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hione.systemui"
        minSdk = 26
        targetSdk = 34
        versionCode = 123
        versionName = "0.3.0"
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
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += setOf("ProtectedPermissions", "QueryAllPackagesPermission")
        abortOnError = false
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "HioneOS_SystemUI-v${defaultConfig.versionName}.apk"
            }
        }
    }
}

dependencies {
    // @SystemApi TrustAgentService is not in the public android.jar. Compile against a stub,
    // the framework class is used at run time. See stubs/build.sh.
    compileOnly(files("stubs/trust-agent-stubs.jar"))
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    // Compose in a window that is NOT an Activity needs the three ViewTree owners set by hand.
    // See HioneShadeWindow: the shade is an accessibility overlay, not an Activity, so that a pull
    // shows an already-composed window instead of paying an activity launch every time.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
}
