package io.galva.common

/**
 * Marks SDK internals that are public only because the SDK is split into several Gradle modules.
 * They can change or disappear in any release; apps use `io.galva.sdk.Galva` and the types its
 * API exposes instead. The SDK's own modules opt in through a compiler flag.
 */
@RequiresOptIn(
    message = "Internal Galva SDK API — use io.galva.sdk.Galva instead. It may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class InternalGalvaApi
