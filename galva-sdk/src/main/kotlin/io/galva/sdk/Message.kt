package io.galva.sdk

import io.galva.iam.Message as InternalMessage
import io.galva.iam.WorkflowType as InternalWorkflowType

/** A pending in-app message. Pass it to [Galva.showMessage] to render it. */
public data class Message @JvmOverloads constructor(
    /** Communication id; stable for the workflow run that produced the message. */
    val id: String,
    /** Workflow that produced the message; null for broadcasts / manual sends or unknown workflows. */
    val workflowType: WorkflowType? = null,
    /** When the server queued the message (ISO-8601); null for messages opened from a deep link. */
    val createdAt: String? = null,
)

/** Receives in-app messages from [Galva.observeInAppMessages]. */
public fun interface InAppMessageListener {
    /** Called on the main thread. Pass [message] to [Galva.showMessage] to render it, or skip it. */
    public fun onInAppMessage(message: Message)
}

/** Workflow categories surfaced by the server. New values may be added over time. */
public enum class WorkflowType {
    CANCELLATION_RECOVERY,
    WINBACK,

    /** Failed-payment recovery. */
    PAYMENT_RECOVERY,

    /** Trial-to-paid conversion rescue. */
    TRIAL_RESCUE,
}

internal fun InternalMessage.toPublic(): Message =
    Message(id, workflowType?.let { WorkflowType.valueOf(it.name) }, createdAt)

internal fun Message.toInternal(): InternalMessage =
    InternalMessage(id, workflowType?.let { InternalWorkflowType.valueOf(it.name) }, createdAt)
