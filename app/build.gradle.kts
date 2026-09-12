val EXCLUDED_LIBS = setOf(
            // Offline QNN graph compiler - only needed to BUILD a .bin/.dlc,
            // never to run one. 87.7MB, the single largest file in the APK.
            "lib/*/libQnnHtpPrepare.so",
            // Chipset validation tooling, not an inference dependency.
            "lib/*/libPlatformValidatorShared.so",
            // Older Hexagon generations. This device is V81.
            "lib/*/libQnnHtpV79*.so",
            "lib/*/libQnnHtpV73*.so",
            "lib/*/libQnnHtpV75*.so",
            "lib/*/libQnnNetRunDirectV79*.so",
            "lib/*/libggml-htp-v73.so",
            "lib/*/libggml-htp-v75.so",
            "lib/*/libggml-htp-v79.so",
            // Profiling/trace readers - developer tooling.
            "lib/*/libQnnHtpOptraceProfilingReader.so",
            "lib/*/libQnnChrometraceProfilingReader.so",
            "lib/*/libQnnHtpProfilingReader.so",
            // HTA is a superseded accelerator path; we target HTP.
            "lib/*/libQnnHta*.so",
            "lib/*/libhta_hexagon_runtime_*.so",
            "lib/*/libCalculator_skel.so",
            "lib/*/libcalculator.so"
)

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
        // GenieX dlopen()s its plugin .so files by name at runtime, so they must
        // exist as real files in the native lib dir rather than staying compressed
        // inside the APK. Without this the SDK reports
        // "Cannot find libgeniex_plugin_llama_cpp.so".
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
            excludes += EXCLUDED_LIBS
        }

        /* Size: the GenieX AAR ships every Hexagon generation and the offline
         * model-compilation toolchain. The loaner is Hexagon V81 (confirmed at
         * runtime: only libggml-htp-v81.so is dlopen'd), and we load prebuilt
         * GGUF rather than compiling graphs on device, so the rest is dead
         * weight. Nothing here touches model weights or accuracy.
         *
         * Re-check this list if qairt/NPU is wired or a different handset is
         * used - a missing Skel/Stub for the actual chip is a silent failure. */
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

    // ML Kit barcode — UPI QR scanning. Same on-device family as the OCR above,
    // bundled model, no Play Services download at runtime.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // GenieX — on-device inference (NPU via qairt, llama_cpp fallback).
    // 0.4.0 is what is actually in the offline cache; docs say 0.3.1.
    implementation("com.qualcomm.qti:geniex-android:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
