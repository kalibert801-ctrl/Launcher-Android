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

    // Подписи. release — собственный ключ AleksOS (им подписаны сборки на GitHub).
    // Файл ключа зашифрован; пароль НЕ в репозитории: на GitHub — секрет ALEKSOS_KEY_PASSWORD,
    // на ПК владельца — файл signing.properties (в .gitignore).
    // debug — отладочный ключ для локальных проверок (не секретный).
    val releasePassword: String? = System.getenv("ALEKSOS_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("signing.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("ALEKSOS_KEY_PASSWORD=") }?.substringAfter("=")?.trim()
    signingConfigs {
        if (releasePassword != null) {
            create("release") {
                storeFile = file("aleksos-release.keystore")
                storePassword = releasePassword
                keyAlias = "aleksos"
                keyPassword = releasePassword
            }
        }
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
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // targetSdk 28 нужен движку; проверка Lint для release иначе останавливает сборку
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
