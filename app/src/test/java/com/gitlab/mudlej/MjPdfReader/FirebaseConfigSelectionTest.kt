// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader

import groovy.lang.Closure
import groovy.lang.GroovyShell
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * firebase-config.groovy, the script app/build.gradle runs per variant: the client of the variant's
 * application ID out of a google-services.json, or push off with a reason. Neutral, made-up values.
 */
class FirebaseConfigSelectionTest {
    @get:Rule val folder = TemporaryFolder()

    @Suppress("UNCHECKED_CAST")
    private val select = GroovyShell().evaluate(File("firebase-config.groovy")) as Closure<Map<String, Any?>>

    private fun pick(json: String?, packageName: String): Map<String, String> {
        val file = File(folder.root, "google-services-${System.nanoTime()}.json")
        json?.let(file::writeText)
        return select.call(file, packageName).mapValues { it.value.toString() }
    }

    private fun client(packageName: String, appId: String, apiKey: String?) = """
        {
          "client_info": {
            "mobilesdk_app_id": "$appId",
            "android_client_info": { "package_name": "$packageName" }
          },
          "oauth_client": [],
          "api_key": [${apiKey?.let { "{ \"current_key\": \"$it\" }" } ?: ""}],
          "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
        }
    """.trimIndent()

    private fun servicesJson(vararg clients: String) = """
        {
          "project_info": {
            "project_number": "111111111111",
            "project_id": "example-thoughts",
            "storage_bucket": "example-thoughts.example"
          },
          "client": [${clients.joinToString(",")}],
          "configuration_version": "1"
        }
    """.trimIndent()

    private val both = servicesJson(
        client("org.example.other", "1:111111111111:android:cccccccccccccccc", "example-key-other"),
        client("org.tbend.thoughts", "1:111111111111:android:aaaaaaaaaaaaaaaa", "example-key-debug"),
        client("org.tbend.thoughts.sandbox", "1:111111111111:android:bbbbbbbbbbbbbbbb", "example-key-sandbox"),
    )

    @Test fun picksTheClientOfTheVariantsApplicationId() {
        assertEquals(
            mapOf(
                "on" to "true",
                "appId" to "1:111111111111:android:aaaaaaaaaaaaaaaa",
                "apiKey" to "example-key-debug",
                "projectId" to "example-thoughts",
                "senderId" to "111111111111",
            ),
            pick(both, "org.tbend.thoughts"),
        )
        val sandbox = pick(both, "org.tbend.thoughts.sandbox")
        assertEquals("1:111111111111:android:bbbbbbbbbbbbbbbb", sandbox["appId"])
        assertEquals("example-key-sandbox", sandbox["apiKey"])
    }

    @Test fun withoutTheFileOrTheClientPushIsOff() {
        assertEquals(mapOf("on" to "false", "reason" to "no google-services.json"), pick(null, "org.tbend.thoughts"))
        val onlyDebug = servicesJson(client("org.tbend.thoughts", "1:111111111111:android:aaaaaaaaaaaaaaaa", "example-key-debug"))
        assertEquals(mapOf("on" to "false", "reason" to "no client for org.tbend.thoughts.sandbox"), pick(onlyDebug, "org.tbend.thoughts.sandbox"))
        assertEquals("true", pick(onlyDebug, "org.tbend.thoughts")["on"])
    }

    @Test fun anIncompleteOrOddFileTurnsPushOffWithoutPrintingItsValues() {
        val noKey = pick(servicesJson(client("org.tbend.thoughts", "1:111111111111:android:aaaaaaaaaaaaaaaa", null)), "org.tbend.thoughts")
        assertEquals(mapOf("on" to "false", "reason" to "the client for org.tbend.thoughts has no apiKey"), noKey)

        val odd = pick(servicesJson(client("org.tbend.thoughts", "1:111111111111:android:aaaaaaaaaaaaaaaa", "bad key\\\\with\\\"quote")), "org.tbend.thoughts")
        assertEquals("false", odd["on"])
        assertEquals("unexpected characters in apiKey", odd["reason"])

        assertEquals(mapOf("on" to "false", "reason" to "google-services.json is not valid JSON"), pick("{ not json", "org.tbend.thoughts"))
        assertEquals(mapOf("on" to "false", "reason" to "google-services.json is not a JSON object"), pick("[1, 2]", "org.tbend.thoughts"))

        // No reason repeats a value from the file.
        for (result in listOf(noKey, odd)) {
            val reason = result.getValue("reason")
            assertFalse(reason, "111111111111" in reason || "example-key" in reason || "bad key" in reason)
        }
    }

    @Test fun theBuildReadsTheConfigFromOutsideTheRepository() {
        val build = File("build.gradle").readText()
        assertTrue(build.contains("project.findProperty('thoughtsFirebaseConfig')"))
        assertTrue(build.contains("/.config/thoughts/firebase/google-services.json"))
        assertTrue(build.contains("evaluate(file('firebase-config.groovy'))"))
        // Nothing of a Firebase project is in the repository.
        assertFalse(File("google-services.json").exists())
        assertFalse(File("src/main/google-services.json").exists())
    }
}
