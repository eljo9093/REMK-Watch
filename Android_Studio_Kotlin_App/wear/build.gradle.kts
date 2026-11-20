plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.remk.coaching.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.remk.coaching.wear"
        minSdk = 26
        targetSdk = 34
        versionCode = 341
        versionName = "34.1-tiles-ring-pro"
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
            signingConfig = signingConfigs.getByName("remkFixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("remkFixed")
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

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear.tiles:tiles:1.2.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("com.google.guava:guava:31.1-android")
}
