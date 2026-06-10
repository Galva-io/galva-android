package io.galva.common.lifecycle


interface AppLifecycleObserver {
    fun observe(
        onEvent: (AppLifecycleState) -> Unit,
    ): AutoCloseable
}

enum class AppLifecycleState {
    FOREGROUND,
    BACKGROUND
}