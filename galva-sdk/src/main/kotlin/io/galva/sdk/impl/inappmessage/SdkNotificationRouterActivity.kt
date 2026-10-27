package io.galva.sdk.impl.inappmessage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.galva.sdk.Galva

internal class SdkNotificationRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = NotificationEvents.payload(intent)
        if (payload == null) {
            finish()
        } else {
            Galva.instance.trackNotificationEvent(NotificationEvents.TAPPED, payload)
            launchConsumerMainActivity(payload)
            finish()
        }
    }

    private fun launchConsumerMainActivity(payload: Map<String, String>) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

                // Hand the push data to the launcher activity as plain string extras
                // (`communicationId`, `title`, `body`, …).
                payload.forEach { (key, value) -> launchIntent.putExtra(key, value) }

                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            Galva.instance.logger.warn(e) { "Could not open the app from a Galva notification" }
        }
    }
}