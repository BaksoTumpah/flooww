import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.floowwadmin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.floowwadmin"
        setProperty("archivesBaseName", "flow-admin")
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2"
        // ID klien web OAuth (Google Cloud). Kosong = login Google dilewati dan hanya PIN yang dipakai.
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"" + (System.getenv("FLOOWW_ADMIN_GOOGLE_CLIENT_ID") ?: "") + "\"")
    }

    // Tanda tangan sama dengan flooww (lihat app/build.gradle.kts).
    signingConfigs {
        create("flooww") {
            val b64 = System.getenv("FLOOWW_KEYSTORE_B64")?.trim().orEmpty()
            if (b64.isNotEmpty()) {
                val f = layout.buildDirectory.file("flooww-ci.keystore").get().asFile
                f.parentFile.mkdirs()
                f.writeBytes(Base64.getMimeDecoder().decode(b64))
                storeFile = f
                storePassword = System.getenv("FLOOWW_STORE_PASSWORD")
                keyAlias = System.getenv("FLOOWW_KEY_ALIAS") ?: "flooww"
                keyPassword = System.getenv("FLOOWW_KEY_PASSWORD") ?: System.getenv("FLOOWW_STORE_PASSWORD")
            } else {
                storeFile = file("../app/flooww.keystore")
                storePassword = "flooww-sign"
                keyAlias = "flooww"
                keyPassword = "flooww-sign"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("flooww")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("flooww")
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
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
