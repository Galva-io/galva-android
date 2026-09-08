package io.galva.sdk.impl.deeplink

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.galva.iam.InAppMessagingManager
import io.galva.iam.Message
import io.galva.identity.Identity
import io.galva.identity.IdentityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeeplinkRouterTest {
    private lateinit var context: Context
    private lateinit var identityManager: FakeIdentityManager
    private lateinit var inAppMessagingManager: RecordingInAppMessagingManager
    private lateinit var pendingStore: InMemoryPendingDeeplinkStore
    private lateinit var router: DeeplinkRouter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        identityManager = FakeIdentityManager()
        inAppMessagingManager = RecordingInAppMessagingManager()
        pendingStore = InMemoryPendingDeeplinkStore()
        router = createRouter()
    }

    @Test
    fun `unsupported scheme is ignored`() = runTest {
        val handled = router.handle(
            Uri.parse("https://openCommunication?communicationId=communication-1")
        )

        assertFalse(handled)
        assertEquals(0, identityManager.awaitInitializedCount)
        assertEquals(0, inAppMessagingManager.cancelCount)
        assertNull(pendingStore.pending)
    }

    @Test
    fun `unknown route is ignored`() = runTest {
        val handled = router.handle(
            Uri.parse("gv://unknown?communicationId=communication-1")
        )

        assertFalse(handled)
        assertEquals(0, inAppMessagingManager.cancelCount)
    }

    @Test
    fun `open communication requires a non blank communication id`() = runTest {
        val missingIdHandled = router.handle(Uri.parse("gv://openCommunication"))
        val blankIdHandled = router.handle(
            Uri.parse("gv://openCommunication?communicationId=%20%20")
        )

        assertFalse(missingIdHandled)
        assertFalse(blankIdHandled)
        assertEquals(0, identityManager.awaitInitializedCount)
        assertEquals(0, inAppMessagingManager.cancelCount)
    }

    @Test
    fun `anonymous user retains only latest deeplink then opens it after identify`() = runTest {
        val firstUri = Uri.parse(
            "gv://openCommunication?communicationId=communication-1"
        )
        val latestUri = Uri.parse(
            "gv-app://router/openCommunication?communicationId=communication-2"
        )

        assertTrue(router.handle(firstUri))
        assertTrue(router.handle(latestUri))

        assertEquals(latestUri, pendingStore.pending)
        assertTrue(inAppMessagingManager.shownMessages.isEmpty())

        identityManager.identify("user-1", null, null)
        router.handlePending()

        assertNull(pendingStore.pending)
        assertEquals(listOf(Message("communication-2")), inAppMessagingManager.shownMessages)
        assertEquals(3, inAppMessagingManager.cancelCount)
        assertEquals(3, inAppMessagingManager.resumeCount)
        assertEquals(
            context.applicationContext,
            inAppMessagingManager.presentationContexts.single(),
        )
    }

    @Test
    fun `cached identified user opens communication immediately`() = runTest {
        identityManager = FakeIdentityManager(userIdAfterAwait = "cached-user")
        router = createRouter()

        val handled = router.handle(
            Uri.parse(
                "gv2://route?destination=openCommunication&communicationId=communication-cached"
            )
        )

        assertTrue(handled)
        assertEquals(
            listOf(Message("communication-cached")),
            inAppMessagingManager.shownMessages,
        )
        assertEquals(listOf("cancel", "show", "resume"), inAppMessagingManager.events)
        assertEquals(1, identityManager.awaitInitializedCount)
        assertNull(pendingStore.pending)
    }

    private fun createRouter(): DeeplinkRouter = DeeplinkRouter(
        handlers = listOf(
            DeeplinkOpenCommunicationRouterHandler(
                context = context,
                identityManager = identityManager,
                inAppMessagingManager = inAppMessagingManager,
            )
        ),
        pendingDeeplinkStore = pendingStore,
    )
}

private class InMemoryPendingDeeplinkStore : PendingDeeplinkStore {
    var pending: Uri? = null

    override suspend fun save(uri: Uri) {
        pending = uri
    }

    override suspend fun load(): Uri? = pending

    override suspend fun clear() {
        pending = null
    }
}

private class RecordingInAppMessagingManager : InAppMessagingManager {
    override val messages: Flow<Message> = emptyFlow()
    val events = mutableListOf<String>()
    val shownMessages = mutableListOf<Message>()
    val presentationContexts = mutableListOf<Context>()
    var cancelCount = 0
    var resumeCount = 0

    override fun showMessage(context: Activity, message: Message) {
        recordMessage(context, message)
    }

    override fun showMessage(context: Context, message: Message) {
        recordMessage(context, message)
    }

    private fun recordMessage(context: Context, message: Message) {
        events += "show"
        presentationContexts += context
        shownMessages += message
    }

    override fun cancelFetchMessage() {
        events += "cancel"
        cancelCount++
    }

    override fun resumeFetchMessage() {
        events += "resume"
        resumeCount++
    }
}

private class FakeIdentityManager(
    userId: String? = null,
    private val userIdAfterAwait: String? = null,
) : IdentityManager {
    private val mutableState = MutableStateFlow(
        Identity(
            anonymousId = "anonymous-1",
            obfuscatedAccountId = "account-1",
            userId = userId,
            firstCreated = false,
        )
    )
    override val state: StateFlow<Identity> = mutableState
    var awaitInitializedCount = 0

    override suspend fun initialize() = Unit

    override suspend fun awaitInitialized() {
        awaitInitializedCount++
        userIdAfterAwait?.let { cachedUserId ->
            mutableState.value = mutableState.value.copy(userId = cachedUserId)
        }
    }

    override suspend fun identify(
        userId: String,
        email: String?,
        obfuscatedAccountId: String?,
    ) {
        mutableState.value = mutableState.value.copy(
            userId = userId,
            email = email,
            obfuscatedAccountId = obfuscatedAccountId
                ?: mutableState.value.obfuscatedAccountId,
        )
    }

    override suspend fun updateUserProperties(properties: JsonObject) = Unit

    override suspend fun setPushToken(token: String) = Unit

    override suspend fun clearPushToken() = Unit

    override suspend fun logout() {
        mutableState.value = mutableState.value.copy(userId = null)
    }
}
