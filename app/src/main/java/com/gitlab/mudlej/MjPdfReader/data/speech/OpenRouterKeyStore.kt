// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets; [encrypt] returns the IV it chose with the ciphertext. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): SealedSecret

    fun decrypt(sealed: SealedSecret): ByteArray

    /** Forgets the encryption key, so whatever it sealed can never be read again. */
    fun destroy()
}

class SealedSecret(val iv: ByteArray, val ciphertext: ByteArray)

/** The few strings [OpenRouterKeyStore] keeps; a private SharedPreferences file on the phone. */
interface SecretPreferences {
    fun getString(name: String): String?

    fun getBoolean(name: String): Boolean

    fun putStrings(values: Map<String, String>)

    fun putBoolean(name: String, value: Boolean)

    fun clear()
}

/**
 * The user's OpenRouter API key for Read aloud's cloud voice. It stays on this phone: AES-GCM
 * ciphertext in a private preferences file ([PREFERENCES_FILE], excluded from Android backup and
 * device transfer, and not part of Settings export), sealed with a key that lives in the Android
 * Keystore and never leaves it. The key is never logged; nothing here throws a message containing it.
 *
 * Also remembers whether the "Add an OpenRouter key" hint was shown.
 */
class OpenRouterKeyStore internal constructor(
    private val preferences: SecretPreferences,
    private val cipher: SecretCipher,
) {
    constructor(context: Context) : this(
        SharedSecretPreferences(
            context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE),
        ),
        AndroidKeystoreCipher(KEYSTORE_ALIAS),
    )

    /** Whether a key is saved, without decrypting it. */
    fun hasKey(): Boolean = preferences.getString(KEY_CIPHERTEXT) != null

    /** The saved key, or null when none is saved or it can no longer be decrypted (then it is removed). */
    fun key(): String? = synchronized(LOCK) {
        val ciphertext = preferences.getString(KEY_CIPHERTEXT) ?: return null
        val iv = preferences.getString(KEY_IV) ?: return null
        runCatching {
            String(cipher.decrypt(SealedSecret(hexToBytes(iv), hexToBytes(ciphertext))), Charsets.UTF_8)
        }.getOrElse {
            removeLocked()
            null
        }
    }

    /** Encrypts and saves [rawKey] after [normalize]; throws [IllegalArgumentException] when it is not a key. */
    fun save(rawKey: String) {
        val key = normalize(rawKey)
        synchronized(LOCK) {
            val sealed = cipher.encrypt(key.toByteArray(Charsets.UTF_8))
            preferences.putStrings(
                mapOf(KEY_CIPHERTEXT to bytesToHex(sealed.ciphertext), KEY_IV to bytesToHex(sealed.iv)),
            )
        }
    }

    /** Deletes the saved key and its Keystore key; the hint will be shown once more. */
    fun remove() = synchronized(LOCK) { removeLocked() }

    private fun removeLocked() {
        preferences.clear()
        runCatching { cipher.destroy() }
    }

    /** True the first time it is asked after the hint became due; false afterwards. */
    fun consumeNoKeyHint(): Boolean = synchronized(LOCK) {
        if (preferences.getBoolean(KEY_HINT_SHOWN)) return false
        preferences.putBoolean(KEY_HINT_SHOWN, true)
        true
    }

    companion object {
        const val PREFERENCES_FILE = "read_aloud_voice"
        private const val KEYSTORE_ALIAS = "thoughts_openrouter_key"
        private const val KEY_CIPHERTEXT = "openrouter_key_ciphertext"
        private const val KEY_IV = "openrouter_key_iv"
        private const val KEY_HINT_SHOWN = "no_key_hint_shown"
        private const val MAX_KEY_LENGTH = 512
        private val LOCK = Any()

        /**
         * The key as pasted, without surrounding whitespace. It goes into an HTTP header, so only
         * printable ASCII without spaces is accepted.
         */
        fun normalize(rawKey: String): String {
            val key = rawKey.trim()
            require(key.isNotEmpty()) { "Paste an OpenRouter API key first" }
            require(key.length <= MAX_KEY_LENGTH) { "That is too long for an OpenRouter API key" }
            require(key.all { it in '!'..'~' }) { "An OpenRouter API key has no spaces or special characters" }
            return key
        }

        /** "…" and the last four characters, to tell keys apart without showing them. */
        fun ending(key: String): String = "…" + key.takeLast(4)

        internal fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        internal fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "odd hex length" }
            return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
    }
}

internal class SharedSecretPreferences(private val preferences: SharedPreferences) : SecretPreferences {
    override fun getString(name: String): String? = preferences.getString(name, null)

    override fun getBoolean(name: String): Boolean = preferences.getBoolean(name, false)

    override fun putStrings(values: Map<String, String>) {
        preferences.edit().apply { values.forEach { (name, value) -> putString(name, value) } }.commit()
    }

    override fun putBoolean(name: String, value: Boolean) {
        preferences.edit().putBoolean(name, value).apply()
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }
}

/** AES-256-GCM with a key generated inside the Android Keystore under [alias]. */
class AndroidKeystoreCipher(private val alias: String) : SecretCipher {
    override fun encrypt(plaintext: ByteArray): SealedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return SealedSecret(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(sealed: SealedSecret): ByteArray {
        val key = keyStore().getKey(alias, null) as? SecretKey ?: error("The Keystore key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        return cipher.doFinal(sealed.ciphertext)
    }

    override fun destroy() {
        keyStore().deleteEntry(alias)
    }

    private fun secretKey(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
