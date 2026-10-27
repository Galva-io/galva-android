package io.galva.sdk.impl.inappmessage

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.galva.common.logger.NoOpLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The library declares no targetSdk, so Robolectric would default to an SDK without channels or
// the notification permission.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SdkNotificationHandlerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val push = mapOf(
        "communicationId" to "c-1", "title" to "Your trial ends today", "body" to "Keep Pro", "campaign" to "trial",
    )

    @Before
    fun grantNotificationPermission() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `posts the notification and reports it`() {
        val posted = SdkNotificationHandler(context, NoOpLogger).handleNotification(push)

        assertTrue(posted)
        val notification = shadowOf(notificationManager).getNotification("c-1".hashCode())
        assertEquals("Your trial ends today", shadowOf(notification).contentTitle)
    }

    @Test
    fun `tap and dismiss intents carry the full push data`() {
        SdkNotificationHandler(context, NoOpLogger).handleNotification(push)
        val notification = shadowOf(notificationManager).getNotification("c-1".hashCode())

        assertEquals(push, NotificationEvents.payload(shadowOf(notification.contentIntent).savedIntent))
        assertEquals(push, NotificationEvents.payload(shadowOf(notification.deleteIntent).savedIntent))
    }

    @Test
    fun `nothing is posted when the app's notifications are off`() {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        assertFalse(SdkNotificationHandler(context, NoOpLogger).handleNotification(push))
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `nothing is posted without the notification permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(SdkNotificationHandler(context, NoOpLogger).handleNotification(push))
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `nothing is posted when the user blocked the Galva channel`() {
        val handler = SdkNotificationHandler(context, NoOpLogger)
        notificationManager.createNotificationChannel(
            NotificationChannel(SdkNotificationHandler.CHANNEL_ID, "Galva", NotificationManager.IMPORTANCE_NONE)
        )

        assertFalse(handler.handleNotification(push))
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `a push without title, body or communication id is not shown`() {
        val handler = SdkNotificationHandler(context, NoOpLogger)

        listOf("title", "body", "communicationId").forEach { key ->
            assertFalse("missing $key", handler.handleNotification(push - key))
        }
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `event attributes are the push data plus id, like iOS`() {
        val attributes = NotificationEvents.attributes(push + ("id" to "from-payload"))

        assertEquals(push + ("id" to "c-1"), attributes)
    }

    @Test
    fun `an intent without the payload extra has no payload`() {
        assertNull(NotificationEvents.payload(Intent()))
    }
}
