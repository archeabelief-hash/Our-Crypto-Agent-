plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.cryptoai.overlay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cryptoai.overlay"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
