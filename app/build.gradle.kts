import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * 发布签名配置。仓库里**没有**也**不该有**密钥和口令：
 * 本机在项目根目录放一个 keystore.properties（已在 .gitignore 里），内容形如
 *
 *     storeFile=C:/path/to/release.jks
 *     storePassword=...
 *     keyAlias=...
 *     keyPassword=...
 *
 * 文件不存在时（别人 clone 这个公开仓库的情况）自动退回 debug 签名，
 * 保证 `./gradlew assembleRelease` 照样能跑通，只是装出来的是调试签名版。
 */
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dicar.vehicle"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dicar.vehicle"
        minSdk = 28
        // 需求文档要求 targetSdk 33：避免车机上因新版本行为限制导致安装/前台服务问题
        targetSdk = 33
        versionCode = 5
        versionName = "0.4.1"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // minSdk 28，v1(JAR) 已无必要；v3 默认不开，但只有 v3 支持以后轮换密钥
                enableV2Signing = true
                enableV3Signing = true
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
            // 有 keystore.properties 就用正式签名，否则退回 debug 签名（见文件顶部说明）
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // targetSdk 33 是刻意选择（车机兼容），不需要 Play 商店的过期提示
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // 连接车机本机 adbd（127.0.0.1:5555），以调试身份起辅助进程读写被签名权限保护的车辆数据
    implementation(libs.dadb)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
