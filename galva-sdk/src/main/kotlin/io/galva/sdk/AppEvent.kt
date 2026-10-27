package io.galva.sdk

/**
 * A strongly-typed event for [Galva.track], so call sites aren't stringly-typed — the Android
 * counterpart of the iOS SDK's `AppEvents.Event`.
 *
 * ```kotlin
 * data class PurchaseEvent(val sku: String, val price: Double) : AppEvent {
 *     override val eventName: String get() = "Purchase"
 *     override val attributes: Map<String, Any?> get() = mapOf("sku" to sku, "price" to price)
 * }
 *
 * Galva.instance.track(PurchaseEvent(sku = "pro_yearly", price = 9.99))
 * ```
 */
public interface AppEvent {
    /** Wire name of the event, e.g. `"Purchase"`; use a stable name from your taxonomy. */
    public val eventName: String

    /** Attributes sent with the event; null for an event without payload. */
    public val attributes: Map<String, Any?>? get() = null
}
