package io.galva.sdk.impl.inappmessage

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.galva.iam.AppLifecycleObserver

class AndroidAppLifecycleObserver(
    private val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
) : AppLifecycleObserver {

    override fun observe(onForeground: () -> Unit): AutoCloseable {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        return AutoCloseable { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}