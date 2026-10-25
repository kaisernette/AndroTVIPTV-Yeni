plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.androtviptv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.androtviptv"
        minSdk = 23
        targetSdk = 28
        versionCode = 2
        versionName = "2.1"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
