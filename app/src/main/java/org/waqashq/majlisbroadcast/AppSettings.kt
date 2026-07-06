package org.waqashq.majlisbroadcast

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Phase 8: runtime-editable server connection + audio settings, entered on
 * the Settings screen and remembered on the phone from then on -- replacing
 * the old build-time-only secrets.properties/BuildConfig values as the
 * source of truth. Stored in an encrypted SharedPreferences file (AndroidX
 * Security, Android Keystore-backed) since the AzuraCast source password
 * lives here now.
 *
 * On first run (nothing saved yet), this seeds itself once from whatever
 * was baked in at build time from secrets.properties, so upgrading from an
 * older build doesn't lose a working configuration. From that point on the
 * Settings screen is the only source of truth; secrets.properties is no
 * longer read at runtime (build.gradle.kts's copies remain only as the
 * one-time seed for a fresh install).
 */
object AppSettings {
    private const val PREFS_NAME = "majlis_secure_settings"
    private const val KEY_SEEDED = "seeded_from_build_config"
    private const val KEY_HOST = "server_host"
    private const val KEY_PORT = "server_port"
    private const val KEY_MOUNT = "server_mount"
    private const val KEY_USERNAME = "server_username"
    private const val KEY_PASSWORD = "server_password"
    private const val KEY_SAMPLE_RATE = "audio_sample_rate"
    private const val KEY_BIT_RATE = "audio_bit_rate"

    const val DEFAULT_SAMPLE_RATE = 44_100
    const val DEFAULT_BIT_RATE_BPS = 64_000

    val SAMPLE_RATE_OPTIONS = intArrayOf(8_000, 11_025, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)
    val BIT_RATE_OPTIONS_BPS = intArrayOf(64_000, 96_000, 128_000, 160_000, 192_000, 224_000, 256_000)

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val built = buildPrefs(context.applicationContext)
            seedFromBuildConfigIfNeeded(built)
            prefs = built
            return built
        }
    }

    private fun buildPrefs(context: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun seedFromBuildConfigIfNeeded(p: SharedPreferences) {
        if (p.getBoolean(KEY_SEEDED, false)) return
        p.edit()
            .putString(KEY_HOST, BuildConfig.AZURACAST_HOST)
            .putInt(KEY_PORT, BuildConfig.AZURACAST_PORT)
            .putString(KEY_MOUNT, "")
            .putString(KEY_USERNAME, BuildConfig.AZURACAST_USERNAME)
            .putString(KEY_PASSWORD, BuildConfig.AZURACAST_PASSWORD)
            .putInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
            .putInt(KEY_BIT_RATE, DEFAULT_BIT_RATE_BPS)
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    fun host(context: Context): String = prefs(context).getString(KEY_HOST, "") ?: ""
    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, 0)
    fun mount(context: Context): String = prefs(context).getString(KEY_MOUNT, "") ?: ""
    fun username(context: Context): String = prefs(context).getString(KEY_USERNAME, "") ?: ""
    fun password(context: Context): String = prefs(context).getString(KEY_PASSWORD, "") ?: ""
    fun sampleRate(context: Context): Int = prefs(context).getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
    fun bitRateBps(context: Context): Int = prefs(context).getInt(KEY_BIT_RATE, DEFAULT_BIT_RATE_BPS)

    /** The web-panel base URL for the now-playing API, derived from the current host (Phase 7 behavior, preserved). */
    fun apiBaseUrl(context: Context): String = "https://" + host(context)

    fun isConfigured(context: Context): Boolean = host(context).isNotBlank() && port(context) != 0

    fun save(
        context: Context,
        host: String,
        port: Int,
        mount: String,
        username: String,
        password: String,
        sampleRate: Int,
        bitRateBps: Int
    ) {
        // Defensive: someone used to typing a full URL might paste
        // "https://host" into what's really just a bare hostname field for
        // the raw source socket.
        val cleanHost = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        prefs(context).edit()
            .putString(KEY_HOST, cleanHost)
            .putInt(KEY_PORT, port)
            .putString(KEY_MOUNT, mount.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, password)
            .putInt(KEY_SAMPLE_RATE, sampleRate)
            .putInt(KEY_BIT_RATE, bitRateBps)
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }
}
