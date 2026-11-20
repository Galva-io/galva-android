package io.galva.sdk.impl.inappmessage

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.galva.common.logger.Logger
import io.galva.iam.InAppMessagingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * `Configuration(autoDisplayInAppMessages = true)`: shows each published message on the activity
 * that is currently resumed — the Android counterpart of the iOS `.autoDisplayInAppMessages()`
 * modifier. A message published while no app activity is resumed is not shown.
 *
 * [register] runs inside `configure` so the first activity is tracked; [start] runs once the
 * message manager has been built in the background. An activity resumed before `configure`
 * (Flutter and other cross-platform apps configure from their UI) gets no `onActivityResumed`, so
 * [register] takes it from the context `configure` was given when that context is the resumed
 * activity.
 */
internal class InAppMessageAutoDisplay(
    private val application: Application,
    private val scope: CoroutineScope,
    private val logger: Logger,
) : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    fun register(hostActivity: Activity? = null) {
        application.registerActivityLifecycleCallbacks(this)
        if (hostActivity != null && hostActivity.isResumed() && !hostActivity.isSdkActivity()) {
            resumedActivity = WeakReference(hostActivity)
        }
    }

    fun start(manager: InAppMessagingManager) {
        scope.launch(Dispatchers.Main) {
            manager.messages.collect { message ->
                val activity = resumedActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                if (activity == null) {
                    logger.info { "In-app message ${message.id} not shown — no visible activity" }
                } else {
                    // One failure must not end the collection: later messages still show.
                    try {
                        manager.showMessage(activity, message)
                    } catch (e: Exception) {
                        logger.error(e) { "In-app message ${message.id} could not be shown" }
                    }
                }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        // The SDK's own activities (message, notification router) never host a message.
        if (!activity.isSdkActivity()) resumedActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity?.get() === activity) resumedActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun Activity.isSdkActivity(): Boolean = javaClass.name.startsWith(SDK_PACKAGE)

    // Only a LifecycleOwner (any AndroidX activity, FlutterActivity included) reports its state.
    private fun Activity.isResumed(): Boolean =
        !isFinishing && !isDestroyed &&
            (this as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    private companion object {
        const val SDK_PACKAGE = "io.galva.sdk."
    }
}
