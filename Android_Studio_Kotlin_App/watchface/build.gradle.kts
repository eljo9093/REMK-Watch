plugins {
    id("com.android.application")
}

android {
    namespace = "eu.remk.coaching.watchface"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.remk.coaching.watchface"
        minSdk = 33
        targetSdk = 34
        versionCode = 331
        versionName = "33.1-iron-telemetry"
    }

    signingConfigs {
        create("remkFixed") {
            storeFile = rootProject.file("remk_watch_fixed.jks")
            storePassword = "remkwatch2026"
            keyAlias = "remkwatch"
            keyPassword = "remkwatch2026"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("remkFixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("remkFixed")
        }
    }
}
