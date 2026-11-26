plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.khalsa.intercom"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.khalsa.intercom"
        minSdk = 26
        targetSdk = 34
        // Higher than v2.2 (7) so it installs over it and keeps the family code and names.
        versionCode = 8
        versionName = "2.3"
    }

    // Same key that signed v2.2 (app/debug.keystore), so the update installs in place.
    signingConfigs {
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
