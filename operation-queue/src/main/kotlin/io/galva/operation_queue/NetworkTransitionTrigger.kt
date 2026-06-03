package io.galva.operation_queue

import kotlinx.coroutines.flow.Flow

interface NetworkTransitionTrigger {
    fun signals(): Flow<Unit>
}
