package io.galva.sdk.impl.inappmessage

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatActivity
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.core.protocol.operation.APIOperation
import io.galva.sdk.Galva
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class SdkNotificationRouterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val communicationId = intent.getStringExtra("communicationId")
        if (communicationId == null) {
            finish()
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                Galva.instance.operationManager.recordOperation(
                    APIOperation.TrackPushNotification(
                        communicationId,
                        "push_communication_opened",
                        DateTimeFormatUtils.format(Calendar.getInstance())
                    )
                )
            }
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