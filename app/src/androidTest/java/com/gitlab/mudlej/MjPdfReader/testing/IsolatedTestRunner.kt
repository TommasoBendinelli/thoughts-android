// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.testing

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.sync.ThoughtsServerSecretStore
import java.io.File
import com.google.gson.JsonParser

/** Refuse to execute before a test can touch the normal application's storage. */
class IsolatedTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        check(targetContext.packageName == "org.tbend.thoughts.sandbox" && BuildConfig.IS_TEST_ENVIRONMENT) {
            "Instrumentation is restricted to Thoughts Test"
        }
        check(arguments.getString("isolation_run_id")?.matches(Regex("[a-zA-Z0-9-]{1,80}")) == true) {
            "Use tools/run-isolated-tests.py so cleanup runs even after a failed test"
        }
        val provisioning = File(targetContext.filesDir, "test-server.json")
        if (provisioning.exists()) {
            val config = JsonParser.parseString(provisioning.readText()).asJsonObject
            check(config.get("base_url").asString == BuildConfig.TEST_SERVER_URL)
            ThoughtsServerSecretStore(targetContext).set(BuildConfig.TEST_SERVER_URL, config.get("token").asString)
            check(provisioning.delete())
        }
        super.onCreate(arguments)
    }
}
