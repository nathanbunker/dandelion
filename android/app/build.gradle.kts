plugins {
    id("com.android.application")
}

android {
    namespace = "org.dandeliondaily.sundial"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.dandeliondaily.sundial"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "Sundial Dev")
        }
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "Sundial")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        resValues = true
    }
}
