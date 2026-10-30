package io.galva.sdk.impl.inappmessage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.galva.billing.BillingManager
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState
import io.galva.common.logger.NoOpLogger
import io.galva.iam.Message
import io.galva.iam.MessageOverlay
import io.galva.iam.WorkflowType
import io.galva.iam.bundle.WebViewBundleResolver
import io.galva.identity.Identity
import io.galva.identity.IdentityManager
import io.galva.network.request.BatchCollectRequest
import io.galva.network.request.ListCommunicationRequest
import io.galva.network.request.ResolveMessageRequest
import io.galva.network.response.Communication
import io.galva.network.response.CommunicationResponse
import io.galva.network.response.MessageResponse
import io.galva.network.response.Meta
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.time.Duration.Companion.minutes

/** Selection rules mirror the iOS SDK's InAppMessageManager. */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultInAppMessagingManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Env(test: TestScope, ids: List<String>) {
        val service = ScriptedIdentifyService(ids)
        val lifecycle = FakeLifecycle()
        val overlay = RecordingOverlay()
        val delivered = mutableListOf<Message>()
        val manager = DefaultInAppMessagingManager.create(
            logger = NoOpLogger,
            identityService = service,
            identityManager = InitializedIdentityManager(),
            webViewBundleResolver = mock {
                onBlocking { resolve(any()) } doReturn WebViewBundleResolver.Outcome.Ready(File("bundle.html"))
            },
            messageOverlay = overlay,
            billingManager = mock<BillingManager> {
                onBlocking { getStorefrontCountryCode() } doReturn "US"
            },
            appLifecycleObserver = lifecycle,
            scope = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler)),
        )

        init {
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) {
                manager.messages.toList(delivered)
            }
        }
    }

    @Test
    fun `foreground delivers the first unseen item in server order without re-ranking`() = runTest {
        // "newest" has the later createdAt; the server order wins regardless.
        val env = Env(this, listOf("newest", "older"))

        env.lifecycle.foreground()

        assertEquals(listOf("newest"), env.delivered.map { it.id })
        assertEquals(1, env.service.fetchCount)
    }

    @Test
    fun `delivered messages carry workflow type and creation time`() = runTest {
        val env = Env(this, listOf("a"))
        env.service.workflowType = "payment-recovery"

        env.lifecycle.foreground()

        assertEquals(
            listOf(Message("a", WorkflowType.PAYMENT_RECOVERY, "2026-09-01T00:00:00Z")),
            env.delivered,
        )
    }

    @Test
    fun `deep link parameters reach the overlay`() = runTest {
        val env = Env(this, emptyList())

        env.manager.showMessage(context, Message("c"), mapOf("communicationId" to "c", "utm" to "email"))

        assertEquals(mapOf("communicationId" to "c", "utm" to "email"), env.overlay.parameters["c"])
    }

    @Test
    fun `there is no periodic polling`() = runTest {
        val env = Env(this, listOf("a"))

        advanceTimeBy(10.minutes)

        assertEquals(0, env.service.fetchCount)
        assertTrue(env.delivered.isEmpty())
    }

    @Test
    fun `a message is delivered at most once per session`() = runTest {
        val env = Env(this, listOf("b", "a"))

        env.lifecycle.foreground()
        env.lifecycle.foreground()
        env.lifecycle.foreground()

        assertEquals(listOf("b", "a"), env.delivered.map { it.id })
        assertEquals(3, env.service.fetchCount)
    }

    @Test
    fun `polling is skipped while a message is on screen and resumes after dismiss`() = runTest {
        val env = Env(this, listOf("b", "a"))
        env.lifecycle.foreground()
        env.manager.showMessage(context, Message("b"))

        env.lifecycle.foreground()
        assertEquals(1, env.service.fetchCount)

        env.overlay.dismiss("b")
        env.lifecycle.foreground()
        assertEquals(2, env.service.fetchCount)
        assertEquals(listOf("b", "a"), env.delivered.map { it.id })
    }

    @Test
    fun `a pending deep link presentation suppresses polling`() = runTest {
        val env = Env(this, listOf("a"))
        val resolveGate = CompletableDeferred<Unit>().also { env.service.resolveGate = it }

        env.manager.showMessage(context, Message("deep-link"))
        env.lifecycle.foreground()
        assertEquals(0, env.service.fetchCount)

        resolveGate.complete(Unit)
        assertEquals(listOf("deep-link"), env.overlay.presented)
    }

    @Test
    fun `results of a poll overtaken by a deep link are dropped unmarked`() = runTest {
        val env = Env(this, listOf("a"))
        val fetchGate = CompletableDeferred<Unit>().also { env.service.fetchGate = it }
        val resolveGate = CompletableDeferred<Unit>().also { env.service.resolveGate = it }

        env.lifecycle.foreground()                                  // fetch in flight
        env.manager.showMessage(context, Message("deep-link"))      // claims the slot
        fetchGate.complete(Unit)
        assertTrue(env.delivered.isEmpty())

        env.service.valid = false                                   // the deep link fails to resolve
        resolveGate.complete(Unit)
        env.service.fetchGate = null
        env.lifecycle.foreground()

        assertEquals(listOf("a"), env.delivered.map { it.id })
    }

    @Test
    fun `a presented message is never delivered again`() = runTest {
        val env = Env(this, listOf("b", "a"))

        env.manager.showMessage(context, Message("b"))
        env.overlay.dismiss("b")
        env.lifecycle.foreground()

        assertTrue(env.manager.hasPresented("b"))
        assertFalse(env.manager.hasPresented("a"))
        assertEquals(listOf("a"), env.delivered.map { it.id })
    }

    @Test
    fun `showing a message that is already being shown is a no-op`() = runTest {
        val env = Env(this, emptyList())

        env.manager.showMessage(context, Message("b"))
        env.manager.showMessage(context, Message("b"))

        assertEquals(listOf("b"), env.overlay.presented)
        assertEquals(1, env.service.resolveCount)
    }

    @Test
    fun `a failed presentation restores the budget`() = runTest {
        val env = Env(this, listOf("a"))
        env.service.valid = false

        env.manager.showMessage(context, Message("gone"))
        env.lifecycle.foreground()

        assertTrue(env.overlay.presented.isEmpty())
        assertEquals(listOf("a"), env.delivered.map { it.id })
    }

    @Test
    fun `checkForMessages polls on demand`() = runTest {
        val env = Env(this, listOf("a"))

        env.manager.checkForMessages()

        assertEquals(listOf("a"), env.delivered.map { it.id })
    }

    @Test
    fun `reset forgets seen and presented messages`() = runTest {
        val env = Env(this, listOf("a"))
        env.lifecycle.foreground()

        env.manager.reset()
        env.lifecycle.foreground()

        assertEquals(listOf("a", "a"), env.delivered.map { it.id })
    }
}

