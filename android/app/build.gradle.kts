import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 读取 keystore 配置（gradle.properties 或环境变量可覆盖）
val keystoreProps = Properties().apply {
    val f = rootProject.file("gradle.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String): String? =
    System.getenv(name.replace('.', '_').uppercase()) ?: keystoreProps.getProperty(name)

android {
    namespace = "com.codex.mobile"
    compileSdk = 35
    // AGP 8.7 默认要求 build-tools 34.0.0；本工程统一用 35.0.0，
    // 避免 AGP 每次再去自动下载一套旧 build-tools。
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.codex.mobile"
        minSdk = 24
        // targetSdk 28 allows executing binaries from app data directory.
        // Android 10+ (targetSdk 29+) enforces W^X which blocks this via SELinux.
        // Termux (F-Droid) uses the same approach.
        targetSdk = 28
        versionCode = 14
        versionName = "0.6.1"
    }

    signingConfigs {
        create("release") {
            val sf = prop("MOBILECODE_STORE_FILE")
            if (sf != null && rootProject.file(sf).exists()) {
                this.storeFile = rootProject.file(sf)
                storePassword = prop("MOBILECODE_STORE_PASSWORD")
                keyAlias = prop("MOBILECODE_KEY_ALIAS")
                keyPassword = prop("MOBILECODE_KEY_PASSWORD")
                // 签名方案：v2（Android 7.0+ 官方校验，minSdk≥24 的必经之路）
                // + v3（支持后续密钥轮换，避免换证书后老用户无法升级）。
                // v1（JAR 签名）在 minSdk>=24 时被 AGP 强制关闭——所有可安装
                // 设备均支持 v2，v1 无实际意义，声明保留仅为语义完整。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        // debug 构建也统一用 release keystore：
        // 保证 debug/release 可互相覆盖安装，且换构建机不再出现
        // "签名不一致导致无法升级"（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Don't compress the pre-compressed runtime image shards (gzip already applied);
    // raw storage avoids double-decompress CPU and is slightly smaller.
    androidResources {
        noCompress += listOf("bin", "zip", "tar.gz")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
}
