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

// 侧载分发的固定签名密钥。
//
// 原来 release 用的是 signingConfigs.getByName("debug")，而 debug keystore 是 Gradle
// 首次构建时随机生成到 ~/.android/debug.keystore 的。CI runner 每次都是全新机器，
// 于是每轮 CI 都换一把新密钥 —— 实测同一个提交 fc17252 连续构建两次，证书摘要分别是
// b4456d77… 和 7426bf8f…，而两个 APK 的大小完全一样（46240722 字节），只有签名不同。
//
// Android 要求覆盖安装时签名一致，签名一变系统直接报"应用未安装"，
// 用户只能先卸载（应用数据全丢）再装新版。下载几轮 APK 一定会撞上。
//
// 所以改成用仓库内自带的固定密钥签名：各次构建永远一致，也不依赖任何 Secret。
// 这是**侧载测试用**的密钥，口令写在下面、本身就是公开的，请勿用于上架；
// 真要发应用商店，换一把自己的密钥，并且不要把私钥提交进仓库。
val sideloadKeystore = rootProject.file("keystore/sideload.jks")

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

    // 密钥文件在仓库里就一定存在；万一被删掉，退回 debug 签名，
    // 让构建先跑通（签名会变，但至少不会因为缺文件直接失败）。
    signingConfigs {
        if (sideloadKeystore.exists()) {
            create("sideload") {
                storeFile = sideloadKeystore
                storePassword = "echosight"
                keyAlias = "echosight"
                keyPassword = "echosight"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 侧载分发：用仓库内固定的密钥签名，保证每轮 CI 产物签名一致、可直接覆盖安装
            signingConfig = if (sideloadKeystore.exists())
                signingConfigs.getByName("sideload")
            else
                signingConfigs.getByName("debug")
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
    // 本项目的布局只有 FrameLayout / LinearLayout，代码里没有一处用到 ConstraintLayout，
    // 不需要自己显式声明 2.2.0。material 会传递引入它需要的 2.0.1，APK 因此小约 200KB。
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