private class FakeLifecycle : AppLifecycleObserver {
    private var listener: ((AppLifecycleState) -> Unit)? = null

    override fun observe(onEvent: (AppLifecycleState) -> Unit): AutoCloseable {
        listener = onEvent
        return AutoCloseable { listener = null }
    }

    fun foreground() = listener?.invoke(AppLifecycleState.FOREGROUND)
}

private class RecordingOverlay : MessageOverlay {
    val presented = mutableListOf<String>()
    val parameters = mutableMapOf<String, Map<String, String>>()
    private val dismissCallbacks = mutableMapOf<String, () -> Unit>()

    override fun present(
        context: Context,
        message: MessageResponse,
        bundleFile: File,
        deepLinkParameters: Map<String, String>,
        onDismiss: () -> Unit,
    ) {
        presented += message.id
        parameters[message.id] = deepLinkParameters
        dismissCallbacks[message.id] = onDismiss
    }

    fun dismiss(messageId: String) = dismissCallbacks.remove(messageId)?.invoke()
}

private class ScriptedIdentifyService(private val ids: List<String>) : IdentifyService {
    var fetchCount = 0
    var resolveCount = 0
    var fetchGate: CompletableDeferred<Unit>? = null
    var resolveGate: CompletableDeferred<Unit>? = null
    var valid = true
    var workflowType: String? = null

    override suspend fun fetchCommunications(
        request: ListCommunicationRequest,
    ): ServiceResult<CommunicationResponse> {
        fetchCount++
        fetchGate?.await()
        // createdAt decreases along the list, so a client sorting by date would pick the last one.
        val items = ids.mapIndexed { index, id ->
            Communication(id = id, type = "in-app", workflowType = workflowType, createdAt = "2026-0${9 - index}-01T00:00:00Z")
        }
        return ServiceResult.Success(CommunicationResponse(items, success = true, meta = Meta(nextCursor = null)))
    }

    override suspend fun resolveMessage(request: ResolveMessageRequest): ServiceResult<MessageResponse> {
        resolveCount++
        resolveGate?.await()
        return ServiceResult.Success(MessageResponse(request.messageId, payload = null, valid = valid, webviewVersion = "1.0.0"))
    }

    override suspend fun sendBatchCollectRequest(request: BatchCollectRequest): ServiceResult<Unit> =
        error("Not used")
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

    override suspend fun setPushToken(token: String, platform: String) = Unit

    override suspend fun clearPushToken() = Unit

    override suspend fun logout() = Unit
}
