package io.galva.sdk.impl.inappmessage

import android.content.Intent
import android.os.Bundle

/**
 * Notification interaction events, sent as regular `track` events like the iOS SDK
 * (`$gv_notification_tapped` / `$gv_notification_dismissed`), with the push data as attributes
 * plus `id`. Android also renders the notification itself, so it adds `$gv_notification_presented`,
 * recorded only once the notification was actually posted.
 */
internal object NotificationEvents {
    const val PRESENTED = "\$gv_notification_presented"
    const val TAPPED = "\$gv_notification_tapped"
    const val DISMISSED = "\$gv_notification_dismissed"

    private const val EXTRA_PAYLOAD = "io.galva.sdk.notification_payload"
    private const val COMMUNICATION_ID = "communicationId"

    /**
     * The push data plus `id`. iOS uses the notification request identifier; Android has no
     * equivalent, and the SDK shows one notification per communication, so `id` is the
     * communication id. Set last so it wins over a data key literally named `id`.
     */
    fun attributes(payload: Map<String, String>): Map<String, Any?> =
        payload + ("id" to payload[COMMUNICATION_ID])

    /** Carries the push data on the notification's tap / dismiss intents. */
    fun putPayload(intent: Intent, payload: Map<String, String>): Intent =
        intent.putExtra(EXTRA_PAYLOAD, Bundle().apply { payload.forEach(::putString) })

    fun payload(intent: Intent): Map<String, String>? {
        val bundle = intent.getBundleExtra(EXTRA_PAYLOAD) ?: return null
        return bundle.keySet().mapNotNull { key -> bundle.getString(key)?.let { key to it } }.toMap()
    }
}
