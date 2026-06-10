package io.galva.core.protocol

sealed class Environment(val baseAPIUrl:String, val baseWebviewUrl:String ){
    data object Development: Environment("https://api.galva.dev/","https://webview.galva.dev/")
    data object Production: Environment("https://api.revflow.dev/","https://webview.revflow.dev/")
    data class Custom(val apiUrl: String, val webviewUrl: String): Environment(apiUrl, webviewUrl)
}
