import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.veil.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.veil.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.6"

        // The admin server phones pair with unless told otherwise (pairing links carry their own).
        buildConfigField("String", "DEFAULT_SERVER", "\"https://veil-admin.netlify.app\"")

        // The image model's runtime ships native code; ARM covers real phones.
        // (On other CPUs image filtering switches itself off; everything else works.)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // The image model is memory-mapped from the APK, so it must not be compressed.
    androidResources { noCompress += "tflite" }

    // Test-build signing key. Every build is signed with the same key so a new
    // build installs over the previous one. Replace with a proper upload key
    // (kept out of the repo) before the Play Store submission.
    signingConfigs {
        create("testKey") {
            storeFile = rootProject.file("keystore/veil-test.jks")
            storePassword = "veil-test-2026"
            keyAlias = "veil"
            keyPassword = "veil-test-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("testKey")
        }
        debug {
            signingConfig = signingConfigs.getByName("testKey")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// One third-party dependency, on purpose kept to this: the TensorFlow Lite
// runtime for the on-device image model (Stage 4). Everything else is the
// Android framework and the Kotlin stdlib.
dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
