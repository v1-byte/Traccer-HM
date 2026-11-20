plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Kunci rilis memakai env/gradle.properties yang sama dengan APK user. Tanpa kunci, release memakai kunci debug (hanya uji).
fun secret(n: String): String? = System.getenv(n) ?: providers.gradleProperty(n).orNull

android {
    namespace = "com.frostbyte.hmtracker.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.frostbyte.hmtracker.admin"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Alamat awal server lisensi (HTTPS). Boleh kosong: bisa diisi di layar masuk dan diingat.
        buildConfigField("String", "LICENSE_API_URL", "\"${secret("HM_LICENSE_API_URL") ?: ""}\"")
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
    lint { abortOnError = false }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
}
