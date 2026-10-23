package io.galva.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import io.galva.core.protocol.identity.ProfileProperty
import io.galva.sdk.Galva
import java.util.UUID

/**
 * Demo control panel: one button per SDK flow. The E2E suite in `src/androidTest` drives these
 * same views, so keep the view ids stable.
 */
class MainActivity : AppCompatActivity() {
    private val galva get() = Galva.instance

    private lateinit var status: TextView
    private lateinit var optOutSwitch: SwitchCompat

    // SDK calls only enqueue work, so the status line polls instead of wiring callbacks.
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresher = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) simulatePush()
            else Toast.makeText(this, R.string.toast_notifications_denied, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        optOutSwitch = findViewById(R.id.opt_out_switch)
        val userIdInput = findViewById<EditText>(R.id.user_id_input)
        val emailInput = findViewById<EditText>(R.id.email_input)

        onClick(R.id.identify_button) {
            val userId = userIdInput.text.toString().trim()
            if (userId.isEmpty()) {
                Toast.makeText(this, R.string.toast_user_id_required, Toast.LENGTH_SHORT).show()
            } else {
                galva.identify(userId)
            }
        }
        onClick(R.id.set_email_button) {
            galva.updateProperties(ProfileProperty.Email(emailInput.text.toString().trim()))
        }
        onClick(R.id.logout_button) { galva.logout() }
        // A click listener (not a checked-change one) so refreshStatus() can set the state silently.
        optOutSwitch.setOnClickListener { galva.setOptOut(optOutSwitch.isChecked) }
        onClick(R.id.check_messages_button) { galva.checkForMessages() }
        onClick(R.id.open_deep_link_button) {
            // Goes through the intent filter like a link tapped elsewhere; lands in onNewIntent.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SAMPLE_DEEP_LINK)).setPackage(packageName))
        }
        onClick(R.id.simulate_push_button) { simulatePushWithPermission() }

        // Skip on recreation (e.g. rotation): the link was already handed over.
        if (savedInstanceState == null) handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handed to Galva right away, so it is not kept with setIntent().
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(statusRefresher)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresher)
        super.onPause()
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        // false means "not a Galva link": a real app would route it itself here.
        galva.handleOpenURL(uri)
    }

    private fun refreshStatus() {
        status.text = getString(
            R.string.status_format,
            Galva.sdkVersion,
            galva.currentUserId,
            getString(if (galva.isAnonymous) R.string.status_anonymous else R.string.status_identified),
            galva.isOptedOut,
        )
        optOutSwitch.isChecked = galva.isOptedOut
    }

    private fun simulatePushWithPermission() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            simulatePush()
        }
    }

    /** The data payload a Galva FCM message carries; the SDK shows and tracks the notification. */
    private fun simulatePush() {
        galva.handlePush(
            mapOf(
                "communicationId" to UUID.randomUUID().toString(),
                "title" to "Test Push",
                "body" to "This is a test push message",
            )
        )
    }

    private inline fun onClick(id: Int, crossinline action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener { action() }
    }

    companion object {
        const val SAMPLE_COMMUNICATION_ID = "demo-communication"
        const val SAMPLE_DEEP_LINK =
            "gvdemo://openCommunication?communicationId=$SAMPLE_COMMUNICATION_ID&source=control-panel"
        private const val STATUS_REFRESH_MS = 1_000L
    }
}
