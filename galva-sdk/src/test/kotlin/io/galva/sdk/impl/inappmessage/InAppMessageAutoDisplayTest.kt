package io.galva.sdk.impl.inappmessage

import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import io.galva.common.logger.NoOpLogger
import io.galva.iam.InAppMessagingManager
import io.galva.iam.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper

/** `autoDisplayInAppMessages`: which activity a published message is shown on. */
@RunWith(RobolectricTestRunner::class)
class InAppMessageAutoDisplayTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = RecordingManager()

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `activity resumed before configure is used when configure gets it`() {
        // Flutter: MainActivity is on screen before Dart calls configure.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        startAutoDisplay(hostActivity = activity)

        publish(Message("m1"))

        assertEquals(listOf(activity to "m1"), manager.shown)
    }

    @Test
    fun `activity resumed before configure is unknown without it`() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup()
        startAutoDisplay(hostActivity = null)

        publish(Message("m1"))

        assertEquals(emptyList<Pair<Activity, String>>(), manager.shown)
    }

    @Test
    fun `host activity that is not resumed is ignored`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().pause().get()
        startAutoDisplay(hostActivity = activity)

        publish(Message("m1"))

        assertEquals(emptyList<Pair<Activity, String>>(), manager.shown)
    }

    @Test
    fun `activity resumed after configure is used`() {
        startAutoDisplay(hostActivity = null)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        publish(Message("m1"))

        assertEquals(listOf(activity to "m1"), manager.shown)
    }

    @Test
    fun `host activity paused later is no longer used`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        startAutoDisplay(hostActivity = controller.get())
        controller.pause()

        publish(Message("m1"))

        assertEquals(emptyList<Pair<Activity, String>>(), manager.shown)
    }

    private fun startAutoDisplay(hostActivity: Activity?) {
        InAppMessageAutoDisplay(application, scope, NoOpLogger).apply {
            register(hostActivity)
            start(manager)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun publish(message: Message) {
        manager.messages.tryEmit(message)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class RecordingManager : InAppMessagingManager {
        override val messages = MutableSharedFlow<Message>(extraBufferCapacity = 8)
        val shown = mutableListOf<Pair<Activity, String>>()

        override fun showMessage(context: Activity, message: Message) {
            shown += context to message.id
        }
    }
}
