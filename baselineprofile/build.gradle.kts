import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates the app's Baseline Profile (ahead-of-time compiled startup + navigation code: faster start and smoother
// scrolling, most noticeable on slow Android TV boxes) and measures startup/scroll with Macrobenchmark.
// Generate:  ./gradlew :androidApp:generateReleaseBaselineProfile   (with an emulator/device connected)
// Measure:   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.steplayer.baselineprofile"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The test list lives on the host (tizenApp/dev/server.mjs) reachable from the emulator.
        testInstrumentationRunnerArguments["listUrl"] = "http://10.0.2.2:8082/dev/list.m3u"
        // Emulator numbers are only compared with each other (before/after), never taken as absolute.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    targetProjectPath = ":androidApp"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

baselineProfile { useConnectedDevices = true }

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}
