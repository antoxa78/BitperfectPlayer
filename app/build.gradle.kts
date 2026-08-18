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
        versionCode = 36
        versionName = "2.7.1"

        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
