plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.leptos.deviceconnector.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.leptos.deviceconnector.client"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    // No external Android dependencies used: only platform APIs and Kotlin standard library.
}
