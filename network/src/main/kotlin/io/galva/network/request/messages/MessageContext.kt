package io.galva.network.request.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MessageContext(
    val app: App? = null,
    val device: Device? = null,
    val ip: String? = null,
    val library: Library? = null,
    val locale: String? = null,
    val network: Network? = null,
    val os: OS? = null,
    val page: Page? = null,
    val referrer: Referrer? = null,
    val screen: Screen? = null,
    val timezone: String? = null,
    val userAgent: String? = null,
    val userAgentData: UserAgentData? = null,
)

@Serializable
data class Referrer(
    val id: String, val link: String, val name: String, @SerialName("type") val refererType: String, val url: String
) {

}

@Serializable
data class App(
    val build: String, val name: String, val namespace: String, val version: String
)

@Serializable
data class Device(
    val adTrackingEnabled: Boolean,
    val advertisingId: String?,
    val id: String,
    val manufacturer: String,
    val model: String,
    val name: String,
    val token: String?,
    @SerialName("type")
    val deviceType: String,
    val version: String
)

@Serializable
data class Library(
    val name: String, val version: String
)

@Serializable
data class Network(
    val bluetooth: Boolean,
    val carrier: String,
    val cellular: Boolean,
    val wifi: Boolean,


    )

@Serializable
data class OS(val name: String, val version: String)

@Serializable
data class Page(
    val path: String, val referrer: String, val search: String, val title: String, val url: String
)

@Serializable
data class Screen(val density: Int, val height: Int, val width: Int)

@Serializable
data class UserAgentData(
    val bitness: String,
    val brands: List<Brand>,
    val mobile: Boolean,
    val model: String,
    val platform: String,
    val platformVersion: String,
    val uaFullVersion: String
)

@Serializable
data class Brand(val brand: String, val version: String)

@Serializable
enum class SourceType {
    @SerialName("profile")
    Profile,

    @SerialName("product")
    Product,

    @SerialName("plan")
    Plan,

    @SerialName("product-billing")
    ProductBilling,

    @SerialName("entitlement")
    Entitlement

}