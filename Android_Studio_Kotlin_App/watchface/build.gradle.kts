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
        versionCode = 330
        versionName = "33.0-iron-telemetry"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
}
