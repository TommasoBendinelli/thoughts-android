// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.speech

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The OpenRouter key sealed by the real Android Keystore, in a test-only file and alias. */
class OpenRouterKeyStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun store() = OpenRouterKeyStore(SharedSecretPreferences(preferences), AndroidKeystoreCipher(ALIAS))

    @After fun cleanUp() {
        store().remove()
    }

    @Test fun keyRoundTripsThroughTheAndroidKeystore() {
        val key = "sk-or-v1-" + "0123456789abcdef".repeat(2)
        store().save(key)
        assertEquals(key, store().key())

        val file = File(context.applicationInfo.dataDir, "shared_prefs/$PREFERENCES.xml")
        assertFalse(file.readText().contains(key))

        store().remove()
        assertNull(store().key())
    }

    private companion object {
        const val PREFERENCES = "read_aloud_voice_instrumented_test"
        const val ALIAS = "thoughts_openrouter_key_instrumented_test"
    }
}
