package io.galva.iam

/** A pending in-app message. Pass it to `showMessage(...)` to render it. */
data class Message @JvmOverloads constructor(
    /** Communication id; stable for the workflow run that produced the message. */
    val id: String,
    /** Workflow that produced the message; null for broadcasts / manual sends or unknown workflows. */
    val workflowType: WorkflowType? = null,
    /** When the server queued the message (ISO-8601); null for messages opened from a deep link. */
    val createdAt: String? = null,
)

/** Workflow categories surfaced by the server. New values may be added over time. */
enum class WorkflowType(val wireValue: String) {
    CANCELLATION_RECOVERY("cancellation-recovery"),
    WINBACK("winback"),
    /** Failed-payment recovery. */
    PAYMENT_RECOVERY("payment-recovery"),
    /** Trial-to-paid conversion rescue. */
    TRIAL_RESCUE("trial-rescue");

    companion object {
        /** Maps the wire value; unknown or missing values become null. */
        @JvmStatic
        fun fromWireValue(value: String?): WorkflowType? = entries.firstOrNull { it.wireValue == value }
    }
}
