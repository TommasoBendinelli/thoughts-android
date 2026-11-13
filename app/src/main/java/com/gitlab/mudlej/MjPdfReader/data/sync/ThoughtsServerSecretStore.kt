// Written for MJ PDF. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ThoughtsServerConfig(val baseUrl: String, val token: String)

class ThoughtsServerSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun config(): ThoughtsServerConfig? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        requireAllowedEnvironment(baseUrl)
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        // The token is only ever sent to the server it was entered for.
        when (ThoughtsServerAddress.storedTokenUse(baseUrl, preferences.getString(KEY_TOKEN_ORIGIN, null))) {
            ThoughtsServerAddress.StoredToken.USE -> Unit
            ThoughtsServerAddress.StoredToken.BIND_TO_SAVED_ADDRESS -> synchronized(LOCK) {
                if (preferences.getString(KEY_BASE_URL, null) == baseUrl && preferences.getString(KEY_TOKEN_ORIGIN, null) == null) {
                    preferences.edit().putString(KEY_TOKEN_ORIGIN, ThoughtsServerAddress.originOf(baseUrl)).apply()
                }
            }
            ThoughtsServerAddress.StoredToken.FORGET -> {
                forgetToken()
                return null
            }
        }
        return runCatching {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            ThoughtsServerConfig(
                baseUrl,
                String(
                    cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                    Charsets.UTF_8,
                ),
            )
        }.getOrElse {
            clear()
            null
        }
    }

    /** The saved server address without decrypting the token, or null when none is saved. */
    fun baseUrl(): String? = preferences.getString(KEY_BASE_URL, null)

    fun set(baseUrl: String, token: String) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        requireAllowedEnvironment(normalizedUrl)
        ThoughtsServerAddress.normalize(normalizedUrl)
        require(token.isNotBlank()) { "The device token must not be empty" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        synchronized(LOCK) {
            preferences.edit()
                .putString(KEY_BASE_URL, normalizedUrl)
                .putString(KEY_CIPHERTEXT, ciphertext)
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_TOKEN_ORIGIN, ThoughtsServerAddress.originOf(normalizedUrl))
                .apply()
        }
    }

    /**
     * Saves a new address without a token and removes the saved token, which was entered for
     * another server. [config] stays null until the token for this address is entered.
     */
    fun setAddressAwaitingToken(baseUrl: String) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        requireAllowedEnvironment(normalizedUrl)
        ThoughtsServerAddress.normalize(normalizedUrl)
        synchronized(LOCK) {
            preferences.edit()
                .putString(KEY_BASE_URL, normalizedUrl)
                .remove(KEY_CIPHERTEXT)
                .remove(KEY_IV)
                .remove(KEY_TOKEN_ORIGIN)
                .apply()
        }
    }

    private fun forgetToken() = synchronized(LOCK) {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).remove(KEY_TOKEN_ORIGIN).apply()
    }

    /**
     * Changes only the saved address, keeping the encrypted token as stored, and only while the
     * saved address is still [expected]. Returns whether the address was replaced. For the app's
     * own move of one server to its new address ([PublicServerMigration]), which has checked that
     * the new address accepts the token: the token is bound to the new address.
     */
    fun replaceBaseUrl(expected: String, replacement: String): Boolean = synchronized(LOCK) {
        val normalized = ThoughtsServerAddress.normalize(replacement)
        requireAllowedEnvironment(normalized)
        if (preferences.getString(KEY_BASE_URL, null) != expected) return false
        if (preferences.getString(KEY_CIPHERTEXT, null) == null) return false
        preferences.edit()
            .putString(KEY_BASE_URL, normalized)
            .putString(KEY_TOKEN_ORIGIN, ThoughtsServerAddress.originOf(normalized))
            .commit()
    }

    private fun requireAllowedEnvironment(baseUrl: String) {
        require(!BuildConfig.IS_TEST_ENVIRONMENT || baseUrl == BuildConfig.TEST_SERVER_URL) {
            "Thoughts Test can only connect to the isolated test server"
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun encryptionKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
        load(null)
    }

    companion object {
        private val LOCK = Any()
        private const val PREFERENCES_NAME = "thoughts_server"
        private const val KEY_ALIAS = "thoughts_server_token"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val KEY_IV = "iv"
        /** The origin ([ThoughtsServerAddress.originOf]) the stored token was entered for. */
        private const val KEY_TOKEN_ORIGIN = "token_origin"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
