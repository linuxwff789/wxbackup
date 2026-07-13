plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.io.File
import java.util.Base64

android {
    namespace = "com.nous.wxhook.xposed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nous.wxhook.xposed"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val ciKeystoreB64 = System.getenv("KEYSTORE_BASE64")
            if (ciKeystoreB64 != null) {
                val tmpFile = java.io.File.createTempFile("release", ".keystore")
                tmpFile.deleteOnExit()
                tmpFile.writeBytes(java.util.Base64.getDecoder().decode(ciKeystoreB64))
                storeFile = tmpFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            } else {
                storeFile = file("../release.keystore")
                storePassword = "wxhook123"
                keyAlias = "wxhook"
                keyPassword = "wxhook123"
            }
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    // Xposed API (compileOnly — provided at runtime by LSPosed)
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")

    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
