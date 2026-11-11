import java.util.Properties

plugins { id("com.android.application") }

// 签名口令放在 keystore/keystore.properties（不进版本库）
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.xiaoqi.calculator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiaoqi.calculator"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "2.1-test"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 离线环境没有 lint 依赖，release 构建跳过 lint
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Material 3（View 体系）：动态取色、MaterialButton、BottomSheetDialog
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
