plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autoglm.assistant.provider"
    compileSdk = 34

    buildFeatures {
        aidl = true  // HARD: 必须启用 AIDL 支持才能编译 .aidl 文件
    }

    defaultConfig {
        // HARD: 包名必须与主应用中的 PROVIDER_PACKAGE_NAME 一致
        applicationId = "com.autoglm.assistant.provider"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        // HARD: versionName 必须与主应用 assets/accessibility_version.txt 一致
        versionName = "1.0.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}

// 编译完成后自动复制 APK 到主应用的 assets 目录
afterEvaluate {
    tasks.register<Copy>("copyApkToAssets") {
        from("build/outputs/apk/release")
        into("../app/src/main/assets")
        include("*.apk")
        rename { "accessibility_provider.apk" }
        
        doLast {
            println("✅ APK 已复制到 app/src/main/assets/accessibility_provider.apk")
        }
    }
    
    // 自动在 assembleRelease 后执行复制
    tasks.named("assembleRelease") {
        finalizedBy("copyApkToAssets")
    }
}
