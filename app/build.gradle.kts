import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

// signing settings live in local.properties (gitignored), never in the repo
val localProps = Properties().also { p -> rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }

android {
    namespace = "io.github.revtale"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.revtale"
        minSdk = 33
        targetSdk = 35
        versionCode = 52
        versionName = "1.0.0"
    }
    // release signing: keystore and password live outside the repo, named in local.properties (gitignored)
    val releaseStore = localProps.getProperty("releaseStoreFile")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = localProps.getProperty("releaseStorePassword")
            keyAlias = localProps.getProperty("releaseKeyAlias")
            keyPassword = localProps.getProperty("releaseStorePassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
    // org.json in unit tests (the android.jar copy is a stub)
    testImplementation("org.json:json:20240303")
}
