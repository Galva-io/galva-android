package io.galva.sdk

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.galva.common.logger.LogLevel as InternalLogLevel
import io.galva.common.logger.LogSink as InternalLogSink
import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.sdk.impl.event.EventAttributes
import io.galva.sdk.impl.inappmessage.FullScreenInAppMessageActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** The SDK never throws at the host app, configured or not. */
@RunWith(RobolectricTestRunner::class)
class CrashSafetyTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val galva = Galva(NoOpLogger, scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `every public call on an unconfigured SDK returns without throwing`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val cyclic = mutableMapOf<String, Any?>().apply { put("self", this) }

        galva.identify("user-1", "user@example.com")
        galva.updateProperties(ProfileProperty.Custom("plan", "pro"))
        galva.track("Purchase", mapOf("price" to 9.99))
        galva.track("Cyclic", cyclic)
        galva.logout()
        galva.setOptOut(true)
        galva.setOptOut(false)
        galva.setPushToken("token")
        galva.setPushToken("token", PushProvider.HMS)
        galva.clearPushToken()
        galva.handlePush(mapOf("communicationId" to "c", "title" to "t", "body" to "b"))
        galva.handleOpenURL(Uri.parse("gvdemo://openCommunication?communicationId=c"))
        galva.showMessage(activity, Message("m-1"))
        galva.checkForMessages()
        galva.reconcileTransactions()
        galva.setPurchaseController(null)
        galva.setLogLevel(LogLevel.DEBUG)

        assertFalse(galva.isConfigured)
        assertTrue(galva.isAnonymous)
        assertEquals("", galva.currentUserId)
        assertEquals("", galva.obfuscatedAccountId)
        assertFalse(galva.isOptedOut)
        // Waits for setup that never comes: no message, no exception.
        assertNull(runBlocking { withTimeoutOrNull(200) { galva.getInAppMessage().firstOrNull() } })
        assertNull(runBlocking { withTimeoutOrNull(200) { galva.billingManager.getProductCatalog("pro") } })
    }

    @Test
    fun `configure with a blank api key logs an error and leaves the SDK off`() {
        val lines = CopyOnWriteArrayList<Pair<InternalLogLevel, String>>()
        val logger = Logger.create("Galva", InternalLogSink { level, _, message, _ -> lines += level to message })
        val galva = Galva(logger, scope)

        Galva::class.java.getDeclaredMethod(
            "configureInternal",
            android.content.Context::class.java,
            Configuration::class.java,
            List::class.java,
        ).apply { isAccessible = true }
            .invoke(galva, ApplicationProvider.getApplicationContext(), Configuration(apiKey = " "), emptyList<Any>())

        assertFalse(galva.isConfigured)
        assertTrue("lines: $lines", lines.any { it.first == InternalLogLevel.ERROR && it.second.contains("apiKey") })
    }

    @OptIn(kotlinx.serialization.InternalSerializationApi::class)
    @Test
    fun `a message activity recreated before the SDK is set up closes itself`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), FullScreenInAppMessageActivity::class.java)
            .putExtra("message_id", "m-1")
            .putExtra("html_file_path", "/nonexistent/bundle.html")

        val activity = Robolectric.buildActivity(FullScreenInAppMessageActivity::class.java, intent).create().get()

        assertTrue(activity.isFinishing)
    }

    @Test
    fun `self-referencing attributes are cut off instead of overflowing the stack`() {
        val cyclic = mutableMapOf<String, Any?>("plan" to "pro").apply { put("self", this) }

        val coerced = EventAttributes.coerce(cyclic)
        val property = ProfileProperty.Custom("cyclic", cyclic)

        assertEquals("\"pro\"", coerced["plan"].toString())
        assertTrue(property.toJson().toString().isNotEmpty())
    }
}
