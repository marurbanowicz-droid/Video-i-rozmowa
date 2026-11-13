import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ver = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "pl.trener.nawodzie"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.trener.nawodzie"
        minSdk = 26
        targetSdk = 34
        versionCode = ver.getProperty("versionCode").toInt()
        versionName = ver.getProperty("versionName")
        buildConfigField("String", "GH_REPO", "\"marurbanowicz-droid/Video-i-rozmowa\"")
        buildConfigField("String", "WEB_URL", "\"https://marurbanowicz-droid.github.io/Video-i-rozmowa/\"")
    }

    val ksFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null) create("release") {
            storeFile = file(ksFile)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "trener"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // bez sekretów (np. gałęzie testowe) – podpis testowy
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    val camerax = "1.4.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("io.getstream:stream-webrtc-android:1.1.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
}
