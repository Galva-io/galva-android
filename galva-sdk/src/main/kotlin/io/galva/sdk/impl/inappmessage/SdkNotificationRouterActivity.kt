package io.galva.sdk.impl.inappmessage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.galva.sdk.Galva

internal class SdkNotificationRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val communicationId = intent.getStringExtra("communicationId")
        if (communicationId == null) {
            finish()
        } else {
            Galva.instance.trackPushEvent(communicationId, "push_communication_opened")
            launchConsumerMainActivity()
            finish()
        }
    }

    private fun launchConsumerMainActivity() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

                // Forward the notification extras to the launcher activity.
                launchIntent.putExtras(intent)

                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Galva.instance.logger.warn(e) { "Could not open the app from a Galva notification" }
        }
    }
}