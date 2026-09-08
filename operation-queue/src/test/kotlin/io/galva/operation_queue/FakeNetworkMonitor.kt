package io.galva.operation_queue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeNetworkMonitor(initial: Boolean = true,
) : NetworkMonitor {
    private val state = MutableStateFlow(initial)
    override val isOnline: Flow<Boolean> = state.asStateFlow()
    fun setOnline(v: Boolean) { state.value = v }
}