package com.raw2dng.rrc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * The paired RapidRawCloud library: bucket coordinates in plain preferences
 * (nothing secret), the access/secret key pair in an
 * [EncryptedSharedPreferences] backed by the Android Keystore. Mirrors
 * RapidRaw's own split (`settings.json` vs. credential store).
 */
data class RrcConfig(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val forcePathStyle: Boolean = true,
    /** The pairing service this config came from, when it came from one. */
    val pairingService: String? = null,
    /** Who paired (OIDC preferred_username), for the source label. */
    val username: String? = null
) {
    val host: String get() = runCatching { java.net.URL(endpoint).host }.getOrDefault(endpoint)

    companion object {
        private const val TAG = "RrcConfig"
        private const val PREFS = "raw2dng_rrc"
        private const val SECRETS = "raw2dng_rrc_secrets"

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun secrets(context: Context): SharedPreferences? = try {
            val app = context.applicationContext
            val keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                SECRETS, keyAlias, app,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Keystore-backed preferences unavailable", e)
            null
        }

        fun load(context: Context): RrcConfig? {
            val p = prefs(context)
            val endpoint = p.getString("endpoint", null) ?: return null
            val bucket = p.getString("bucket", null) ?: return null
            if (endpoint.isBlank() || bucket.isBlank()) return null
            return RrcConfig(
                endpoint = endpoint, bucket = bucket,
                region = p.getString("region", "us-east-1") ?: "us-east-1",
                forcePathStyle = p.getBoolean("path_style", true),
                pairingService = p.getString("pairing_service", null),
                username = p.getString("username", null)
            )
        }

        fun save(context: Context, cfg: RrcConfig, creds: SigV4.Credentials) {
            prefs(context).edit()
                .putString("endpoint", cfg.endpoint).putString("bucket", cfg.bucket).putString("region", cfg.region)
                .putBoolean("path_style", cfg.forcePathStyle)
                .putString("pairing_service", cfg.pairingService).putString("username", cfg.username)
                .apply()
            val s = secrets(context) ?: throw IllegalStateException("Cannot store credentials: Android Keystore unavailable")
            s.edit().putString("access_key", creds.accessKey).putString("secret_key", creds.secretKey).apply()
        }

        fun credentials(context: Context): SigV4.Credentials? {
            val s = secrets(context) ?: return null
            val a = s.getString("access_key", null) ?: return null
            val k = s.getString("secret_key", null) ?: return null
            return SigV4.Credentials(a, k)
        }

        fun clear(context: Context) {
            prefs(context).edit().clear().apply()
            secrets(context)?.edit()?.clear()?.apply()
        }
    }
}
