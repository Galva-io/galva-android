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

        inAppMessagingManager.cancelFetchMessage()
        return try {
            identityManager.awaitInitialized()

            if (identityManager.userId.isNullOrBlank()) {
                DeeplinkHandlingResult.DEFERRED_UNTIL_IDENTIFIED
            } else {
                inAppMessagingManager.showMessage(appContext, Message(communicationId))
                DeeplinkHandlingResult.HANDLED
            }
        } finally {
            inAppMessagingManager.resumeFetchMessage()
        }
    }

    private fun Uri.queryParameter(name: String): String? =
        runCatching { getQueryParameter(name) }.getOrNull()

    private companion object {
        const val ROUTE_NAME = "openCommunication"
        const val COMMUNICATION_ID = "communicationId"
    }
}
