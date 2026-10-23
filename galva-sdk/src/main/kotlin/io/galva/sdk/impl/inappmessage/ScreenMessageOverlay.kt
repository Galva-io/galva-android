package io.galva.sdk.impl.inappmessage

import android.content.Context
import io.galva.iam.MessageOverlay
import io.galva.network.response.MessageResponse
import kotlinx.serialization.InternalSerializationApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class ScreenMessageOverlay : MessageOverlay {
    @OptIn(InternalSerializationApi::class)
    override fun present(
        context: Context,
        message: MessageResponse,
        bundleFile: File,
        deepLinkParameters: Map<String, String>,
        onDismiss: () -> Unit,
    ) {
        dismissCallbacks[message.id] = onDismiss
        val intent = FullScreenInAppMessageActivity.createIntent(
            context,
            message.id,
            bundleFile.absolutePath,
            message.payload,
            deepLinkParameters,
        )
        context.startActivity(intent)
    }

    internal companion object {
        private val dismissCallbacks = ConcurrentHashMap<String, () -> Unit>()

        /** Called by the message activity once it closes for good. */
        fun notifyDismissed(messageId: String) {
            dismissCallbacks.remove(messageId)?.invoke()
        }
    }
}
