package com.john.assistant.integrations

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.john.assistant.core.util.AssistantLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where connected-account tokens live.
 *
 * An OAuth token is a standing grant to act as the user on someone else's
 * service, so it goes in `EncryptedSharedPreferences` — keys held in the
 * Android Keystore, backed by hardware where the device has it — and never in
 * plain preferences, the database, a log line, or source.
 *
 * If encrypted storage cannot be created (a small number of devices with broken
 * keystore implementations), this class refuses to fall back to plaintext.
 * [isAvailable] goes false, connecting an account is disabled, and the UI says
 * why. Storing a token in the clear because the secure path failed would be
 * exactly the wrong trade.
 */
@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AssistantLogger,
) {

    private val preferences: SharedPreferences? by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure {
            logger.error(TAG, "Encrypted storage unavailable; connected accounts are disabled", it)
        }.getOrNull()
    }

    val isAvailable: Boolean get() = preferences != null

    fun put(key: String, value: String) {
        preferences?.edit()?.putString(key, value)?.apply()
    }

    fun get(key: String): String? = preferences?.getString(key, null)

    fun remove(key: String) {
        preferences?.edit()?.remove(key)?.apply()
    }

    /** Disconnect everything. Used by the privacy screen. */
    fun clear() {
        preferences?.edit()?.clear()?.apply()
    }

    private companion object {
        const val TAG = "TokenStore"
        const val FILE_NAME = "john_secure_tokens"
    }
}
