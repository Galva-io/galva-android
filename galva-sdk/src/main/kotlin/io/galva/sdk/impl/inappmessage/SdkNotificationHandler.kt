package io.galva.sdk.impl.inappmessage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import io.galva.common.logger.Logger
import io.galva.common.utils.NotificationUtils

internal class SdkNotificationHandler(context: Context, private val logger: Logger) {
    private val context = context.applicationContext

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "galva_sdk_default_channel"
        private const val CHANNEL_NAME = "Galva SDK Notifications"
        private val REQUIRED_KEYS = listOf("title", "body", "communicationId")
    }

    init {
        createNotificationChannel()
    }

    /** Shows the Galva push in [data]; returns true only when the notification was actually posted. */
    fun handleNotification(data: Map<String, String>): Boolean {
        val missing = REQUIRED_KEYS.filter { data[it] == null }
        if (missing.isNotEmpty()) {
            logger.warn { "Galva push ignored: missing ${missing.joinToString()} in the data payload" }
            return false
        }
        val communicationId = data.getValue("communicationId")
        // The full push data rides on both intents so taps and dismissals are tracked with it.
        val openIntent = NotificationEvents.putPayload(Intent(context, SdkNotificationRouterActivity::class.java), data)
        val closeIntent = NotificationEvents.putPayload(Intent(context, SdkNotificationDismissReceiver::class.java), data)
        // One notification per communication: a re-delivered push replaces its own notification.
        val posted = NotificationUtils.showPushNotification(
            context, CHANNEL_ID, communicationId.hashCode(), data.getValue("title"), data.getValue("body"),
            openIntent, closeIntent,
        )
        if (posted) {
            logger.debug { "Galva notification $communicationId shown" }
        } else {
            logger.info {
                "Galva notification $communicationId not shown: notifications are off for the app, " +
                    "POST_NOTIFICATIONS is not granted or the \"$CHANNEL_NAME\" channel is blocked"
            }
        }
        return posted
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }
}