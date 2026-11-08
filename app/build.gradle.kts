plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.naptimegames.goquestwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.naptimegames.goquestwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so `assembleRelease` produces an installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; use the real one for unit tests.
    testImplementation("org.json:json:20240303")
}
