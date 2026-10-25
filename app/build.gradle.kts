import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 本机签名配置（keystore.properties，不入 git）；缺失时回退标准 debug 签名、release 产出未签名包
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.zhuanggui.pda"
    compileSdk = 35
    // 本机 SDK 已装的是 35.0.1（AGP 默认找 35.0.0；android.builder.sdkDownload=false 不会自动补装）
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.zhuanggui.pda"
        minSdk = 26
        // v1.10.0：targetSdk 29 —— 东集 CRUISE2 等工业 PDA 的 Android 11 ROM 仍用 legacy sdcardfs
        // 挂载 /data/media，只有 targetSdk≤29 的应用才会被加入 sdcard_rw 组、才能在 /sdcard 根目录
        // 建目录写文件（targetSdk 30+ 时 MANAGE_EXTERNAL_STORAGE 也救不了组权限 → 表现为
        // 「有权限却写不进 /sdcard」）。29 还能保留 requestLegacyExternalStorage 的旧行为。
        targetSdk = 29
        versionCode = 37
        versionName = "1.11.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        // targetSdk 29 是现场工业 PDA 的有意选择（legacy 外部存储），不做强制升级提示
        disable += "ExpiredTargetSdkVersion"
        disable += "OldTargetApi"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 内部工具：debug 与 release 同钥匙 → 同一台设备可无缝互装（升级不丢数据；需 run-as 排障时装 debug）
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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
    sourceSets {
        // 迁移测试（MigrationTestHelper）需要读取导出的 schema JSON
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Room schema 导出（数据库结构快照入库，便于后续迁移核对）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 规则库「规则源导入」：解析 .xlsx/.xls（POI）。
    // 注：工单 Excel 导入功能仍保持下架，仅规则库使用 POI。
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.commons:commons-compress:1.26.2")

    // WiFi 同步（v1.8.0）：仅这两个依赖（org.json 系统自带）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // 迁移测试（androidTest：真机跑 8→9 单步 + 5→9 全链校验）
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
