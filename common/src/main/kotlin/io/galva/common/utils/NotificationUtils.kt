package io.galva.common.utils

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build


object NotificationUtils {
    const val META_DEFAULT_ICON = "io.galva.sdk.default_notification_icon"
    const val META_DEFAULT_COLOR = "io.galva.sdk.default_notification_color"
    fun isCommunicationNotification(message: Map<String, String>): Boolean {
        return message.contains("communicationId")
    }

    fun showPushNotification(
        context: Context,
        notificationChannel: String,
        title: String,
        message: String,
        openIntent: Intent,
        closeIntent: Intent
    ) {
        val applicationInfo = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA
        )

        val metaData = applicationInfo.metaData
        val icon = metaData.getInt(META_DEFAULT_ICON, applicationInfo.icon)
        val color = metaData.getInt(META_DEFAULT_COLOR, Color.BLUE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, notificationChannel)
        } else {
            Notification.Builder(context)
        }
        val openIntent  = PendingIntent.getActivity(
            context,
            88,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closePendingIntent = PendingIntent.getBroadcast(
            context,
            66,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setSmallIcon(icon).setColor(color).setContentTitle(title).setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true)
            .setContentIntent(openIntent)
            .setDeleteIntent(closePendingIntent)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notificationId = System.currentTimeMillis().toInt()
        if (notificationManager.areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notificationManager.notify(notificationId, builder.build())
            }
        }
    }

}