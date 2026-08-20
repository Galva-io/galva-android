package io.galva.sdk.impl.inappmessage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import io.galva.common.utils.NotificationUtils

class SdkNotificationHandler(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "galva_sdk_default_channel"
        private const val CHANNEL_NAME = "Galva SDK Notifications"
    }

    init {
        createNotificationChannel()
    }

    fun handleNotification(data: Map<String, String>) {
        val title = data["title"] ?: return
        val body = data["body"] ?: return
        val communicationId = data["communicationId"] ?: return
        val openIntent = Intent(context, SdkNotificationRouterActivity::class.java).apply {
            putExtra("communicationId", communicationId)
        }
        val closeIntent = Intent(context, SdkNotificationDismissReceiver::class.java).apply {
            putExtra("communicationId", communicationId)
        }
        NotificationUtils.showPushNotification(
            context, CHANNEL_ID, title, body, openIntent, closeIntent
        )
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