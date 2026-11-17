import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Play upload key: kept outside the repo (default ~/.steplayer-signing/keystore.properties, or -Psteplayer.signing=<file>).
// Without it, release builds are signed with the debug key for local testing, and Play bundles are refused.
val uploadKeyFile = file(
    (findProperty("steplayer.signing") as String?) ?: "${System.getProperty("user.home")}/.steplayer-signing/keystore.properties",
)
val uploadKey: Properties? = uploadKeyFile.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.steplayer.app"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.steplayer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (uploadKey != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
            // Native symbol tables in the bundle: Play Console can read crashes in the FFmpeg JNI code.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.media3.common.util.UnstableApi",
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

// Baseline Profile (generated on an Android TV emulator by :baselineprofile): AOT-compiles the startup and
// D-pad browsing paths at install time, and orders the DEX so startup classes are read first.
baselineProfile {
    dexLayoutOptimization = true
}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.mediarouter)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.cast)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.play.services.cast)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    implementation(libs.zxing.core)
    // Installs the Baseline Profile also on sideloaded APKs (TV boxes rarely get them from the Play Store).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    // FFmpeg audio decoders (AC-3, E-AC-3, DTS, TrueHD, …): LGPL shared libraries, see decoder-ffmpeg/.
    implementation(project(":decoder-ffmpeg"))
}

// A bundle for Google Play must carry the upload key, never the debug one.
tasks.matching { it.name == "bundleRelease" }.configureEach {
    // Plain local values only: the configuration cache cannot serialise references to the build script.
    val hasUploadKey = uploadKey != null
    val uploadKeyPath = uploadKeyFile.path
    doFirst {
        check(hasUploadKey) { "Play upload key not found: $uploadKeyPath (see store/README.md)" }
    }
}

