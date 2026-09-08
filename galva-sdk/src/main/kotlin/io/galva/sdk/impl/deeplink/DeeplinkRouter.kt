package io.galva.sdk.impl.deeplink

import android.net.Uri

internal class DeeplinkRouter(
    private val handlers: List<DeeplinkRouterHandler>,
    private val pendingDeeplinkStore: PendingDeeplinkStore,
) {
    suspend fun handle(uri: Uri): Boolean {
        if (!hasSupportedScheme(uri)) return false

        val result = dispatch(uri) ?: return false
        when (result) {
            DeeplinkHandlingResult.HANDLED -> pendingDeeplinkStore.clear()
            DeeplinkHandlingResult.DEFERRED_UNTIL_IDENTIFIED -> {
                // A single storage key gives latest-deeplink-wins semantics.
                pendingDeeplinkStore.save(uri)
            }
            DeeplinkHandlingResult.REJECTED -> Unit
        }
        return result != DeeplinkHandlingResult.REJECTED
    }

    suspend fun handlePending() {
        val pendingUri = pendingDeeplinkStore.load() ?: return
        if (!hasSupportedScheme(pendingUri)) {
            pendingDeeplinkStore.clear()
            return
        }

        when (dispatch(pendingUri)) {
            DeeplinkHandlingResult.HANDLED,
            DeeplinkHandlingResult.REJECTED,
            null -> pendingDeeplinkStore.clear()

            DeeplinkHandlingResult.DEFERRED_UNTIL_IDENTIFIED -> Unit
        }
    }

    private suspend fun dispatch(uri: Uri): DeeplinkHandlingResult? =
        handlers.firstOrNull { it.canHandle(uri) }?.handle(uri)

    private fun hasSupportedScheme(uri: Uri): Boolean =
        uri.scheme?.startsWith(SCHEME_PREFIX, ignoreCase = true) == true

    private companion object {
        const val SCHEME_PREFIX = "gv"
    }
}
