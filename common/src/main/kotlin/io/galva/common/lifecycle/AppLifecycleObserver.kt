package io.galva.common.lifecycle

import io.galva.common.InternalGalvaApi


@InternalGalvaApi
interface AppLifecycleObserver {
    fun observe(
        onEvent: (AppLifecycleState) -> Unit,
    ): AutoCloseable
}

@InternalGalvaApi
enum class AppLifecycleState {
    FOREGROUND,
    BACKGROUND
}