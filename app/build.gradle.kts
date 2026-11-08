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
        // targetSdk 28 — как у Winlator: Android 10+ запрещает приложениям с targetSdk ≥ 29
        // запускать программы из своей папки, а движок Windows (Wine, Box64) работает именно так.
        // Приложение при этом нормально работает на новых Android.
        targetSdk = 28
        // Номер сборки GitHub Actions (build-N) = versionCode. По нему лаунчер
        // понимает, что на GitHub есть версия новее. Локальная сборка получает 1.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"

        // Движок Windows (Wine/Box64) работает только на ARM64 — как у всех современных телефонов.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Нативная часть движка: proot и функции X-сервера (app/src/main/cpp)
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // .so-файлы должны лежать распакованными на диске: proot запускается как программа
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
