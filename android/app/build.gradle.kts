import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// API Key 来源（二选一）：
//   1. app/api_key.txt 明文文件（本地构建，已 gitignore）
//   2. 环境变量 SENSEAUDIO_API_KEY（GitHub Actions 从 Secret 注入）
val apiKeyFile = rootProject.file("app/api_key.txt")
val senseAudioKey: String =
    if (apiKeyFile.exists()) apiKeyFile.readText().trim()
    else (System.getenv("SENSEAUDIO_API_KEY") ?: "")

android {
    namespace = "com.echosight.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echosight.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SENSEAUDIO_KEY", "\"$senseAudioKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 侧载分发：用自动生成的 debug 密钥签名，保证 APK 可直接安装
            signingConfig = signingConfigs.getByName("debug")
            // onnxruntime 的原生库按 ABI 各带一份，四个 ABI 合计 70MB+，
            // 其中 x86/x86_64 只有模拟器用得到，真机全是 ARM。
            // 这里只保留 ARM 两个 ABI，包体直接减半；debug 构建不限制，
            // 方便继续在 x86 模拟器里调试。
            ndk {
                abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // CameraX
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // 端侧 YOLO 推理
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // 云端 ASR / TTS
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
