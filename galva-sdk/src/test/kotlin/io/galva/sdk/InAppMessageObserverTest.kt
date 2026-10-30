package io.galva.sdk

import android.os.Looper
import androidx.activity.ComponentActivity
import io.galva.common.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class InAppMessageObserverTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val logs = TestLogSink()
    private val galva = Galva(Logger.create("Galva", logs), scope)
    private val messages = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    private val received = mutableListOf<String>()
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()

    @After
    fun tearDown() = scope.cancel()

    private fun runMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun publish(id: String) {
        messages.tryEmit(Message(id))
        runMainLooper()
    }

    private fun observe(listener: InAppMessageListener = InAppMessageListener { received += it.id }) {
        galva.observe(activity.get(), messages, listener)
        runMainLooper()
    }

    @Test
    fun `messages reach the listener while the owner is resumed`() {
        observe()

        publish("m-1")
        publish("m-2")

        assertEquals(listOf("m-1", "m-2"), received)
    }

    @Test
    fun `messages published while paused are skipped, and delivery resumes with the owner`() {
        observe()

        activity.pause()
        publish("while-paused")
        activity.resume()
        runMainLooper()
        publish("after-resume")

        assertEquals(listOf("after-resume"), received)
    }

    @Test
    fun `observation ends when the owner is destroyed`() {
        observe()

        activity.pause().stop().destroy()
        runMainLooper()
        publish("after-destroy")

        assertTrue(received.isEmpty())
        assertEquals(0, messages.subscriptionCount.value)
    }

    @Test
    fun `a failing listener is logged and keeps receiving messages`() {
        observe { message ->
            check(message.id != "bad") { "listener bug" }
            received += message.id
        }

        publish("bad")
        publish("good")

        assertEquals(listOf("good"), received)
        assertTrue(logs.messages.toString(), logs.messages.any { "InAppMessageListener failed on message bad" in it })
    }

    @Test
    fun `observing an unconfigured SDK delivers nothing and does not throw`() {
        galva.observeInAppMessages(activity.get()) { received += it.id }
        runMainLooper()

        activity.pause().stop().destroy()
        runMainLooper()

        assertTrue(received.isEmpty())
    }
}
