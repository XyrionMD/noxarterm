plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    api(project(":terminal-emulator"))
    testImplementation(libs.junit)
}
