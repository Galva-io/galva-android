package io.galva.sdk.impl.deeplink

import android.net.Uri

internal interface DeeplinkRouterHandler {
    fun canHandle(uri: Uri): Boolean

    suspend fun handle(uri: Uri): DeeplinkHandlingResult
}

internal enum class DeeplinkHandlingResult {
    HANDLED,
    DEFERRED_UNTIL_IDENTIFIED,
    REJECTED,
}
