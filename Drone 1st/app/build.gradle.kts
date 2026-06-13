plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.droneswarm.drone1"
    compileSdk = 35 // Latest stable

    defaultConfig {
        applicationId = "com.example.droneswarm.drone1"
        minSdk = 24
        targetSdk = 34 // 36 se 34 kar diya for stability
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

   /* packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }*/
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // CameraX & ML Kit
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta3")

 //WebRTC
    //implementation("io.getstream:stream-webrtc-android:1.2.3")
    implementation("io.github.webrtc-sdk:android:125.6422.06.1")
    // Services
    implementation("com.google.android.gms:play-services-location:21.1.0")
    implementation("com.google.android.gms:play-services-nearby:19.0.0")
}