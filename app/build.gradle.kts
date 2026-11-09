plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER so every build gets a higher version (used by the in-app updater).
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.gamechanger.shield"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gamechanger.shield"
        minSdk = 30 // Android 11: needed for AccessibilityService.takeScreenshot
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Real phones only (keeps the APK small).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "UPDATE_REPO", "\"elroitouati/game-changer\"")
    }

    // One fixed key so updates install over the previous version.
    // The key is NOT in the repo: CI writes it from the SIGNING_KEYSTORE_BASE64 secret.
    val keystorePath = System.getenv("SIGNING_KEYSTORE") ?: "keystore/shield.jks"
    val keystoreFile = rootProject.file(keystorePath)
    val signingPassword = System.getenv("SIGNING_PASSWORD")
    val hasKey = keystoreFile.exists() && signingPassword != null

    signingConfigs {
        if (hasKey) create("shield") {
            storeFile = keystoreFile
            storePassword = signingPassword
            keyAlias = "shield"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKey) signingConfigs.getByName("shield") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += "onnx" }

    lint { checkReleaseBuilds = false }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric downloads the Android runtime at test time; use Google's Maven Central mirror.
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
