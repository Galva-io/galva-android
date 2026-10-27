package io.galva.sdk.impl.inappmessage

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.galva.common.lifecycle.AppLifecycleObserver
import io.galva.common.lifecycle.AppLifecycleState

internal class AndroidAppLifecycleObserver(
    private val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
) : AppLifecycleObserver {

    override fun observe(onEvent: (AppLifecycleState) -> Unit ): AutoCloseable {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onEvent(AppLifecycleState.FOREGROUND)
            else if(event == Lifecycle.Event.ON_PAUSE) onEvent(AppLifecycleState.BACKGROUND)
        }
        // Lifecycle observers must be added on the main thread; configure may run elsewhere.
        onMainThread { lifecycleOwner.lifecycle.addObserver(observer) }
        return AutoCloseable { onMainThread { lifecycleOwner.lifecycle.removeObserver(observer) } }
    }

    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
    }
}