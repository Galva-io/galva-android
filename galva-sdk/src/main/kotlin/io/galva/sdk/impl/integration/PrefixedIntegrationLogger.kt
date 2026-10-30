package io.galva.sdk.impl.integration

import io.galva.common.logger.Logger
import io.galva.sdk.GalvaIntegrationApi
import io.galva.sdk.IntegrationLogger

/** An integration's view of the SDK logger: every line is prefixed with `[name]`. */
@OptIn(GalvaIntegrationApi::class)
internal class PrefixedIntegrationLogger(name: String, private val logger: Logger) : IntegrationLogger {
    private val prefix = "[$name]"

    override fun debug(message: () -> String) = logger.debug { "$prefix ${message()}" }
    override fun info(message: () -> String) = logger.info { "$prefix ${message()}" }
    override fun warn(throwable: Throwable?, message: () -> String) = logger.warn(throwable) { "$prefix ${message()}" }
    override fun error(throwable: Throwable?, message: () -> String) = logger.error(throwable) { "$prefix ${message()}" }
}
