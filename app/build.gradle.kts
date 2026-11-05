plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// API key tetap: diambil dari env/secret saat build, tidak pernah ditulis ke source.
val anthropicKey: String = (System.getenv("ANTHROPIC_API_KEY")
    ?: (project.findProperty("ANTHROPIC_API_KEY") as String?)
    ?: "")
    .trim().replace("\"", "").replace("\\", "")

android {
    namespace = "com.example.floatstudy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.floatstudy"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
        buildConfigField("String", "API_KEY", "\"$anthropicKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.0")
}
