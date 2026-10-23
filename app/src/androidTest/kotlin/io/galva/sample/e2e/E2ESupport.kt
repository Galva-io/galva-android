package io.galva.sample.e2e

import android.app.Activity
import android.util.Log
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import io.galva.core.protocol.identity.ProfileProperty
import io.galva.sample.R
import io.galva.sdk.Galva
import org.json.JSONObject
import org.junit.rules.ExternalResource
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Drives the demo control panel (MainActivity) like a user would. */
object ControlPanel {
    fun identify(userId: String) {
        type(R.id.user_id_input, userId)
        tap(R.id.identify_button)
    }

    fun setEmail(email: String) {
        type(R.id.email_input, email)
        tap(R.id.set_email_button)
    }

    fun logout() = tap(R.id.logout_button)
    fun toggleOptOut() = tap(R.id.opt_out_switch)
    fun checkForMessages() = tap(R.id.check_messages_button)
    fun openSampleDeepLink() = tap(R.id.open_deep_link_button)

    private fun type(id: Int, text: String) {
        SystemDialogs.dismissAnr()
        onView(withId(id)).perform(scrollTo(), replaceText(text), closeSoftKeyboard())
    }

    private fun tap(id: Int) {
        SystemDialogs.dismissAnr()
        onView(withId(id)).perform(scrollTo(), click())
    }
}

/**
 * A loaded emulator can show "<system app> isn't responding" over the app under test; the dialog
 * takes window focus, which Espresso needs. Answer "Wait" so the test talks to the app again.
 */
object SystemDialogs {
    fun dismissAnr() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(MAX_STACKED_DIALOGS) {
            val wait = device.findObject(By.res("android", "aerr_wait")) ?: return
            Log.w(E2EApplication.TAG, "Dismissing a system ANR dialog")
            wait.click()
            device.waitForIdle(DIALOG_SETTLE_MS)
        }
    }

    private const val MAX_STACKED_DIALOGS = 5
    private const val DIALOG_SETTLE_MS = 1_000L
}

/** Records activity lifecycle transitions in the app process, including the SDK's own activities. */
class ActivityTracker : ExternalResource(), ActivityLifecycleCallback {
    private val events = CopyOnWriteArrayList<Pair<String, Stage>>()

    override fun before() = ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(this)
    override fun after() = ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(this)

    override fun onActivityLifecycleChanged(activity: Activity, stage: Stage) {
        events += activity.javaClass.name to stage
    }

    fun count(activityClassName: String, stage: Stage): Int =
        events.count { it.first == activityClassName && it.second == stage }

    fun awaitStage(activityClassName: String, stage: Stage, timeoutMs: Long = MockBackend.DEFAULT_TIMEOUT_MS) =
        awaitCondition("${activityClassName.substringAfterLast('.')} to reach $stage", timeoutMs) {
            count(activityClassName, stage) > 0
        }
}

object InAppMessage {
    /** The SDK's message activity is internal, so it is matched by name. */
    const val ACTIVITY = "io.galva.sdk.impl.inappmessage.FullScreenInAppMessageActivity"
    private const val CLOSE_LABEL = "Close E2E"
    private const val UI_TIMEOUT_MS = 15_000L

    /** What the test bundle saw through the bridge, posted to the mock with apiFetch. */
    fun awaitBundleReport(communicationId: String): JSONObject =
        MockBackend.awaitRequest("bundle report (apiFetch) for $communicationId") { request ->
            request.method == "POST" && request.path == "/e2e/report" &&
                request.json().optJSONObject("messageData")?.optString("communicationId") == communicationId
        }.json()

    fun resolveRequests(communicationId: String): List<MockBackend.Request> =
        MockBackend.recordedRequests().filter {
            it.method == "POST" && it.path == "${MockBackend.COMMUNICATIONS}/$communicationId/resolve"
        }

    /** Checks the rendered page on screen, then taps its close button (the `dismiss` bridge call). */
    fun closeFromPage() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        awaitLabel(device, MockBackend.OFFER_TITLE)
        awaitLabel(device, CLOSE_LABEL).click()
    }

    // WebView exposes page text as either the node text or its content description.
    private fun awaitLabel(device: UiDevice, label: String): UiObject2 =
        awaitValue("\"$label\" on screen", UI_TIMEOUT_MS) {
            SystemDialogs.dismissAnr()
            device.findObject(By.text(label)) ?: device.findObject(By.desc(label))
        }
}

/**
 * Waits until everything the SDK recorded so far has reached the mock: the event queue is FIFO, so
 * once a fresh marker property is uploaded, every earlier event was uploaded too. Needs an app
 * activity in the foreground (the queue pauses in the background) and tracking opted in.
 */
fun drainSdkQueue() {
    val marker = UUID.randomUUID().toString()
    Galva.instance.updateProperties(ProfileProperty.Custom(FLUSH_MARKER, marker))
    MockBackend.awaitBatchMessage("queue flush marker") { it.optJSONObject("traits")?.optString(FLUSH_MARKER) == marker }
}

fun uniqueId(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

/** Identify messages carry the user id as `endUserId`. */
fun JSONObject.isIdentifyOf(userId: String) = optString("type") == "identify" && optString("endUserId") == userId

private const val FLUSH_MARKER = "e2e_flush_marker"
