import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ai.prinim.prinyal"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.prinim.prinyal"
        // Android 10+ — матрица устройств из ТЗ UI §5 (Xiaomi/realme/Samsung A).
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-r1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ключ DeepSeek едет в APK: версия работает без сервера (решение владельца,
        // отступление от PRD §2 п.3). Значение берётся из local.properties и в git
        // не попадает — но в самом APK оно есть, и это надо помнить.
        val deepSeekKey = localProperty("deepseek.key")
        buildConfigField("String", "DEEPSEEK_KEY", "\"$deepSeekKey\"")

        ndk {
            // Только arm64: онсовые библиотеки для x86 и armeabi-v7a — это ~25 МБ
            // в APK, который и так большой из-за весов. Телефоны с Android 10+
            // все arm64.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    androidResources {
        // Веса и так сжаты квантованием: архивирование даёт единицы процентов,
        // а распаковку замедляет заметно.
        noCompress += "onnx"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // В одной JVM живут песочницы Robolectric и ONNX-сессия на 323 МБ —
                // дефолтной кучи на это не хватает.
                it.maxHeapSize = "4g"
                // Тесты с весами forkEvery=1 держат память только на время класса.
                it.forkEvery = 1
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.okhttp)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.onnxruntime.jvm)
}


/** Секреты для сборки живут в local.properties — он не в git. */
fun localProperty(name: String): String {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return ""
    val props = Properties()
    file.inputStream().use(props::load)
    return props.getProperty(name, "")
}
