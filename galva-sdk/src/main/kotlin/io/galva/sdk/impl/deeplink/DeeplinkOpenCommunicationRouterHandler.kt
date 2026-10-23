package io.galva.sdk.impl.deeplink

import android.content.Context
import android.net.Uri
import io.galva.iam.InAppMessagingManager
import io.galva.iam.Message
import io.galva.identity.IdentityManager

internal class DeeplinkOpenCommunicationRouterHandler(
    context: Context,
    private val identityManager: IdentityManager,
    private val inAppMessagingManager: InAppMessagingManager,
) : DeeplinkRouterHandler {
    private val appContext = context.applicationContext

    override fun canHandle(uri: Uri): Boolean =
        uri.toString().contains(ROUTE_NAME)

    override suspend fun handle(uri: Uri): DeeplinkHandlingResult {
        val communicationId = uri.queryParameter(COMMUNICATION_ID)
            ?.takeIf(String::isNotBlank)
            ?: return DeeplinkHandlingResult.REJECTED

        identityManager.awaitInitialized()
        if (identityManager.userId.isNullOrBlank()) {
            return DeeplinkHandlingResult.DEFERRED_UNTIL_IDENTIFIED
        }
        // Already shown this session (e.g. the same link delivered again): stand down without
        // using the display budget, so other pending messages stay eligible.
        if (inAppMessagingManager.hasPresented(communicationId)) {
            return DeeplinkHandlingResult.HANDLED
        }
        // showMessage claims the display budget, which suppresses polling while this resolves.
        // All query parameters ride along into the page as `window.galvaDeepLinkParams`.
        inAppMessagingManager.showMessage(appContext, Message(communicationId), uri.queryParameters())
        return DeeplinkHandlingResult.HANDLED
    }

    private fun Uri.queryParameter(name: String): String? =
        runCatching { getQueryParameter(name) }.getOrNull()

    private fun Uri.queryParameters(): Map<String, String> =
        runCatching { queryParameterNames.associateWith { getQueryParameter(it).orEmpty() } }.getOrDefault(emptyMap())

    private companion object {
        const val ROUTE_NAME = "openCommunication"
        const val COMMUNICATION_ID = "communicationId"
    }
}
