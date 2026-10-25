plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.echolog.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.echolog.app"
        // 架构决策 1：最低 Android 10（API 29），前台服务类型声明最清晰、分区存储边界干净
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

// Room schema 导出目录（架构 5.3：数据库 v1 schema 导出校验）
ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    // AndroidX 基座
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    // Compose（版本对齐以 BOM 为准）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Room（KSP）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore（设置存储）
    implementation(libs.androidx.datastore.preferences)

    // SAF 自选目录文件操作（用户需求 3：录音存储位置复制）
    implementation(libs.androidx.documentfile)

    // WorkManager + Hilt Worker 注入
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    // Jetpack Security（P1 FR-103 本地加密）
    implementation(libs.androidx.security.crypto)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 协程 / 网络（模型下载）
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // 本地二进制制品：sherpa-onnx AAR / llama.cpp jar（放置说明见 app/libs/README.md）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // 单元测试
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // JVM 单测需要真实 org.json 实现（android.jar 中为 Stub；SummaryJsonParser/ModelCatalog 依赖）
    testImplementation(libs.org.json)
    // Mockito 5 默认 inline mock maker，可 mock Kotlin final 类（ModelManager/QwenSummarizer 等 Android 依赖类）
    testImplementation(libs.mockito.core)

    // 仪器测试 / 调试工具
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
