package io.galva.sdk.impl.inappmessage

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import io.galva.billing.model.BillingLaunchState
import io.galva.common.network.ExponentialBackoffRetryPolicy
import io.galva.common.network.RetryPolicy
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.common.utils.JsonUtils
import io.galva.iam.InAppMessageActivity
import io.galva.iam.PageContext
import io.galva.iam.bridge.ShowAlertOptions
import io.galva.network.android.HttpAPIIamService
import io.galva.network.android.OkHttpClientBuilder
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult
import io.galva.network.service.ServiceResult
import io.galva.sdk.BuildConfig
import io.galva.sdk.Galva
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.Calendar
import java.util.Locale

@kotlinx.serialization.InternalSerializationApi
class FullScreenInAppMessageActivity : InAppMessageActivity() {
    private val viewModel: InAppMessageViewModel by viewModels(factoryProducer = {
        val httpClient = OkHttpClientBuilder().apiKey(Galva.instance.configuration.apiKey)
            .sdkVersion("android/${io.galva.sdk.BuildConfig.SDK_VERSION}")
            .logger(Galva.instance.logger).build()
        InAppMessageViewModelFactory(
            Galva.instance.billingManager,
            HttpAPIIamService(
                Galva.instance.configuration.env.baseAPIUrl,
                httpClient,
                Galva.instance.logger,
                ExponentialBackoffRetryPolicy()
            )
        )
    })

    companion object {
        private const val EXTRA_HTML_FILE_PATH = "html_file_path"
        private const val EXTRA_PAYLOAD = "payload"

        fun createIntent(
            context: Context,
            bundleHtmlFilePath: String,
            payload: JsonObject?,
        ): Intent {
            return Intent(context, FullScreenInAppMessageActivity::class.java).apply {
                putExtra(EXTRA_HTML_FILE_PATH, bundleHtmlFilePath)
                putExtra(EXTRA_PAYLOAD, payload?.toString())

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    override fun getPayloadJsonData(): String? {
        return intent.getStringExtra(EXTRA_PAYLOAD)
    }

    override fun getHtmlFilePath(): String {
        return intent.getStringExtra(EXTRA_HTML_FILE_PATH)
            ?: throw IllegalArgumentException("HTML file path is required")
    }

    override fun onGetPageContext(requestId: String) {
        super.onGetPageContext(requestId)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val windowInsets = ViewCompat.getRootWindowInsets(window.decorView)
            val insets = windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            val pageContext =
                viewModel.doGetPageContext(this@FullScreenInAppMessageActivity, insets)
            withContext(Dispatchers.Main) {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    sendMessageToWebView(
                        requestId, JsonUtils.defaultJson.encodeToJsonElement(pageContext)
                    )
                }
            }
        }
    }


    override fun onRequestPurchase(
        requestId: String,
        productId: String,
        basePlanId: String,
        offerId: String
    ) {
        super.onRequestPurchase(requestId, productId, basePlanId, offerId)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            viewModel.launchBillingFlow(
                this@FullScreenInAppMessageActivity,
                productId,
                basePlanId,
                offerId
            )
                .collectLatest { state ->
                    withContext(Dispatchers.Main.immediate) {
                        if (isDestroyed.not() && isFinishing.not()) {
                            val result: JsonObject? = when (state) {
                                is BillingLaunchState.Cancelled -> {
                                    JsonObject(
                                        mapOf(
                                            "outcome" to JsonPrimitive("cancelled"),
                                        )
                                    )

                                }

                                is BillingLaunchState.Completed -> {
                                    JsonObject(
                                        mapOf(
                                            "outcome" to JsonPrimitive("completed"),
                                            "transaction" to JsonObject(
                                                mapOf(
                                                    "id" to JsonPrimitive(state.orderId),
                                                    "productId" to JsonPrimitive(productId),
                                                    "basePlanId" to JsonPrimitive(basePlanId),
                                                    "offerId" to JsonPrimitive(offerId),
                                                    "purchaseDate" to JsonPrimitive(
                                                        DateTimeFormatUtils.format(
                                                            Calendar.getInstance().apply {
                                                                timeInMillis =
                                                                    state.purchaseTimestamp
                                                            })
                                                    ),
                                                    "verified" to JsonPrimitive(state.verified)
                                                )
                                            )
                                        )
                                    )

                                }

                                is BillingLaunchState.Failed -> {
                                    JsonObject(
                                        mapOf(
                                            "outcome" to JsonPrimitive("cancelled"),
                                        )
                                    )
                                }

                                is BillingLaunchState.Launching -> {
                                    null
                                }

                                is BillingLaunchState.Showing -> {
                                    // ask to buy
                                   null
                                }
                            }
                            result?.let { resultToSend ->
                                sendMessageToWebView(
                                    requestId, resultToSend
                                )
                            }

                        }
                    }
                }
        }

    }

    override fun onGetProductPrice(
        requestId: String, productId: String, basePlanId: String, offerId: String?
    ) {
        super.onGetProductPrice(requestId, productId, basePlanId, offerId)
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val productPrice = viewModel.getProductPrice(productId, basePlanId, offerId)
            val jsonPayload = JsonUtils.defaultJson.encodeToJsonElement(productPrice)
            withContext(Dispatchers.Main) {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    sendMessageToWebView(requestId, jsonPayload)
                }
            }
        }
    }

    override fun onDoApiFetch(requestId: String, payload: APIFetchRequest) {
        super.onDoApiFetch(requestId, payload)
        CoroutineScope(Dispatchers.IO).launch {
            val response = viewModel.doApiFetch(payload)
            if (response is ServiceResult.Success<APIFetchResult>) {
                val jsonPayload = JsonUtils.defaultJson.encodeToJsonElement(response.value)
                withContext(Dispatchers.Main) {
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        sendMessageToWebView(requestId, jsonPayload)
                    }
                }
            } else {
                Galva.instance.logger.error {
                    "API fetch failed response: ${response}"
                }
            }

        }
    }

    @OptIn(InternalSerializationApi::class)
    override fun onShowAlert(requestId: String, option: ShowAlertOptions) {
        super.onShowAlert(requestId, option)
        lifecycleScope.launch(Dispatchers.Main) {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                showAlertDialog(requestId, option)
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun showAlertDialog(requestId: String, option: ShowAlertOptions) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(option.title)
            .setMessage(option.message).setCancelable(false)

        option.actions.take(3).forEach { action ->
            when (action.style) {
                "default" -> {
                    builder.setPositiveButton(action.title) { dialog, _ ->
                        sendMessageToWebView(
                            requestId, JsonObject(mapOf(
                                "actionId" to JsonPrimitive(action.id)
                            ))
                        )
                        dialog.dismiss()
                    }
                }

                "cancel" -> {
                    builder.setNegativeButton(action.title) { dialog, _ ->
                        sendMessageToWebView(
                            requestId, JsonObject(mapOf(
                                "actionId" to JsonPrimitive(action.id)
                            ))
                        )
                        dialog.dismiss()
                    }
                }

                "destructive" -> {
                    builder.setNeutralButton(action.title) { dialog, _ ->
                        sendMessageToWebView(
                            requestId, JsonObject(mapOf(
                                "actionId" to JsonPrimitive(action.id)
                            ))
                        )
                        dialog.dismiss()
                    }
                }
            }

        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            sendMessageToWebView(
                requestId, JsonObject(mapOf(
                    "actionId" to JsonNull
                ))
            )
            dialog.dismiss()
        }

        builder.show()
    }
}