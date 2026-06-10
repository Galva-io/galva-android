package io.galva.iam

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import io.galva.common.utils.JsonUtils
import io.galva.iam.bridge.BridgeMessage
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
import kotlinx.serialization.json.JsonObject

abstract class InAppMessageActivity : ComponentActivity(), JSBridgeCallback {

    abstract fun getPayloadJsonData(): String?

    abstract fun getHtmlFilePath(): String


    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        //   enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        createViews()
        configureBackPress()
    }

    private fun createViews() {
        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val htmlFileUrl = getHtmlFilePath()
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true

                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = true
            }
            addJavascriptInterface(JSBridge(this@InAppMessageActivity), "galva")
            WebView.setWebContentsDebuggingEnabled(true)

            loadUrl("file:///$htmlFileUrl")
        }
        setContentView(webView)
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
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
            removeAllViews()
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
                    data = android.net.Uri.parse(url)
                }
                startActivity(intent)
            }
        }
    }

    protected fun sendMessageToWebView(requestId: String, jsonPayload: JsonElement){
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)){
            lifecycleScope.launch(Dispatchers.Main){
                val payload = BridgeResponse(
                    result = jsonPayload,
                    requestId = requestId
                )
                Log.e("InAppMessageActivity","Sending message to WebView: ${JsonUtils.defaultJson.encodeToString(payload)}")
                webView.evaluateJavascript("window.handleNativeMessage(${JsonUtils.defaultJson.encodeToString(payload)})", null)
            }
        }

    }

    protected fun sendStringMessageToWebView(requestId: String, jsonPayload: String){
        if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)){
            lifecycleScope.launch(Dispatchers.Main){
                val payload = BridgeStringResponse(
                    result = jsonPayload,
                    requestId = requestId
                )
                Log.e("InAppMessageActivity","Sending message to WebView: ${JsonUtils.defaultJson.encodeToString(payload)}")
                webView.evaluateJavascript("window.handleNativeMessage(${JsonUtils.defaultJson.encodeToString(payload)})", null)
            }
        }

    }
}