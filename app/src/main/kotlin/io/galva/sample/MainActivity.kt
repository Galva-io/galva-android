package io.galva.sample

import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import io.galva.sdk.Galva
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val launcherPostPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()){
        if(it){
            Galva.instance.handlePush(
                mapOf(
                    "communicationId" to UUID.randomUUID().toString(),
                    "title" to "Test Push",
                    "body" to "This is a test push message"
                )
            )
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launcherPostPermission.launch(
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }else{
            Galva.instance.handlePush(
                mapOf(
                    "communicationId" to UUID.randomUUID().toString(),
                    "title" to "Test Push",
                    "body" to "This is a test push message"
                )
            )
        }

    }
}