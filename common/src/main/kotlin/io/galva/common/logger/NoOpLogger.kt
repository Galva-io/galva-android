package io.galva.common.logger

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
val NoOpLogger: Logger = Logger.create(
    tag = "NoOp",
    sink = { _, _, _, _ -> },          // discard everything
    level = LogLevel.NONE,
    enabled = false,
)