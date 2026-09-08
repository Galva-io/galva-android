package io.galva.sdk.impl.inappmessage

import android.content.Context
import io.galva.iam.InAppMessageActivity
import io.galva.iam.MessageOverlay
import io.galva.network.response.MessageResponse
import kotlinx.serialization.InternalSerializationApi
import java.io.File

class ScreenMessageOverlay : MessageOverlay {
    @OptIn(InternalSerializationApi::class)
    override fun present(
        context: Context, message: MessageResponse, bundleFile: File, onDismiss: () -> Unit
    ) {
        val intent = FullScreenInAppMessageActivity.createIntent(
            context,
            bundleFile.absolutePath,
            message.payload
        )
        context.startActivity(intent)
    }
}