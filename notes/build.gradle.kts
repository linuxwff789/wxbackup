plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
}

import java.io.File
import java.util.Base64

android {
    namespace = "com.nous.wxnotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nous.wxnotes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val ciKeystoreB64 = System.getenv("KEYSTORE_BASE64")
            if (ciKeystoreB64 != null) {
                val tmpFile = File.createTempFile("release", ".keystore")
                tmpFile.deleteOnExit()
                tmpFile.writeBytes(Base64.getDecoder().decode(ciKeystoreB64))
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        // libsilkdec.so 是可执行文件，需要解压到 nativeLibraryDir 才能 exec
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libsilkdec.so"
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // WebDAV 备份需要 PROPFIND/MKCOL，HttpURLConnection 不允许这些方法
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
