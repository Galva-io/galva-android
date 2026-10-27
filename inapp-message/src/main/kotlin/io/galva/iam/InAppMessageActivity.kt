package io.galva.iam

import io.galva.common.InternalGalvaApi
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.galva.common.logger.LogLevel
import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.common.utils.JsonUtils
import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.BridgeErrorResponse
import io.galva.iam.bridge.BridgeResponse
import io.galva.iam.bridge.BridgeStringResponse
import io.galva.iam.bridge.JSBridge
import io.galva.iam.bridge.JSBridgeCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

@InternalGalvaApi
abstract class InAppMessageActivity : ComponentActivity(), JSBridgeCallback {

    abstract fun getPayloadJsonData(): String?

    /** The downloaded bundle to show; null when it is unavailable (the activity then closes). */
    abstract fun getHtmlFilePath(): String?

    /** Query parameters of the deep link that opened this message, exposed as `window.galvaDeepLinkParams`. */
    open fun getDeepLinkParameters(): Map<String, String> = emptyMap()

    /** SDK logger; bridge traffic is only logged at DEBUG / VERBOSE. */
    protected open val logger: Logger get() = NoOpLogger

    /** Null when the message could not be set up; the activity then finishes right away. */
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        if (!canShowMessage() || !createViews()) {
            finish()
            return
        }
        configureBackPress()
    }

    /** False when what the message needs is unavailable (e.g. the SDK is not set up yet). */
    protected open fun canShowMessage(): Boolean = true

    private fun createViews(): Boolean {
        val htmlFilePath = getHtmlFilePath()
        if (htmlFilePath == null) {
            logger.warn { "In-app message closed: no bundle to show" }
            return false
        }
        val bundleUri = Uri.fromFile(File(htmlFilePath))
        return try {
            setContentView(createWebView(bundleUri))
            true
        } catch (e: Exception) {
            // WebView can be missing or mid-update on the device; never crash the host app over it.
            logger.error(e) { "In-app message closed: WebView unavailable" }
            false
        }
    }

    private fun createWebView(bundleUri: Uri): WebView {
        // Process-wide switch: only turn it on when the host app itself is a debuggable build.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        return WebView(this).apply {
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
            webViewClient = BundleOnlyWebViewClient(bundleUri, deepLinkParamsScript()).also { it.install(this) }
            webChromeClient = ConsoleForwardingChromeClient()
            addJavascriptInterface(JSBridge(this@InAppMessageActivity, logger), "galva")

            loadUrl(bundleUri.toString())
        }.also { webView = it }
    }

    /**
     * Allows only the bundle file to load in the WebView, like the iOS SDK. Links inside a
     * message go through the `openDeepLink` / `openManageSubscription` bridge methods instead.
     */
    private inner class BundleOnlyWebViewClient(
        private val bundleUri: Uri,
        private val startScript: String?,
    ) : WebViewClient() {
        private var injectOnPageStart = false

        fun install(webView: WebView) {
            if (startScript == null) return
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, startScript, setOf("*"))
            } else {
                injectOnPageStart = true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // Fallback for WebViews without document-start scripts: runs as early as possible.
            if (injectOnPageStart && startScript != null) view.evaluateJavascript(startScript, null)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url
            if (target.scheme == "file" && target.path == bundleUri.path) return false
            logger.warn { "In-app message navigation blocked: ${target.scheme}://${target.host.orEmpty()}" }
            return true
        }
    }

    /** `window.galvaDeepLinkParams = {...}` for messages opened from a deep link, like the iOS SDK. */
    private fun deepLinkParamsScript(): String? {
        val parameters = getDeepLinkParameters().takeIf { it.isNotEmpty() } ?: return null
        val json = JsonObject(parameters.mapValues { JsonPrimitive(it.value) })
        return "window.galvaDeepLinkParams = $json;"
    }

    /** Forwards the bundle's `console.*` output and uncaught errors to the SDK log at DEBUG. */
    private inner class ConsoleForwardingChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (!logger.isLoggable(LogLevel.DEBUG)) return false
            logger.debug {
                "WebView console.${consoleMessage.messageLevel().name.lowercase()}: ${consoleMessage.message()} " +
                    "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
            }
            return true
        }
    }

    private fun configureBackPress() {
        onBackPressedDispatcher.addCallback(
            this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {

                    val view = webView
                    if (view != null && view.canGoBack()) view.goBack() else finish()
                }
            })
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            clearHistory()
            removeJavascriptInterface("galva")
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }

    // Every bridge call is answered — void methods with null — so the bundle's Promise settles.

    override fun onReady(requestId: String) {
        sendMessageToWebView(requestId, JsonNull)
    }

    override fun onDismiss(requestId: String) {
        sendMessageToWebView(requestId, JsonNull)
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            lifecycleScope.launch(Dispatchers.Main) {
                finish()
            }
        }
    }

    override fun onGetMessageData(requestId: String) {
        val messagePayload = getPayloadJsonData()
        if (messagePayload == null) {
            sendErrorToWebView(
                requestId,
                BridgeError(BridgeError.Code.MESSAGE_DATA_UNAVAILABLE, "Resolved payload not available"),
            )
            return
        }
        sendStringMessageToWebView(requestId, messagePayload)
    }

    override fun onOpenManageSubscription(requestId: String, url: String) {
        openLink(requestId, url)
    }

    override fun onOpenDeepLink(requestId: String, url: String) {
        openLink(requestId, url)
    }

    override fun onBridgeError(requestId: String, error: BridgeError) {
        sendErrorToWebView(requestId, error)
    }

    private fun openLink(requestId: String, url: String) {
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    sendMessageToWebView(requestId, JsonPrimitive(true))
                } catch (e: Exception) {
                    // ActivityNotFoundException, or a SecurityException from the target app.
                    logger.warn { "Bridge: the requested URL cannot be opened (${e.javaClass.simpleName})" }
                    sendErrorToWebView(
                        requestId,
                        BridgeError(BridgeError.Code.URL_OPEN_FAILED, "URL cannot be opened"),
                    )
                }
            }
        }
    }

    protected fun sendErrorToWebView(requestId: String, error: BridgeError) {
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)){
            lifecycleScope.launch(Dispatchers.Main){
                val payload = JsonUtils.defaultJson.encodeToString(
                    BridgeErrorResponse(requestId = requestId, error = error)
                )
                logger.debug { "Bridge out (error): $payload" }
                webView?.evaluateJavascript("window.handleNativeMessage($payload)", null)
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
                webView?.evaluateJavascript("window.handleNativeMessage($payload)", null)
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
                webView?.evaluateJavascript("window.handleNativeMessage($payload)", null)
            }
        }

    }
}
