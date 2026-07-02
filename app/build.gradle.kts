import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Real AzuraCast connection details never live in this file or anywhere
// tracked -- they're read at build time from a local, git-ignored
// secrets.properties in the project root. See README.md / secrets.properties
// for the placeholder template. Missing keys just become empty strings; the
// app checks for that at runtime and reports it clearly instead of trying to
// connect with blank credentials.
val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) {
        FileInputStream(secretsFile).use { load(it) }
    }
}
fun secret(key: String, default: String = "") = secrets.getProperty(key, default)

android {
    namespace = "org.waqashq.majlisbroadcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.waqashq.majlisbroadcast"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "p6.2"

        buildConfigField("String", "AZURACAST_HOST", "\"${secret("azuracast.host")}\"")
        buildConfigField("int", "AZURACAST_PORT", secret("azuracast.port", "0"))
        buildConfigField("String", "AZURACAST_USERNAME", "\"${secret("azuracast.username")}\"")
        buildConfigField("String", "AZURACAST_PASSWORD", "\"${secret("azuracast.password")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
}
