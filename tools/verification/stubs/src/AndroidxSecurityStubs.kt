package androidx.security.crypto

import android.content.Context
import android.content.SharedPreferences

/**
 * Stub of androidx.security:security-crypto for the offline verifier.
 * See DaggerStubs.kt for why these exist.
 */
class MasterKey private constructor() {
    class Builder(context: Context) {
        fun setKeyScheme(scheme: KeyScheme): Builder = this
        fun build(): MasterKey = MasterKey()
    }

    enum class KeyScheme { AES256_GCM }
}

object EncryptedSharedPreferences {
    enum class PrefKeyEncryptionScheme { AES256_SIV }

    enum class PrefValueEncryptionScheme { AES256_GCM }

    @JvmStatic
    fun create(
        context: Context,
        fileName: String,
        masterKey: MasterKey,
        prefKeyEncryptionScheme: PrefKeyEncryptionScheme,
        prefValueEncryptionScheme: PrefValueEncryptionScheme,
    ): SharedPreferences = throw UnsupportedOperationException("verification stub")
}
