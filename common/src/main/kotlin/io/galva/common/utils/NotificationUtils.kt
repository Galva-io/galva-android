package io.galva.common.utils

import io.galva.common.InternalGalvaApi
import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build


@InternalGalvaApi
object NotificationUtils {
    const val META_DEFAULT_ICON = "io.galva.sdk.default_notification_icon"
    const val META_DEFAULT_COLOR = "io.galva.sdk.default_notification_color"
    fun isCommunicationNotification(message: Map<String, String>): Boolean {
        return message.contains("communicationId")
    }

    /**
     * Posts a notification. [notificationId] also keys both pending intents, so every
     * notification keeps its own extras instead of sharing (and overwriting) one pair.
     */
    fun showPushNotification(
        context: Context,
        notificationChannel: String,
        notificationId: Int,
        title: String,
        message: String,
        openIntent: Intent,
        closeIntent: Intent
    ) {
        val applicationInfo = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA
        )

        // metaData is null when the app declares no <meta-data> under <application>.
        val metaData = applicationInfo.metaData
        val icon = metaData?.getInt(META_DEFAULT_ICON, 0)?.takeIf { it != 0 } ?: applicationInfo.icon
        val colorResId = metaData?.getInt(META_DEFAULT_COLOR, 0) ?: 0
        val color = if (colorResId != 0) {
            try {
                context.getColor(colorResId)
            } catch (_: Resources.NotFoundException) {
                null
            }
        } else {
            null
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, notificationChannel)
        } else {
            Notification.Builder(context)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setSmallIcon(icon).apply {
            if(color != null){
                setColor(color)
            }
        }.setContentTitle(title).setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(closePendingIntent)
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
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
