package io.galva.iam

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.Flow

interface InAppMessagingManager {
    /** Stream of winning messages — 0 or 1 per foreground poll. */
    val messages: Flow<Message>

    fun showMessage(context: Activity, message: Message)

    fun showMessage(context: Context, message: Message) {
        require(context is Activity) { "An Activity context is required" }
        showMessage(context, message)
    }

    /** Cancels in-flight automatic message fetches without cancelling their collectors. */
    fun cancelFetchMessage() = Unit

    /** Requests another automatic fetch for active message collectors only. */
    fun resumeFetchMessage() = Unit
}
