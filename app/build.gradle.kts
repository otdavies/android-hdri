plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.hdri"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.otdavies.hdri.preview"
        minSdk = 29
        targetSdk = 35
        versionCode = System.getenv("HDRI_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        create("preview") {
            System.getenv("HDRI_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("HDRI_STORE_PASSWORD")
            keyAlias = System.getenv("HDRI_KEY_ALIAS") ?: "hdri"
            keyPassword = System.getenv("HDRI_KEY_PASSWORD") ?: System.getenv("HDRI_STORE_PASSWORD")
        }
    }
    testBuildType = "release"
    buildTypes {
        release {
            // Keep the tested binary identical to the installable preview, including native APIs.
            isMinifyEnabled = false
            signingConfig = if (System.getenv("HDRI_KEYSTORE") != null) signingConfigs.getByName("preview") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.ar:core:1.54.0")
    implementation("org.opencv:opencv:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
