plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Kunci rilis dibaca dari environment / gradle.properties. Tanpa kunci, release memakai kunci debug (hanya untuk uji).
fun secret(n: String): String? = System.getenv(n) ?: providers.gradleProperty(n).orNull

android {
    namespace = "com.frostbyte.hmtracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.frostbyte.hmtracker"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.5.1"
        // URL dasar backend OTP (HTTPS). Kosong = mode demo (OTP dibuat di perangkat). Lihat server/otp-server.
        buildConfigField("String", "OTP_API_URL", "\"${secret("HM_OTP_API_URL") ?: ""}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    val ksFile = secret("HM_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ksFile != null) create("release") {
            storeFile = ksFile
            storePassword = secret("HM_KEYSTORE_PASSWORD")
            keyAlias = secret("HM_KEY_ALIAS")
            keyPassword = secret("HM_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ksFile != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        // Laporan HTML/XML selalu dibuat (diunggah oleh CI). abortOnError dimatikan sementara
        // sampai baseline lint pertama dibersihkan; lihat bagian Roadmap di README.
        htmlReport = true
        xmlReport = true
        abortOnError = false
        warningsAsErrors = false
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.livedata.core)   // LiveData di API CameraX (zoomState)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // OCR on-device (detektor angka HM, bekerja tanpa internet)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.tflite)   // model sendiri (digits.tflite), opsional saat dijalankan

    testImplementation(libs.junit)
    testImplementation(libs.org.json)   // org.json asli untuk tes JVM (stub Android mengembalikan nilai kosong)
}
