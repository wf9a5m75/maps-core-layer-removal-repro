plugins {
    // AGP 9 brings Kotlin support with it; no separate Kotlin plugin.
    id("com.android.application")
}

android {
    namespace = "com.example.ommstall"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.ommstall"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.openmobilemaps:mapscore:4.0.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
