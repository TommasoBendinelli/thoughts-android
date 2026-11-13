// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The key store with the JVM's AES-GCM in place of the Android Keystore and a map for the prefs file. */
class OpenRouterKeyStoreTest {
    private class MemoryPreferences : SecretPreferences {
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        override fun getString(name: String) = strings[name]
        override fun getBoolean(name: String) = booleans[name] ?: false
        override fun putStrings(values: Map<String, String>) {
            strings.putAll(values)
        }
        override fun putBoolean(name: String, value: Boolean) {
            booleans[name] = value
        }
        override fun clear() {
            strings.clear()
            booleans.clear()
        }
    }

    /** AES-256-GCM like [AndroidKeystoreCipher], with the key in memory. */
    private class JvmCipher : SecretCipher {
        var key: SecretKey? = null
        var destroyed = 0

        private fun secretKey() = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }

        override fun encrypt(plaintext: ByteArray): SealedSecret {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            return SealedSecret(iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(sealed: SealedSecret): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, checkNotNull(key), GCMParameterSpec(128, sealed.iv))
            return cipher.doFinal(sealed.ciphertext)
        }

        override fun destroy() {
            key = null
            destroyed++
        }
    }

    private val preferences = MemoryPreferences()
    private val cipher = JvmCipher()
    private val store = OpenRouterKeyStore(preferences, cipher)
    private val key = "sk-or-v1-" + "0123456789abcdef".repeat(4)

    @Test fun savedKeyRoundTrips() {
        assertFalse(store.hasKey())
        assertNull(store.key())
        store.save("  $key\n")
        assertTrue(store.hasKey())
        assertEquals(key, store.key())
        // A second store over the same file and Keystore key reads it too (app restart).
        assertEquals(key, OpenRouterKeyStore(preferences, cipher).key())
    }

    @Test fun onlyCiphertextIsWritten() {
        store.save(key)
        val written = preferences.strings.values.joinToString(" ")
        assertFalse(written.contains(key))
        assertFalse(written.contains("sk-or"))
        assertFalse(written.contains(OpenRouterKeyStore.bytesToHex(key.toByteArray())))
        assertEquals(2, preferences.strings.size)
    }

    @Test fun eachSaveUsesAFreshIv() {
        store.save(key)
        val first = preferences.strings.toMap()
        store.save(key)
        assertTrue(first != preferences.strings.toMap())
        assertEquals(key, store.key())
    }

    @Test fun removeForgetsTheKeyAndTheKeystoreKey() {
        store.save(key)
        store.remove()
        assertFalse(store.hasKey())
        assertNull(store.key())
        assertTrue(preferences.strings.isEmpty())
        assertEquals(1, cipher.destroyed)
        // A new key can be saved afterwards.
        store.save(key)
        assertEquals(key, store.key())
    }

    @Test fun aKeyThatCanNoLongerBeDecryptedIsDropped() {
        store.save(key)
        // E.g. the Keystore key was lost, or the file was restored on another phone.
        cipher.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(store.key())
        assertFalse(store.hasKey())
    }

    @Test fun tamperedCiphertextIsRejected() {
        store.save(key)
        val name = preferences.strings.keys.first { it.endsWith("ciphertext") }
        val bytes = OpenRouterKeyStore.hexToBytes(preferences.strings.getValue(name))
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        preferences.strings[name] = OpenRouterKeyStore.bytesToHex(bytes)
        assertNull(store.key())
    }

    @Test fun pastedTextMustLookLikeAKey() {
        assertEquals("sk-or-v1-abc", OpenRouterKeyStore.normalize("\tsk-or-v1-abc  "))
        for (bad in listOf("", "   ", "sk-or v1", "sk-or-v1-abc\r\nX-Evil: 1", "sk-ör", "a".repeat(513))) {
            try {
                OpenRouterKeyStore.normalize(bad)
                fail("accepted \"$bad\"")
            } catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.orEmpty().contains(bad.trim().ifEmpty { "\u0000" }))
            }
        }
        try {
            store.save("not a key")
            fail("saved a key with spaces")
        } catch (expected: IllegalArgumentException) {
        }
        assertFalse(store.hasKey())
    }

    @Test fun endingShowsOnlyTheLastFourCharacters() {
        assertEquals("…cdef", OpenRouterKeyStore.ending(key))
    }

    @Test fun theNoKeyHintIsShownOnceUntilTheKeyIsRemoved() {
        assertTrue(store.consumeNoKeyHint())
        assertFalse(store.consumeNoKeyHint())
        store.save(key)
        assertFalse(store.consumeNoKeyHint())
        store.remove()
        assertTrue(store.consumeNoKeyHint())
        assertFalse(store.consumeNoKeyHint())
    }

    @Test fun hexRoundTrips() {
        val bytes = byteArrayOf(0, 1, -1, 127, -128, 16)
        assertEquals("0001ff7f8010", OpenRouterKeyStore.bytesToHex(bytes))
        assertArrayEquals(bytes, OpenRouterKeyStore.hexToBytes("0001ff7f8010"))
    }
}
