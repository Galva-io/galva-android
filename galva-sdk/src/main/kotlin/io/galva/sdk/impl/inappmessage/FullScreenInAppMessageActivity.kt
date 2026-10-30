package io.galva.sdk.impl.inappmessage

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.galva.common.logger.Logger
import io.galva.common.utils.JsonUtils
import io.galva.iam.InAppMessageActivity
import io.galva.iam.bridge.AlertAction
import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.BridgeException
import io.galva.iam.bridge.ProductPriceRequest
import io.galva.iam.bridge.PurchaseRequest
import io.galva.iam.bridge.ShowAlertOptions
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult
import io.galva.network.service.ServiceResult
import io.galva.sdk.Galva
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@kotlinx.serialization.InternalSerializationApi
internal class FullScreenInAppMessageActivity : InAppMessageActivity() {
    override val logger: Logger get() = Galva.instance.logger

    private val viewModel: InAppMessageViewModel by viewModels(factoryProducer = {
        InAppMessageViewModelFactory(Galva.instance.billingManager, Galva.instance.iamService)
    })

    private var alertDialog: AlertDialog? = null

    /** Bridge work never crashes the app: a failure is logged and the request left unanswered. */
    private val guard = CoroutineExceptionHandler { _, t -> logger.error(t) { "In-app message bridge call failed" } }

    /**
     * After process death Android may recreate this activity before Galva is set up again; there
     * is no message state to restore then, so it closes instead of failing.
     */
    override fun canShowMessage(): Boolean = (Galva.instance.iamService != null).also { ready ->
        if (!ready) logger.info { "In-app message closed: Galva is not set up" }
    }

