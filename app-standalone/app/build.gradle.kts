plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.playground.phonecontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.playground.phonecontrol"
        minSdk = 30 // AccessibilityService.takeScreenshot
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
