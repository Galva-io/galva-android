package io.galva.sdk.impl.inappmessage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.galva.sdk.Galva

internal class SdkNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val communicationId = intent.getStringExtra("communicationId") ?: return
        // Keep the receiver alive until the event is persisted on the SDK queue.
        val pending = goAsync()
        Galva.instance.trackPushEvent(communicationId, "push_communication_dismissed") { pending.finish() }
    }
}
