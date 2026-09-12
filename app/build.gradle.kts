plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.coldboot.papertrail"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coldboot.papertrail"
        minSdk = 27   // GenieX 0.4.0 requires 27; loaner is Android 16 so no practical cost
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // GenieX ships arm64-v8a only; the loaner is arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        // DEBUGGABLE IS LOAD-BEARING: hot reload routes via /data/local/tmp + run-as,
        // which only works on a debuggable build. See SPIKE-FINDINGS.md §5 Edge 1.
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // CameraX — capture path (stub wiring for now, proves the path exists)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // ML Kit OCR — receipt path. Offline resolve of this is the gate.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // GenieX — on-device inference (NPU via qairt, llama_cpp fallback).
    // 0.4.0 is what is actually in the offline cache; docs say 0.3.1.
    implementation("com.qualcomm.qti:geniex-android:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
