plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libwg-go.so"
        }
    }
}
