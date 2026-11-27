plugins {
    id("com.android.library")
}

android {
    namespace = "com.decent.usbaudio.media3"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin { jvmToolchain(21) }
}

dependencies {
    api(project(":decent-usb-audio-driver"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.media3:media3-database:1.5.1")

    // JSch fork (maintained) — SFTP streaming with native offset seek
    implementation("com.github.mwiede:jsch:0.2.23")
}
