plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20230706")
}

android {
    namespace = "com.bekhruz.vpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bekhruz.vpn"
        minSdk = 26
        targetSdk = 35
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
