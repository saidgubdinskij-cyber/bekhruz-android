plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
}

android {
    namespace = "com.bekhruz.vpn"
    compileSdk = 36

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
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            pickFirsts += "**/libwg-go.so"
        }
    }
}
