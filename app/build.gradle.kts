plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.mangalens"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mangalens"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Every modern tablet is arm64; dropping the other ABIs takes the
            // APK from ~114 MB to a fraction. Add "armeabi-v7a" for pre-2016 devices.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Committed keystore so sideloaded updates always match signatures.
        // This app is for personal sideloading; the keystore protects nothing.
        getByName("debug") {
            storeFile = rootProject.file("signing/debug.keystore")
            storePassword = "mangalens"
            keyAlias = "mangalens"
            keyPassword = "mangalens"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // On-device OCR for the three CJK scripts (models bundled in the APK)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // Optional fully-offline translation engine
    implementation("com.google.mlkit:translate:17.0.3")
}
