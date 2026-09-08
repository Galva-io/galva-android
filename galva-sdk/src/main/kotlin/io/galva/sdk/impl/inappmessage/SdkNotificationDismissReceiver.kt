package io.galva.sdk.impl.inappmessage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.core.protocol.operation.APIOperation
import io.galva.sdk.Galva
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class SdkNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val communicationId = intent.getStringExtra("communicationId")
        if (communicationId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                Galva.instance.operationManager.recordOperation(
                    APIOperation.TrackPushNotification(
                        communicationId, "push_communication_dismissed",
                        DateTimeFormatUtils.format(Calendar.getInstance())
                    )
                )
            }
        }
    }
}