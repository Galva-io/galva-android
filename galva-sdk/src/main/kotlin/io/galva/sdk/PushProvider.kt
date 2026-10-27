package io.galva.sdk

/**
 * The push service that issued a token passed to [Galva.setPushToken]. Galva sends its pushes through
 * that service, so a token only works with the provider that issued it.
 */
public enum class PushProvider(internal val platform: String) {
    /** Firebase Cloud Messaging, on devices with Google Play services. */
    FCM("fcm"),

    /** Huawei Push Kit, on Huawei devices without Google Play services. */
    HMS("hms"),

    /** Amazon Device Messaging, on Fire OS devices. */
    ADM("adm"),
}
