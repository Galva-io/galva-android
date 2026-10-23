package io.galva.iam

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.common.utils.JsonUtils
import io.galva.iam.bridge.BridgeResponse
import io.galva.iam.bridge.BridgeStringResponse
import io.galva.iam.bridge.JSBridge
import io.galva.iam.bridge.JSBridgeCallback
import io.galva.iam.bridge.ShowAlertOptions
import io.galva.network.request.APIFetchRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import java.io.File

abstract class InAppMessageActivity : ComponentActivity(), JSBridgeCallback {

    abstract fun getPayloadJsonData(): String?

    abstract fun getHtmlFilePath(): String

    /** SDK logger; bridge traffic is only logged at DEBUG / VERBOSE. */
    protected open val logger: Logger get() = NoOpLogger

    private lateinit var webView: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        createViews()
        configureBackPress()
    }

    private fun createViews() {
        val bundleUri = Uri.fromFile(File(getHtmlFilePath()))
        // Process-wide switch: only turn it on when the host app itself is a debuggable build.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true

                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                // Needed to load the cached bundle from app storage; nothing else is reachable
                // because navigation is restricted to that single file below.
                allowFileAccess = true
                allowContentAccess = false
            }
            webViewClient = BundleOnlyWebViewClient(bundleUri)
            addJavascriptInterface(JSBridge(this@InAppMessageActivity, logger), "galva")

            loadUrl(bundleUri.toString())
        }
        setContentView(webView)
    }

    /**
     * Allows only the bundle file to load in the WebView, like the iOS SDK. Links inside a
     * message go through the `openDeepLink` / `openManageSubscription` bridge methods instead.
     */
    private inner class BundleOnlyWebViewClient(private val bundleUri: Uri) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url
            if (target.scheme == "file" && target.path == bundleUri.path) return false
            logger.warn { "In-app message navigation blocked: ${target.scheme}://${target.host.orEmpty()}" }
            return true
        }
    }

    private fun configureBackPress() {
        onBackPressedDispatcher.addCallback(
            this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {

                    when {
                        webView.canGoBack() -> webView.goBack()
                        else -> finish()
                    }
                }
            })
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.apply {
            stopLoading()
            clearHistory()
            removeJavascriptInterface("galva")
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    override fun onReady(requestId: String,) {

    }

    override fun onDismiss(requestId: String,) {
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            lifecycleScope.launch(Dispatchers.Main) {
                finish()
            }
        }
    }

    override fun onGetPageContext(requestId: String,) {

    }

    override fun onGetMessageData(requestId: String,) {
        val messagePayload = getPayloadJsonData() ?: return
        sendStringMessageToWebView(requestId,messagePayload)
    }

    override fun onRequestPurchase(requestId: String,productId: String,basePlanId:String, offerId: String) {

    }

    override fun onOpenManageSubscription(requestId: String,url: String) {
        openLink(url)
    }

    override fun onOpenDeepLink(requestId: String,url: String) {
        openLink(url)
    }

    override fun onGetProductPrice(requestId: String,productId: String,basePlanId:String, offerId: String?) {

    }

    override fun onDoApiFetch(requestId: String, payload: APIFetchRequest) {

    }

    @OptIn(InternalSerializationApi::class)
    override fun onShowAlert(requestId: String, option: ShowAlertOptions) {

    }

    private fun openLink(url: String) {
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            lifecycleScope.launch(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(url)
                }
                startActivity(intent)
            }
        }
    }

    protected fun sendMessageToWebView(requestId: String, jsonPayload: JsonElement){
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)){
            lifecycleScope.launch(Dispatchers.Main){
                val payload = JsonUtils.defaultJson.encodeToString(
                    BridgeResponse(result = jsonPayload, requestId = requestId)
                )
                logger.verbose { "Bridge out: $payload" }
                webView.evaluateJavascript("window.handleNativeMessage($payload)", null)
            }
        }

    }

    protected fun sendStringMessageToWebView(requestId: String, jsonPayload: String){
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)){
            lifecycleScope.launch(Dispatchers.Main){
                val payload = JsonUtils.defaultJson.encodeToString(
                    BridgeStringResponse(result = jsonPayload, requestId = requestId)
                )
                logger.verbose { "Bridge out: $payload" }
                webView.evaluateJavascript("window.handleNativeMessage($payload)", null)
            }
        }

    }
}
