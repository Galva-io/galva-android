package io.galva.iam

import android.content.Context
import io.galva.network.response.MessageResponse
import java.io.File

interface MessageOverlay {
    fun present(
        context: Context,
        message: MessageResponse,
        bundleFile: File,
        onDismiss: () -> Unit,
    )
}