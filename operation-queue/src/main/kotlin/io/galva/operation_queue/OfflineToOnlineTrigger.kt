package io.galva.operation_queue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi

class OfflineToOnlineTrigger(private val monitor: NetworkMonitor) : NetworkTransitionTrigger {
    @OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
    override fun signals(): Flow<Unit> = monitor.isOnline.distinctUntilChanged()
        .runningFold(
            null as Boolean? to null as Boolean?
        ) { (_, current), new ->
            current to new
        }
        .filter { (prev, curr) ->
            prev == false && curr == true
        }
        .map {  }
}