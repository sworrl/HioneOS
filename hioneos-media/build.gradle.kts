import java.util.Properties
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hione.media"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hione.media"
        // 30, not the usual 26: this APK only ever ships inside the HioneOS image (SDK 34), and
        // MediaStore.createDeleteRequest/createWriteRequest (30), loadThumbnail (29) and scoped
        // RELATIVE_PATH writes are the whole basis of the gallery and recorder. Supporting older
        // releases would mean a second, untested legacy storage path for no device that exists.
        minSdk = 30
        targetSdk = 34
        versionCode = 3
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
            // Unlike the other small modules this one is minified. CameraX + Media3 + the extended
            // icon set come to well over 20 MB of dex unshrunk, all of it sitting in /system on a
            // partition with little room. Every library here ships its own consumer R8 rules and
            // the app code uses no reflection, so shrinking is safe.
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
    }

    // Sound assets are opened with openFd() for SoundPool and MediaPlayer, which only works on
    // stored (uncompressed) entries. AGP already stores .ogg; this keeps it explicit.
    androidResources {
        noCompress += "ogg"
    }

    lint {
        disable += setOf("ProtectedPermissions", "MissingPermission", "UnsafeOptInUsageError")
        abortOnError = false
    }

    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                outputFileName = "HioneMedia.apk"
            }
        }
    }
}

/**
 * Copies the camera, recorder and gallery subset of the HioneOS sound library into the APK's
 * assets at build time, with an index trimmed to just those rows.
 *
 * hioneos/data stays the single source of truth: regenerate a clip there and the next build picks
 * it up. Only the files this app uses are declared as inputs, so the 100+ other clips and the
 * radio station database are neither copied nor hashed. If the hioneos checkout is not next to
 * this repo, the task writes an empty asset dir and the app runs silent rather than failing.
 */
abstract class HioneSoundsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val dataDir: DirectoryProperty

    @get:Input
    abstract val apps: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val root = File(out, "sounds").apply { mkdirs() }
        val data = dataDir.get().asFile
        val indexFile = File(data, "sounds_index.json")
        if (!indexFile.exists()) {
            logger.warn("hioneos-media: ${indexFile} not found, building without sounds")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val index = JsonSlurper().parse(indexFile) as Map<String, Any?>
        val keep = apps.get().toSet()
        fun rows(key: String) = (index[key] as? List<*>).orEmpty().filterIsInstance<Map<String, Any?>>().filter { it["app"] in keep }
        val voice = rows("voice")
        val sfx = rows("sfx")
        val paths = voice.flatMap { (it["files"] as? Map<*, *>).orEmpty().values.map { v -> v.toString() } } +
            sfx.map { it["file"].toString() }
        for (rel in paths) {
            val src = File(data, rel)
            if (src.exists()) src.copyTo(File(root, rel), overwrite = true)
            else logger.warn("hioneos-media: missing sound ${rel}")
        }
        val trimmed = LinkedHashMap<String, Any?>()
        trimmed["version"] = index["version"]
        trimmed["voices"] = index["voices"]
        trimmed["default_voice"] = index["default_voice"]
        trimmed["voice"] = voice
        trimmed["sfx"] = sfx
        File(root, "sounds_index.json").writeText(JsonOutput.toJson(trimmed))
    }
}

val hioneSoundsData = rootProject.layout.projectDirectory.dir("../hioneos/data")
val hioneSoundApps = listOf("camera", "recorder", "gallery")
val copyHioneSounds = tasks.register<HioneSoundsTask>("copyHioneSounds") {
    dataDir.set(hioneSoundsData)
    apps.set(hioneSoundApps)
    val d = hioneSoundsData.asFile
    sources.from(File(d, "sounds_index.json").takeIf { it.exists() } ?: emptyList<File>())
    hioneSoundApps.forEach { app ->
        sources.from(fileTree(File(d, "sfx/$app")).matching { include("*.ogg") })
        listOf("mirai", "hoshi", "cyber").forEach { v -> sources.from(fileTree(File(d, "voice/$v/app/$app")).matching { include("*.ogg") }) }
    }
    // No outputDir here: addGeneratedSourceDirectory below assigns one per variant
    // (build/generated/assets/copyHioneSounds/<variant>) and wires the task into the merge.
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyHioneSounds, HioneSoundsTask::outputDir)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Camera: CameraX over camera2. The view artifact supplies PreviewView, which handles the
    // sensor-to-display transform that is the usual source of sideways previews on odd hardware.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Same Media3 version the player app already ships, so the image carries one copy's worth of
    // known-good behaviour on this SoC's decoders.
    implementation("androidx.media3:media3-exoplayer:1.4.1")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
