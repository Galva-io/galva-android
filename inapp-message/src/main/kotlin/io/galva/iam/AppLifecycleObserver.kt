package io.galva.iam

interface AppLifecycleObserver {
    fun observe(onForeground: () -> Unit): AutoCloseable
}