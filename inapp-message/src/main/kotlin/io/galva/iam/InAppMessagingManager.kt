package io.galva.iam

import io.galva.common.InternalGalvaApi
import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.Flow

@InternalGalvaApi
interface InAppMessagingManager {
    /**
     * Broadcast of pending messages, same model as the iOS SDK: the SDK polls on every return to
     * the foreground (cold start included) and on [checkForMessages], and publishes at most one
     * message per poll. Polling does not depend on collectors; a message published while nothing
     * collects is not shown and is not published again during this app session.
     */
    val messages: Flow<Message>

    /** Presents [message]. Calling it again for a message that is already being shown is a no-op. */
    fun showMessage(context: Activity, message: Message)

    /** Presents [message] when [context] is an Activity; otherwise there is nothing to show it on. */
    fun showMessage(context: Context, message: Message) {
        if (context is Activity) showMessage(context, message)
    }

    /** Shows a message opened from a deep link; its query parameters reach the page as `window.galvaDeepLinkParams`. */
    fun showMessage(context: Context, message: Message, deepLinkParameters: Map<String, String>) {
        showMessage(context, message)
    }

    /** Polls now, outside the foreground cadence (e.g. after a user action that should trigger a workflow). */
    fun checkForMessages() = Unit

    /** Whether [messageId] already went on screen during this app session. */
    fun hasPresented(messageId: String): Boolean = false

    /** Clears per-identity state (seen / presented messages, display budget); called on logout. */
    fun reset() = Unit
}
