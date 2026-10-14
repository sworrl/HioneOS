plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hione.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hione.tv"
        // Android TV 8.0. Google TV sets are well above this; the floor only matters if an older
        // box turns up.
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -O3 -ffast-math -fexceptions -frtti")
            }
        }
        ndk {
            abiFilters.addAll(setOf("armeabi-v7a"))
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("../app/src/main/assets")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Deliberately NOT platform-signed, unlike every other module here. The Falcon platform key
    // is what gives our apps system permissions on the M500; on a TV it is just an unknown
    // signature and buys nothing. A plain signing key keeps sideloading simple and avoids
    // implying this app has privileges it does not.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "HioneOS_TV-v${defaultConfig.versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
