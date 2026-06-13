plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.droneswarm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.droneswarm"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Ye line aapke testing app ka package name badal degi
            applicationIdSuffix = ".testing"
            // Ye line app ke naam ke aage [Testing] ya -debug jod degi takki pehchan sako
            versionNameSuffix = "-testing"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        // ✅ YOLO TFLite support ke liye enable kiya
        mlModelBinding = true
    }

    // 🔥 16KB PAGE SIZE FIX (As you provided)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // ✅ TFLite model compress na ho, isliye ye zaroori hai
    aaptOptions {
        noCompress("tflite")
    }
}

dependencies {
    // ------------------ Compose (As provided) ------------------
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")
    debugImplementation("androidx.compose.ui:ui-tooling:1.5.4")

    // ------------------ CameraX (As provided) ------------------
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")

    //--------------For WebRTC----------------------------------
   // implementation("io.getstream:stream-webrtc-android:1.2.3")
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")

    // ------------------ Location & Nearby (As provided) ------------------
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("com.google.android.gms:play-services-nearby:19.0.0")

    // ------------------ YOLOv8 / TensorFlow Lite (Added New) ------------------
    // Ye zaroori hain YOLOv8n model run karne ke liye
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0") // Fast processing ke liye
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.4")

    // ------------------ ML Kit (Cleaning duplicates) ------------------
    // Maine sirf zaroori rakha hai, pose-detection ko remove kar sakte ho agar YOLO use kar rahe ho
    implementation("com.google.mlkit:object-detection:17.0.0")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta3")
}