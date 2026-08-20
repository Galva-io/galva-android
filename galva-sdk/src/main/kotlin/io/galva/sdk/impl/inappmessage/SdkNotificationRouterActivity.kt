package io.galva.sdk.impl.inappmessage

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
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
        Log.e("SdkNotificationRouterActivity", "communicationId: $communicationId")
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
                // Thêm flags để đảm bảo app mở lên đúng cách
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

                // (Tùy chọn) Truyền tiếp data từ Notification sang Main Activity nếu cần
                launchIntent.putExtras(intent)

                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}