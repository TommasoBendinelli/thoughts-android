// Written for Thoughts. License is GPLv3.

package com.gitlab.mudlej.MjPdfReader.data.sync

import com.gitlab.mudlej.MjPdfReader.BuildConfig
import com.gitlab.mudlej.MjPdfReader.data.AppDatabase
import com.gitlab.mudlej.MjPdfReader.data.source.SourceEditQueue
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.tbend.thoughts.sdk.SyncClient
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreThoughtsServerConfigInstrumentedTest {

    @Test
    fun reportsVpnDnsState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = checkNotNull(manager.activeNetwork)
        val capabilities = checkNotNull(manager.getNetworkCapabilities(active))
        val vpnNetworks = manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        println("active=$active activeVpn=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)} vpnNetworks=$vpnNetworks")
        val addresses = active.getAllByName(checkNotNull(java.net.URI(SERVER_URL).host))
        assertTrue(addresses.isNotEmpty())
    }

    @Test
    fun restoresServerConfigurationAndStartsSync() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = InstrumentationRegistry.getArguments()
            .getString("thoughts_token")
            .orEmpty()
        assertTrue("A Thoughts server token is required", token.isNotBlank())

        val expectedDevice = InstrumentationRegistry.getArguments().getString("expected_device_id").orEmpty()
        assertTrue("Restoration requires a dedicated Android identity", expectedDevice.startsWith("thoughts-android-") || expectedDevice == "thoughts-android")
        assertEquals("Credential belongs to another device", expectedDevice, SyncClient(SERVER_URL, token).deviceSequence().deviceId)
        ThoughtsServerSecretStore(context).set(SERVER_URL, token)
        val result = SyncCommandCoordinator(context).start(null)
        assertTrue(result.message, result.accepted)
    }

    /** Explicit provisioning only: keeps the installed app, database and synced files intact. */
    @Test
    fun provisionDedicatedAndroidIdentity() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("provision_android_identity") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "android-device-provisioning.json")
        assertTrue("Private provisioning file is missing", file.isFile)
        val request = JsonParser.parseString(file.readText()).asJsonObject
        val expectedDevice = request.get("device_id").asString
        assertTrue("Expected a dedicated Android device", expectedDevice.startsWith("thoughts-android-"))
        val token = request.get("token").asString
        val remote = SyncClient(SERVER_URL, token).deviceSequence()
        assertEquals(expectedDevice, remote.deviceId)
        val store = ThoughtsServerSecretStore(context)
        val previous = requireNotNull(store.config())
        val oldDevice = SyncClient(previous.baseUrl, previous.token).deviceSequence().deviceId
        assertTrue("Unexpected prior device identity", oldDevice == "thoughts-mac" || oldDevice == expectedDevice)
        val dao = AppDatabase.getInstance(context).thoughtsSyncDao()
        assertTrue("Drain the existing outbox before changing its identity", dao.undelivered().isEmpty())
        assertTrue("Finish pending source requests first", SourceEditQueue(context, scheduleRequests = false).undeliveredRequestIds().isEmpty())
        val beforeState = dao.state()
        val beforeOutbox = dao.allOutbox()
        val beforeSources = dao.sourceRequests()
        store.set(SERVER_URL, token)
        val saved = requireNotNull(store.config())
        assertEquals(expectedDevice, SyncClient(saved.baseUrl, saved.token).deviceSequence().deviceId)
        assertEquals(beforeState, dao.state())
        assertEquals(beforeOutbox, dao.allOutbox())
        assertEquals(beforeSources, dao.sourceRequests())
        assertTrue("Remove provisioning credential after storing it encrypted", file.delete())
        println("Identity switched: $oldDevice -> $expectedDevice; preserved ${beforeOutbox.size} outbox records and ${beforeSources.size} source requests; pending=0")
    }

    @Test
    fun verifyDedicatedAndroidIdentity() = runBlocking {
        val expected = InstrumentationRegistry.getArguments().getString("expected_device_id")
        assumeTrue(!expected.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = requireNotNull(ThoughtsServerSecretStore(context).config())
        val identity = SyncClient(config.baseUrl, config.token).deviceSequence()
        assertEquals(expected, identity.deviceId)
        val dao = AppDatabase.getInstance(context).thoughtsSyncDao()
        println("Authenticated identity=${identity.deviceId}; remoteNext=${identity.nextDeviceSequence}; localNext=${dao.state()?.nextDeviceSequence}; pending=${dao.undelivered().size}")
        assertTrue("Pending operation delivery is incomplete", dao.undelivered().isEmpty())
    }

    private companion object {
        val SERVER_URL = BuildConfig.TEST_SERVER_URL
    }
}