    companion object {
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_HTML_FILE_PATH = "html_file_path"
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_DEEP_LINK_PARAMETERS = "deep_link_parameters"

        fun createIntent(
            context: Context,
            messageId: String,
            bundleHtmlFilePath: String,
            payload: JsonObject?,
            deepLinkParameters: Map<String, String> = emptyMap(),
        ): Intent {
            return Intent(context, FullScreenInAppMessageActivity::class.java).apply {
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_HTML_FILE_PATH, bundleHtmlFilePath)
                putExtra(EXTRA_PAYLOAD, payload?.toString())
                putExtra(EXTRA_DEEP_LINK_PARAMETERS, Bundle().apply { deepLinkParameters.forEach(::putString) })

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun getPayloadJsonData(): String? {
        return intent.getStringExtra(EXTRA_PAYLOAD)
    }

    override fun getDeepLinkParameters(): Map<String, String> {
        val bundle = intent.getBundleExtra(EXTRA_DEEP_LINK_PARAMETERS) ?: return emptyMap()
        return bundle.keySet().mapNotNull { key -> bundle.getString(key)?.let { key to it } }.toMap()
    }

    override fun getHtmlFilePath(): String? = intent.getStringExtra(EXTRA_HTML_FILE_PATH)

    override fun onDestroy() {
        alertDialog?.dismiss()
        alertDialog = null
        // A configuration change recreates the activity; anything else means the message is gone.
        if (!isChangingConfigurations) {
            intent.getStringExtra(EXTRA_MESSAGE_ID)?.let(ScreenMessageOverlay::notifyDismissed)
        }
        super.onDestroy()
    }

    override fun onGetPageContext(requestId: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO + guard) {
            val windowInsets = ViewCompat.getRootWindowInsets(window.decorView)
            val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            val pageContext =
                viewModel.doGetPageContext(this@FullScreenInAppMessageActivity, insets)
            withContext(Dispatchers.Main) {
                sendMessageToWebView(requestId, JsonUtils.defaultJson.encodeToJsonElement(pageContext))
            }
        }
    }

    override fun onRequestPurchase(requestId: String, request: PurchaseRequest) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID).orEmpty()
        viewModel.viewModelScope.launch(guard) {
            val answer = viewModel.purchase(this@FullScreenInAppMessageActivity, request, messageId)
            if (isDestroyed || isFinishing) return@launch
            answer.fold(
                onSuccess = { sendMessageToWebView(requestId, it) },
                onFailure = { sendErrorToWebView(requestId, (it as BridgeException).error) },
            )
        }
    }

    override fun onGetProductPrice(requestId: String, request: ProductPriceRequest) {
        viewModel.viewModelScope.launch(Dispatchers.IO + guard) {
            val productPrice = viewModel.getProductPrice(request.productId, request.basePlanId, request.offerId)
            val jsonPayload = JsonUtils.defaultJson.encodeToJsonElement(productPrice)
            withContext(Dispatchers.Main) {
                sendMessageToWebView(requestId, jsonPayload)
            }
        }
    }

    /**
     * Same contract as iOS: any HTTP response (4xx / 5xx included) is a result; only an invalid
     * path or a transport failure is an error. `shouldRetry` requests are persisted and
     * acknowledged right away with `{ queued: true }`.
     */
    override fun onDoApiFetch(requestId: String, payload: APIFetchRequest) {
        if (Galva.instance.iamService?.isValidPath(payload.path) != true) {
            sendErrorToWebView(
                requestId,
                BridgeError(BridgeError.Code.INVALID_PAYLOAD, "apiFetch path is invalid or targets a disallowed origin"),
            )
            return
        }
        viewModel.viewModelScope.launch(Dispatchers.IO + guard) {
            if (payload.shouldRetry) {
                val queue = Galva.instance.apiFetchQueue
                val queued = queue != null && runCatching { queue.enqueue(payload) }
                    .onFailure { logger.error(it) { "apiFetch: could not persist request" } }
                    .isSuccess
                withContext(Dispatchers.Main) {
                    if (queued) {
                        sendMessageToWebView(requestId, buildJsonObject { put("queued", true) })
                    } else {
                        sendErrorToWebView(
                            requestId,
                            BridgeError(BridgeError.Code.API_REQUEST_FAILED, "Durable request queue unavailable"),
                        )
                    }
                }
                return@launch
            }
            val response = viewModel.doApiFetch(payload)
            withContext(Dispatchers.Main) {
                when (response) {
                    is ServiceResult.Success<APIFetchResult> ->
                        sendMessageToWebView(requestId, JsonUtils.defaultJson.encodeToJsonElement(response.value))

                    is ServiceResult.Error -> sendErrorToWebView(
                        requestId,
                        BridgeError(BridgeError.Code.API_REQUEST_FAILED, "apiFetch transport error: ${response.error.message}"),
                    )

                    is ServiceResult.Failure -> sendErrorToWebView(
                        requestId,
                        BridgeError(BridgeError.Code.API_REQUEST_FAILED, "apiFetch failed: HTTP ${response.status}"),
                    )

                    is ServiceResult.UnknownError -> sendErrorToWebView(
                        requestId,
                        BridgeError(BridgeError.Code.API_REQUEST_FAILED, "apiFetch failed: ${response.exception.message}"),
                    )
                }
            }
        }
    }

    override fun onShowAlert(requestId: String, option: ShowAlertOptions) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@launch
            try {
                showAlertDialog(requestId, option)
            } catch (e: Exception) {
                // e.g. BadTokenException when the window went away meanwhile.
                logger.warn(e) { "showAlert: could not show the dialog" }
                sendErrorToWebView(requestId, BridgeError(BridgeError.Code.NO_ACTIVE_MESSAGE, "Alert could not be shown"))
            }
        }
    }

    /**
     * Resolves with `{ actionId }` of the tapped action. The cancel action takes the negative
     * button; the others fill the positive, neutral and (when free) negative slots in order. An
     * alert that closes without an action answers `noActiveMessage`, like iOS.
     */
    private fun showAlertDialog(requestId: String, options: ShowAlertOptions) {
        var answered = false
        fun answer(actionId: String) {
            if (answered) return
            answered = true
            sendMessageToWebView(requestId, buildJsonObject { put("actionId", JsonPrimitive(actionId)) })
        }

        val builder = AlertDialog.Builder(this).setCancelable(false)
        options.title?.let { builder.setTitle(it) }
        options.message?.let { builder.setMessage(it) }

        val slots = mutableListOf<(CharSequence, DialogInterface.OnClickListener) -> Unit>(
            { title, listener -> builder.setPositiveButton(title, listener) },
            { title, listener -> builder.setNeutralButton(title, listener) },
            { title, listener -> builder.setNegativeButton(title, listener) },
        )
        options.actions.firstOrNull { it.style == AlertAction.Style.CANCEL }?.let { cancel ->
            val negative = slots.removeAt(slots.lastIndex)
            negative(cancel.title, DialogInterface.OnClickListener { _, _ -> answer(cancel.id) })
        }
        options.actions.filter { it.style != AlertAction.Style.CANCEL }.forEachIndexed { index, action ->
            slots[index](action.title, DialogInterface.OnClickListener { _, _ -> answer(action.id) })
        }

        builder.setOnDismissListener {
            alertDialog = null
            if (!answered) {
                answered = true
                sendErrorToWebView(
                    requestId,
                    BridgeError(BridgeError.Code.NO_ACTIVE_MESSAGE, "Alert was dismissed before any action was tapped"),
                )
            }
        }
        alertDialog = builder.show()
    }
}
