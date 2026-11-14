package io.github.tgx.android

import android.content.Context
import java.security.SecureRandom

/**
 * Owns the persistent MTProto proxy secret.
 *
 * The secret is generated once per installation and remains stable across
 * normal application/process restarts. A new value is generated only when
 * explicitly requested by the user.
 *
 * The secret is deliberately stored in its own SharedPreferences file so
 * Android Backup rules can exclude only the secret without excluding the
 * rest of the TGX settings.
 */
object SecretManager {

    private const val PREFS_NAME = "tgx_secret"
    private const val KEY_PROXY_SECRET = "proxy_secret_hex"
    private const val SECRET_BYTE_LENGTH = 16
    private const val LEGACY_PREFS_NAME = "tgx_settings"
    private const val MIGRATION_DONE_KEY = "legacy_secret_migration_done"

    // Fingerprint keeps the historical test value out of the release artifact.
    private const val LEGACY_TEST_SECRET_SHA256 =
        "3eb1bd439947eb762998e566ccc2e099c791118b2f40579cc4f7da2b5061b7f9"

    private val SECRET_REGEX = Regex("^[0-9a-fA-F]{32}$")
    private val secureRandom = SecureRandom()

    @Synchronized
    fun getOrCreateSecret(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // One-time migration from the old tgx_settings storage. This is important
        // for users upgrading from the previous build: their current secret must
        // survive the storage split instead of being replaced by a new one.
        if (!prefs.getBoolean(MIGRATION_DONE_KEY, false)) {
            migrateLegacySecret(context, prefs)
        }

        val stored = prefs.getString(KEY_PROXY_SECRET, null)
        if (stored != null && isUsableSecret(stored)) {
            return stored.lowercase()
        }

        return generateAndStore(prefs)
    }

    private fun migrateLegacySecret(
        context: Context,
        newPrefs: android.content.SharedPreferences
    ) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacySecret = legacyPrefs.getString(KEY_PROXY_SECRET, null)

        if (legacySecret != null && isUsableSecret(legacySecret)) {
            check(
                newPrefs.edit()
                    .putString(KEY_PROXY_SECRET, legacySecret.lowercase())
                    .commit()
            ) {
                "Could not migrate MTProto secret"
            }
        }

        // Do not mark migration complete until the old secret is durably removed
        // from the legacy preferences file, which remains eligible for Backup.
        check(legacyPrefs.edit().remove(KEY_PROXY_SECRET).commit()) {
            "Could not remove legacy MTProto secret"
        }
        check(newPrefs.edit().putBoolean(MIGRATION_DONE_KEY, true).commit()) {
            "Could not persist MTProto secret migration state"
        }
    }

    @Synchronized
    fun setSecret(context: Context, secret: String) {
        val normalized = secret.trim().lowercase()
        require(isUsableSecret(normalized)) {
            "Attempted to persist an invalid MTProto secret"
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(
            prefs.edit()
                .putString(KEY_PROXY_SECRET, normalized)
                .commit()
        ) {
            "Could not persist MTProto secret"
        }
    }

    private fun isUsableSecret(secret: String): Boolean {
        if (!SECRET_REGEX.matches(secret)) return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(secret.lowercase().toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return !digest.equals(LEGACY_TEST_SECRET_SHA256, ignoreCase = true)
    }

    private fun generateAndStore(
        prefs: android.content.SharedPreferences
    ): String {
        val bytes = ByteArray(SECRET_BYTE_LENGTH)
        secureRandom.nextBytes(bytes)

        val secret = buildString(bytes.size * 2) {
            for (byte in bytes) {
                append("%02x".format(byte.toInt() and 0xff))
            }
        }

        check(SECRET_REGEX.matches(secret)) {
            "Generated MTProto secret has an invalid format"
        }
        check(isUsableSecret(secret))

        check(
            prefs.edit()
                .putString(KEY_PROXY_SECRET, secret)
                .commit()
        ) {
            "Could not persist MTProto secret"
        }

        return secret
    }
}
