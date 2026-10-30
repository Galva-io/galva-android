package io.galva.iam

import io.galva.common.InternalGalvaApi
import android.content.Context
import io.galva.network.response.MessageResponse
import java.io.File

@InternalGalvaApi
interface MessageOverlay {
    /**
     * Shows [message]. [deepLinkParameters] are the query parameters of the deep link that opened
     * it (exposed to the page as `window.galvaDeepLinkParams`); [onDismiss] runs once it closes.
     */
    fun present(
        context: Context,
        message: MessageResponse,
        bundleFile: File,
        deepLinkParameters: Map<String, String>,
        onDismiss: () -> Unit,
    )
}
