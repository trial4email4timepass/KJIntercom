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
        versionCode = 1
        versionName = "1.0"
    }

    // A fixed debug key (app/debug.keystore, created by the CI workflow or Android Studio)
    // keeps every build installable over the previous one.
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
    }
}

dependencies {
    // Lightweight pure-Java MQTT client used to relay messages over Wi-Fi or mobile data.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
