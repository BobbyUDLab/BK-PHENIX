plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "ru.bkphenix.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.bkphenix"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.8.2"
        // Отпечаток SHA-256 постоянного release-сертификата: обновление по воздуху принимается только с этой подписью.
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"F7F1B3D79DB8E049F41BDA893A0E8767E5F1E1EC0F37EF937060FD04B0B73463\"")
        // Нативный движок MapLibre: только ARM-телефоны, без x86, чтобы не раздувать APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Release-ключ хранится только у владельца, вне репозитория. Путь и пароль передаются через
    // переменные окружения при сборке (скрипт сборки спрашивает пароль); без них release не подписывается.
    val releaseKeystore = System.getenv("BKPHENIX_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("BKPHENIX_KS_PASS")
                keyAlias = System.getenv("BKPHENIX_KEY_ALIAS") ?: "bkphenix"
                keyPassword = System.getenv("BKPHENIX_KS_PASS")
            }
        }
    }

    buildTypes {
        release {
            // Без минификации: release-сборка до этого не выпускалась, R8 пока не проверялся с MapLibre и Room.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room {
    // Схемы БД в репозитории: по ним пишутся и проверяются миграции.
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.maplibre.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
