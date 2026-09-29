// DESIGN INTENT: Build the minSdk-34 Sidecar app with deterministic local recipe OCR available
// immediately after install; dependency changes must still pass lint and assembleDebug.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.gatorforge.sidecar.trackb"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gatorforge.sidecar.trackb"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.gatorforge.sidecar.trackb.SidecarRenderChecks"
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        getByName("debug") {
            enableV4Signing = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// AGP 9.1.1 registers the project-level Kotlin extension itself.
// The standalone org.jetbrains.kotlin.android plugin must NOT be applied
// (it conflicts: "Cannot add extension with name 'kotlin'").
// jvmTarget 17 matches compileOptions above to avoid "Inconsistent JVM-target compatibility".
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Bundled so recipe-photo OCR works immediately without a first-run model download.
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
