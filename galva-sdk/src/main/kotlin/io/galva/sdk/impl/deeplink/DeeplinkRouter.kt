package io.galva.sdk.impl.deeplink

import android.net.Uri
import io.galva.common.logger.Logger

internal class DeeplinkRouter(
    private val handlers: List<DeeplinkRouterHandler>,
    private val pendingDeeplinkStore: PendingDeeplinkStore,
    private val logger: Logger,
) {
    suspend fun handle(uri: Uri): Boolean {
        if (!hasSupportedScheme(uri)) return false

        val result = dispatch(uri) ?: return false
        when (result) {
            DeeplinkHandlingResult.HANDLED -> {
                logger.debug { "Galva link opened: $uri" }
                pendingDeeplinkStore.clear()
            }
            DeeplinkHandlingResult.DEFERRED_UNTIL_IDENTIFIED -> {
                logger.info { "Galva link saved until the user is identified: $uri" }
                // A single storage key gives latest-deeplink-wins semantics.
                pendingDeeplinkStore.save(uri)
            }
            DeeplinkHandlingResult.REJECTED -> logger.warn { "Galva link ignored, it has no communicationId: $uri" }
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
            DeeplinkHandlingResult.HANDLED -> {
                logger.debug { "Saved Galva link opened after identify: $pendingUri" }
                pendingDeeplinkStore.clear()
            }

            DeeplinkHandlingResult.REJECTED,
            null -> pendingDeeplinkStore.clear()

            DeeplinkHandlingResult.DEFERRED_UNTIL_IDENTIFIED -> Unit
        }
    }

    private suspend fun dispatch(uri: Uri): DeeplinkHandlingResult? {
        val handler = handlers.firstOrNull { it.canHandle(uri) }
        if (handler == null) logger.warn { "Galva link not recognized (unknown route): $uri" }
        return handler?.handle(uri)
    }

    private fun hasSupportedScheme(uri: Uri): Boolean =
        uri.scheme?.startsWith(SCHEME_PREFIX, ignoreCase = true) == true

    private companion object {
        const val SCHEME_PREFIX = "gv"
    }
}
