package io.galva.sdk.impl.inappmessage

import io.galva.billing.BillingManager
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.logger.NoOpLogger
import io.galva.iam.MessageOverlay
import io.galva.iam.bundle.WebViewBundleResolver
import io.galva.identity.Identity
import io.galva.identity.IdentityManager
import io.galva.network.request.BatchCollectRequest
import io.galva.network.request.ListCommunicationRequest
import io.galva.network.request.ResolveMessageRequest
import io.galva.network.response.CommunicationResponse
import io.galva.network.response.MessageResponse
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultInAppMessagingManagerTest {
    @Test
    fun `resume fetch message restarts fetch for active collector`() = runTest {
        val identifyService = SuspendingIdentifyService()
        val manager = createManager(identifyService)

        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.messages.collect()
        }
        identifyService.fetchStarted.await()

        manager.cancelFetchMessage()
        identifyService.fetchCancelled.await()
        manager.resumeFetchMessage()
        identifyService.resumedFetchStarted.await()
        runCurrent()

        assertTrue(collector.isActive)
        assertEquals(2, identifyService.fetchCount)
    }

    @Test
    fun `resume fetch message does nothing without a collector`() = runTest {
        val identifyService = SuspendingIdentifyService()
        val manager = createManager(identifyService)

        manager.resumeFetchMessage()
        runCurrent()

        assertEquals(0, identifyService.fetchCount)
    }

    private fun createManager(
        identifyService: IdentifyService,
    ): DefaultInAppMessagingManager = DefaultInAppMessagingManager.create(
        logger = NoOpLogger,
        identityService = identifyService,
        identityManager = InitializedIdentityManager(),
        webViewBundleResolver = mock<WebViewBundleResolver>(),
        messageOverlay = mock<MessageOverlay>(),
        billingManager = mock<BillingManager>(),
        appLifecycleObserver = object : AppLifecycleObserver {
            override fun observe(onEvent: (io.galva.common.lifecycle.AppLifecycleState) -> Unit) =
                AutoCloseable {}
        },
    )
}

private class SuspendingIdentifyService : IdentifyService {
    val fetchStarted = CompletableDeferred<Unit>()
    val fetchCancelled = CompletableDeferred<Unit>()
    val resumedFetchStarted = CompletableDeferred<Unit>()
    var fetchCount = 0
        private set

    override suspend fun fetchCommunications(
        request: ListCommunicationRequest,
    ): ServiceResult<CommunicationResponse> {
        fetchCount++
        if (fetchCount == 1) {
            fetchStarted.complete(Unit)
        } else {
            resumedFetchStarted.complete(Unit)
        }
        try {
            awaitCancellation()
        } finally {
            if (fetchCount == 1) {
                fetchCancelled.complete(Unit)
            }
        }
    }

    override suspend fun sendBatchCollectRequest(
        request: BatchCollectRequest,
    ): ServiceResult<Unit> = error("Not used")

    override suspend fun resolveMessage(
        request: ResolveMessageRequest,
    ): ServiceResult<MessageResponse> = error("Not used")
}

private class InitializedIdentityManager : IdentityManager {
    override val state: StateFlow<Identity> = MutableStateFlow(
        Identity(
            anonymousId = "anonymous-1",
            obfuscatedAccountId = "account-1",
            userId = "user-1",
            firstCreated = false,
        )
    )

    override suspend fun initialize() = Unit

    override suspend fun awaitInitialized() = Unit

    override suspend fun identify(
        userId: String,
        email: String?,
        obfuscatedAccountId: String?,
    ) = Unit

    override suspend fun updateUserProperties(properties: JsonObject) = Unit

    override suspend fun setPushToken(token: String) = Unit

    override suspend fun clearPushToken() = Unit

    override suspend fun logout() = Unit
}
