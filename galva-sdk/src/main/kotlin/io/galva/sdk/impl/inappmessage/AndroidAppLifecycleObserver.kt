package io.galva.sdk.impl.inappmessage

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState

class AndroidAppLifecycleObserver(
    private val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
) : AppLifecycleObserver {

    override fun observe(onEvent: (AppLifecycleState) -> Unit ): AutoCloseable {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onEvent(AppLifecycleState.FOREGROUND)
            else if(event == Lifecycle.Event.ON_PAUSE) onEvent(AppLifecycleState.BACKGROUND)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        return AutoCloseable { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}