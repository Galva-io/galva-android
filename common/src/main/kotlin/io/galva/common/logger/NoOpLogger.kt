package io.galva.common.logger

val NoOpLogger: Logger = Logger.create(
    tag = "NoOp",
    sink = { _, _, _, _ -> },          // discard everything
    level = LogLevel.NONE,
    enabled = false,
)