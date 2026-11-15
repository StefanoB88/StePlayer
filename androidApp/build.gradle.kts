import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.steplayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.steplayer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // TODO: replace with a real release keystore before publishing.
            signingConfig = signingConfigs.getByName("debug")
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
}
