// Media3 FFmpeg audio decoder (Apache 2.0, androidx/media 1.8.0 "decoder_ffmpeg"), with FFmpeg 6.0 built as LGPL
// shared libraries by build_ffmpeg.sh. Picked up by DefaultRenderersFactory through reflection.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-proguard-rules.txt")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.exoplayer)
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.48.4")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:1.9.0")
}
