package io.galva.core.protocol

/**
 * Galva backend the SDK talks to. Environments are fully isolated; an API key is only
 * valid against the environment it was issued for.
 */
sealed class Environment(val baseAPIUrl:String, val baseWebviewUrl:String ){
    /** `api.galva.io` + `webview.galva.io`. Use for store releases. */
    data object Production: Environment("https://api.galva.io/","https://webview.galva.io/")
    /** `api.galva.dev` + `webview.galva.dev`. Use for internal / staging builds. */
    data object Development: Environment("https://api.galva.dev/","https://webview.galva.dev/")
    /** Proxy / on-prem setups. [webviewUrl] must end with `/`; bundles load from `<webviewUrl><version>.html`. */
    data class Custom(val apiUrl: String, val webviewUrl: String): Environment(apiUrl, webviewUrl)
}
