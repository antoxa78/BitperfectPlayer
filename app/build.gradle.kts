import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Read signing credentials from local.properties (which is gitignored).
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

fun prop(name: String): String? = localProperties.getProperty(name)
    ?: providers.gradleProperty(name).orNull

android {
    namespace = "com.example.bitperfectplayer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.github.antoxa78.bitperfectplayer"
        minSdk = 28
        targetSdk = 36
        versionCode = 45
        versionName = "3.0.0"

        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Include all common ABIs so the APK installs on any device (e.g. 32-bit
            // armeabi-v7a Android TV boxes). The NEON fast path is aarch64-guarded;
            // other ABIs fall back to the scalar decoder.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (prop("RELEASE_STORE_FILE") != null) {
            create("release") {
                storeFile = file(prop("RELEASE_STORE_FILE")!!)
                storePassword = prop("RELEASE_STORE_PASSWORD")!!
                keyAlias = prop("RELEASE_KEY_ALIAS")!!
                keyPassword = prop("RELEASE_KEY_PASSWORD")!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.leanback)
    implementation(libs.jcifs.ng)

    // decent-player userspace USB audio driver (vendored in third_party/)
    implementation(project(":decent-usb-audio-driver"))
    implementation(project(":decent-usb-audio-wrapper-media3"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// Defensive version pin: keep the whole media3 stack on 1.5.1. The vendored
// decent-player wrapper also builds against 1.5.1, but pinning here guarantees
// no transitive path (a future wrapper bump or another media3 artifact) silently
// upgrades ExoPlayer/session/common out from under the AudioSink APIs the app
// relies on (verified against 1.5.1).
configurations.configureEach {
    resolutionStrategy {
        force(
            "androidx.media3:media3-exoplayer:1.5.1",
            "androidx.media3:media3-common:1.5.1",
            "androidx.media3:media3-session:1.5.1"
        )
    }
}
