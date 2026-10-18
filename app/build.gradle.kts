import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mangaromaji"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mangaromaji.preview"
        ndk { abiFilters += "arm64-v8a" }
        minSdk = 26
        targetSdk = 34
        versionCode = 100000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)*10 + (System.getenv("GITHUB_RUN_ATTEMPT")?.toIntOrNull() ?: 0)
        versionName = "1.1.0-preview-"+(System.getenv("GITHUB_RUN_NUMBER") ?: "local")+"."+(System.getenv("GITHUB_RUN_ATTEMPT") ?: "0")+"-"+(System.getenv("GITHUB_SHA")?.take(7) ?: "dev")
    }
    buildFeatures { buildConfig = true }
    // Public development-only signing key, never a production signing identity.
    signingConfigs.getByName("debug") {
        val f=rootProject.layout.buildDirectory.file("preview-debug.keystore").get().asFile
        f.parentFile.mkdirs();f.writeBytes(Base64.getMimeDecoder().decode(rootProject.file("tools/preview-debug-keystore.b64").readText()))
        storeFile=f;storePassword="android";keyAlias="androiddebugkey";keyPassword="android"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/*.md", "META-INF/*.txt")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // On-device Japanese OCR
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    // Japanese dictionary: kanji -> readings
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
}
