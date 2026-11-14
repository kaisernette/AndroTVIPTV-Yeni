plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keep local/CI builds working before the owner supplies Firebase's app config.
// Once app/google-services.json is added, the Google Services plugin generates
// the resources required by Firebase Authentication and Google Credential Manager.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.androtviptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.androtviptv"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "3.0"
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.tvprovider:tvprovider:1.1.0")

    // Firebase services for the KaiserTV+ cloud account.
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")

    // Google account sign-in through Android Credential Manager.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
