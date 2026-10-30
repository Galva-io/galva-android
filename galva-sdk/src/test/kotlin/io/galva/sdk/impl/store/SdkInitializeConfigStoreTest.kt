package io.galva.sdk.impl.store

import androidx.test.core.app.ApplicationProvider
import io.galva.common.logger.LogLevel
import io.galva.common.logger.LogSink
import io.galva.common.logger.Logger
import io.galva.localstorage.PrefKeyValueStorage
import io.galva.network.HttpHeaders
import io.galva.network.NetworkError
import io.galva.network.request.sdk.InitConfigSdkRequest
import io.galva.network.response.BatchCollectionConfig
import io.galva.network.response.PlayStoreConfig
import io.galva.network.response.SDKInitializeConfig
import io.galva.network.response.SDKInitializeResponse
import io.galva.network.service.SdkService
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** `sdk/initialize` failures fall back to the cached config, and a rejected API key is reported. */
@RunWith(RobolectricTestRunner::class)
class SdkInitializeConfigStoreTest {
    private val lines = CopyOnWriteArrayList<Pair<LogLevel, String>>()
    private val logger = Logger.create("Galva", LogSink { level, _, message, _ -> lines += level to message })
    private val storage = PrefKeyValueStorage(ApplicationProvider.getApplicationContext(), fileName = "config-test")
    private val config = SDKInitializeResponse(
        SDKInitializeConfig(listOf("v1"), BatchCollectionConfig(10, 10_000), PlayStoreConfig(listOf("pro")))
    )

    private fun store(vararg results: ServiceResult<SDKInitializeResponse>): SdkInitializeConfigStore {
        val queue = results.toMutableList()
        val service = object : SdkService {
            override suspend fun getInitializeConfig(request: InitConfigSdkRequest) = queue.removeAt(0)
        }
        return SdkInitializeConfigStore(service, storage, logger)
    }

    private fun LogLevel.isProblem() = this == LogLevel.ERROR || this == LogLevel.WARN

    private fun failure(status: Int) = ServiceResult.Failure(status, "", HttpHeaders.Builder().build(), retryable = false)

    @Test
    fun `a rejected API key is an error that points at the configuration`() = runBlocking {
        val store = store(ServiceResult.Success(config), failure(401))
        store.loadAndSaveConfig()

        assertEquals(config, store.loadAndSaveConfig())
        val (level, message) = lines.single { it.first.isProblem() }
        assertEquals(LogLevel.ERROR, level)
        assertTrue(message, message.contains("apiKey"))
    }

    @Test
    fun `offline falls back to the cached config at info level`() = runBlocking {
        val store = store(ServiceResult.Success(config), ServiceResult.Error(NetworkError.NoConnectivity()))
        store.loadAndSaveConfig()

        assertEquals(config, store.loadAndSaveConfig())
        assertTrue("no warning or error expected: $lines", lines.none { it.first.isProblem() })
        assertTrue(lines.any { it.first == LogLevel.INFO && it.second.contains("offline") })
    }

    @Test
    fun `a server error is a warning`() = runBlocking {
        store(failure(503)).loadAndSaveConfig()

        assertEquals(LogLevel.WARN, lines.single().first)
    }
}
