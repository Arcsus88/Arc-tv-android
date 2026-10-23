import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Screenshot tests: real screens rendered on the JVM (no emulator needed).
    alias(libs.plugins.roborazzi)
}

// Release signing: read from keystore.properties (gitignored) or environment (CI).
// Without one, release builds are signed with the debug key so they still install.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

// The TMDB key the update button uses to find new films: local.properties
// (tmdb.apiKey, gitignored) or the TMDB_API_KEY environment variable. Without
// one, the app asks for a key the first time the update button is pressed.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
val tmdbApiKey: String = localProperties.getProperty("tmdb.apiKey") ?: System.getenv("TMDB_API_KEY") ?: ""

// Self-update (see RELEASING.md): the release workflow passes the version and a
// read-only GitHub token for this private repo's releases. Builds without a
// token never check for updates.
val updateToken: String = localProperties.getProperty("update.token") ?: System.getenv("UPDATE_TOKEN") ?: ""
val updateRepo = "Arcsus88/Arc-tv-android"
val appVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 2
val appVersionName = (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.1.0"

fun quoted(s: String) = "\"" + s.replace("\\", "").replace("\"", "") + "\""

android {
    namespace = "com.arcsus.arcdisney"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arcsus.arcdisney"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "TMDB_API_KEY", quoted(tmdbApiKey))
        buildConfigField("String", "UPDATE_TOKEN", quoted(updateToken))
        buildConfigField("String", "UPDATE_REPO", quoted(updateRepo))
    }

    // libVLC and libtorrent are native, so each CPU type gets its own APK, plus
    // one with both ARM builds: plenty of Android 9 devices run a 32-bit system
    // on a 64-bit chip, and that one installs on either.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "3g" }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Compressed in the APK: libVLC and libtorrent are ~70 MB stored
            // uncompressed, about half that compressed, and this APK is sideloaded.
            useLegacyPackaging = true
            // libVLC's AAR also carries x86 builds; no device this targets needs them.
            excludes += listOf("lib/x86/**", "lib/x86_64/**")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// JVM unit tests run the real torrent engine against libtorrent's Linux build:
// pull its .so out of the jar and point libtorrent4j at it.
val libtorrentLinux: Configuration by configurations.creating
val extractLibtorrentLinux by tasks.registering(Copy::class) {
    from({ libtorrentLinux.map { zipTree(it) } })
    include("lib/x86_64/libtorrent4j.so")
    eachFile { path = name }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("libtorrent-linux"))
}
tasks.withType<Test>().configureEach {
    dependsOn(extractLibtorrentLinux)
    val so = layout.buildDirectory.file("libtorrent-linux/libtorrent4j.so")
    doFirst { systemProperty("libtorrent4j.jni.path", so.get().asFile.absolutePath) }
    // The snapshot generator (see README) is opt-in; pass its switches through.
    listOf("generateSnapshot", "snapshotSeed", "snapshotLimit", "snapshotRetry", "posterDir").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    System.getenv("TMDB_API_KEY")?.let { environment("TMDB_API_KEY", it) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    // The player: VLC inside the app.
    implementation(libs.libvlc)

    // The torrent engine: libtorrent's Java API plus each phone ABI's native build.
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.arm)
    libtorrentLinux(libs.libtorrent4j.linux)

    // The QR code for AllDebrid's PIN sign-in.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
