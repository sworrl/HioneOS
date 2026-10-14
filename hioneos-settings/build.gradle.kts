import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The holiday list for the Theme & mascot page (custom holiday checklist). Only calendar.json is
// copied: holiday art in the same folder belongs to the mascot packs, not to this APK. A build
// without the file still works, the page then says there is no list.
val holidayAssetsDir = layout.buildDirectory.dir("generated/holidayCalendar")
val copyHolidayCalendar = tasks.register<Copy>("copyHolidayCalendar") {
    from(rootProject.file("../hioneos/mascot/holidays")) { include("calendar.json") }
    into(holidayAssetsDir)
}
android {
    namespace = "com.hione.settings"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hione.settings"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "0.3.1"
    }

    sourceSets["main"].assets.srcDir(holidayAssetsDir)

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
                outputFileName = "HioneOS_Settings-v${defaultConfig.versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
}

tasks.named("preBuild") { dependsOn(copyHolidayCalendar) }
