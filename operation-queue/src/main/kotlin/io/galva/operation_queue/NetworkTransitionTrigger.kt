package io.galva.operation_queue

import io.galva.common.InternalGalvaApi
import kotlinx.coroutines.flow.Flow

@InternalGalvaApi
interface NetworkTransitionTrigger {
    fun signals(): Flow<Unit>
}
