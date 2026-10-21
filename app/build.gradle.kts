plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.screenshield"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.screenshield"
        minSdk = 31
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.$runNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            // Personal signing key kept in this private repo so every build
            // can be installed as an update over the previous one.
            storeFile = file("release.p12")
            storeType = "pkcs12"
            storePassword = "screenshield"
            keyAlias = "screenshield"
            keyPassword = "screenshield"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    androidResources { noCompress += "onnx" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
