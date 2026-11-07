plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aleksos.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aleksos.launcher"
        minSdk = 26
        targetSdk = 35
        // Номер сборки GitHub Actions (build-N) = versionCode. По нему лаунчер
        // понимает, что на GitHub есть версия новее. Локальная сборка получает 1.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
    }

    // Фиксированный ключ подписи: каждая новая сборка ставится поверх старой
    // без удаления приложения. Это отладочный ключ, он не секретный.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug-signing.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
