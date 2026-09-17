import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- 正式版签名（just release 用）------------------------------------------------
// keystore.properties 在仓库根，被 .gitignore 覆盖；裸 clone（没有这个文件的机器）
// 依然能编 debug、跑测试，只是出不了可分发的 release 包。
// 密钥备份在仓库外（按日期分目录），具体位置见本地运维记录，不进版本库。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.ted.shouhuan"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ted.shouhuan"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.1"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 刻意**不**回退 signingConfigs.debug：debug key 是每台机器随机生成的，
            // 出的包能直装、看着一切正常，换台机器再发一版就变成「装不上更新」
            // （INSTALL_FAILED_UPDATE_INCOMPATIBLE）—— 那是最难查的一种假象。
            // 没有 keystore.properties 时这里什么都不设，assembleRelease 会产 unsigned 包，
            // tools/just_release.ps1 会在自检里拦下来。
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.health.connect.client)
    implementation(libs.kotlinx.coroutines.android)

    // 配对页「扫码填入」：相机预览 + 二维码解码
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    // CameraX 的 ProcessCameraProvider 暴露 guava ListenableFuture（AGP 禁止空接口 jar，
    // 必须带完整 guava）
    implementation(libs.guava)

    debugImplementation(libs.androidx.ui.tooling)
}
