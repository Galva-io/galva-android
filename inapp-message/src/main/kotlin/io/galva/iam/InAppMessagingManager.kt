package io.galva.iam

import android.app.Activity
import kotlinx.coroutines.flow.Flow

interface  InAppMessagingManager {
    /** Stream of winning messages — 0 or 1 per foreground poll. */
    val messages: Flow<Message>

    fun showMessage(context: Activity,message: Message)

}