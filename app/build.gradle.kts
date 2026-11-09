import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.floatstudy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.floatstudy"
        setProperty("archivesBaseName", "flooww")
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "12"
        // alamat Firebase Realtime Database (data trial) dan berkas info pembaruan; bisa diisi lewat env CI
        buildConfigField("String", "TRIAL_DB_URL", "\"" + (System.getenv("FLOOWW_DB_URL") ?: "") + "\"")
        buildConfigField("String", "UPDATE_URL", "\"" + (System.getenv("FLOOWW_UPDATE_URL") ?: "") + "\"")
        // hanya arm64 (hampir semua HP sejak 2017): library ML Kit native tidak digandakan 4 arsitektur
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Tanda tangan tetap supaya APK baru bisa memperbarui yang lama (tanpa copot pasang).
    // Bawaan: app/flooww.keystore. Untuk key sendiri, isi env FLOOWW_KEYSTORE_B64 / FLOOWW_STORE_PASSWORD /
    // FLOOWW_KEY_ALIAS / FLOOWW_KEY_PASSWORD (mis. dari GitHub secrets) dan key bawaan tidak dipakai.
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
                storeFile = file("flooww.keystore")
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
    packaging {
        // library native dikompres di dalam APK (unduhan lebih kecil)
        jniLibs { useLegacyPackaging = true }
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
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:translate:17.0.3")
}
