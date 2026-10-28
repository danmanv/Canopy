import java.util.Properties

plugins {
    id("com.android.application")
}

// Framework Views only, like Plume. The only libraries are for playback: Media3 (ExoPlayer) and an ffmpeg audio decoder.
android {
    namespace = "dev.alder"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.canopy"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.2"
        // TVs are ARM; the x86 builds of ffmpeg's native code would only add size.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    // Release key lives outside the repo: keystore.properties (git-ignored) points at it locally, and CI gets it from
    // the CANOPY_KEYSTORE* secrets (release.yml). Without either (a fresh clone) release builds fall back to the debug
    // key, so anyone can still build and sideload.
    val keys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
        ?: System.getenv("CANOPY_KEYSTORE")?.takeIf { it.isNotEmpty() }?.let { store ->
            val password = System.getenv("CANOPY_KEYSTORE_PASSWORD").orEmpty()
            Properties().apply {
                setProperty("storeFile", store)
                setProperty("storePassword", password)
                setProperty("keyAlias", System.getenv("CANOPY_KEY_ALIAS")?.takeIf { it.isNotEmpty() } ?: "canopy")
                setProperty("keyPassword", System.getenv("CANOPY_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: password)
            }
        }
    signingConfigs {
        // One shared debug key, committed (it guards nothing), so every CI and local debug build has the same
        // signature and `adb install -r` updates the TV in place, keeping profiles and sign-in.
        getByName("debug") {
            storeFile = file("canopy-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keys != null) create("release") {
            storeFile = file(keys.getProperty("storeFile"))
            storePassword = keys.getProperty("storePassword")
            keyAlias = keys.getProperty("keyAlias")
            keyPassword = keys.getProperty("keyPassword")
        }
    }

    // -PreleaseKey (CI once the key is in its secrets): the release key or a failed build, never a quiet fallback.
    val releaseKey = if (!project.hasProperty("releaseKey")) null else signingConfigs.findByName("release")
        ?: error("-PreleaseKey: no release key. Set CANOPY_KEYSTORE and CANOPY_KEYSTORE_PASSWORD, or add keystore.properties.")

    buildTypes {
        getByName("debug") {
            // With -PreleaseKey a PR's build is signed like the releases, so the TV moves between them with
            // `adb install -r` and the app's own updater, keeping profiles and sign-in.
            if (releaseKey != null) signingConfig = releaseKey
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // -PdebugKey: an optimised build that installs over a debug one on a test TV (same key, data kept).
            signingConfig = releaseKey
                ?: signingConfigs.findByName("release")?.takeUnless { project.hasProperty("debugKey") }
                ?: signingConfigs.getByName("debug")
        }
    }
}

// CHANGELOG.md goes into the app as an asset, so "What's new" shows the notes for the version installed, offline.
abstract class ChangelogAsset : DefaultTask() {
    @get:InputFile abstract val changelog: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun copy() {
        changelog.get().asFile.copyTo(outputDir.get().file("CHANGELOG.md").asFile, overwrite = true)
    }
}

val changelogAsset = tasks.register<ChangelogAsset>("changelogAsset") {
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(changelogAsset, ChangelogAsset::outputDir) }
}

// Media3 stays on the version Jellyfin's ffmpeg decoder is built against: its native glue targets one exact release.
val media3 = "1.9.0"

dependencies {
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3") // live and sports addons stream HLS
    implementation("androidx.media3:media3-ui:$media3")
    // Software audio for what TVs can't decode themselves (DTS, TrueHD), so those files still have sound.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    testImplementation("junit:junit:4.13.2")
}
