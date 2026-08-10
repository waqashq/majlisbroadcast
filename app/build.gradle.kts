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

// Phase 7: the AzuraCast now-playing API lives on the station's normal web
// front end (HTTPS, standard port), which is a completely different
// service from the raw source-ingest socket on AZURACAST_PORT (see
// PROTOCOL-NOTES.md -- that port is plain HTTP, no TLS, source protocol
// only). Defaults to "https://" + the same host, since that's correct for
// most single-domain AzuraCast setups; override with an optional
// azuracast.api_base_url key in secrets.properties if the web panel lives
// somewhere else.
val defaultApiBaseUrl = "https://${secret("azuracast.host")}"
val apiBaseUrl = secret("azuracast.api_base_url", defaultApiBaseUrl)

// Phase 7: release signing. Real keystore path/passwords are never
// committed -- read at build time from a local, git-ignored
// keystore.properties (same pattern as secrets.properties). Until that
// file exists, the release build type is simply left unsigned rather than
// failing the build, so debug work is never blocked on having a keystore
// set up.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "org.waqashq.majlisbroadcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.waqashq.majlisbroadcast"
        minSdk = 26
        targetSdk = 34
        versionCode = 36
        versionName = "p9.6"

        buildConfigField("String", "AZURACAST_HOST", "\"${secret("azuracast.host")}\"")
        buildConfigField("int", "AZURACAST_PORT", secret("azuracast.port", "0"))
        buildConfigField("String", "AZURACAST_USERNAME", "\"${secret("azuracast.username")}\"")
        buildConfigField("String", "AZURACAST_PASSWORD", "\"${secret("azuracast.password")}\"")
        buildConfigField("String", "AZURACAST_API_BASE_URL", "\"$apiBaseUrl\"")
        // Optional -- see README. AzuraCast's all-stations /api/nowplaying
        // endpoint only lists stations flagged public and has a known bug
        // returning an empty array for unauthenticated requests; the
        // per-station endpoint (/api/nowplaying/{shortcode}) is reliable
        // regardless, so it's used instead whenever this is set.
        buildConfigField("String", "AZURACAST_STATION_SHORTCODE", "\"${secret("azuracast.station_shortcode")}\"")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", ""))
                storePassword = keystoreProps.getProperty("storePassword", "")
                keyAlias = keystoreProps.getProperty("keyAlias", "")
                keyPassword = keystoreProps.getProperty("keyPassword", "")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    // Phase 8: encrypted on-device storage for the runtime-editable server
    // settings (source password lives here now instead of only at build
    // time). Pinned to the 1.0.0 stable release rather than the 1.1.0-alpha
    // line, which has since deprecated EncryptedSharedPreferences in favor
    // of a DataStore+Tink rewrite -- overkill for this single-user app.
    implementation("androidx.security:security-crypto:1.0.0")
}
