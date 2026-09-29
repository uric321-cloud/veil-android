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
        versionCode = 1
        versionName = "0.1.0"
    }

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

// No third-party dependencies on purpose: the whole app is Android framework +
// Kotlin stdlib, which keeps the first builds simple and reviewable.
dependencies {
}
