package io.galva.iam.bundle

import io.galva.common.InternalGalvaApi
import io.galva.common.logger.Logger
import io.galva.iam.bundle.WebViewBundleResolver.Outcome.Failed
import io.galva.iam.bundle.WebViewBundleResolver.Outcome.Ready
import io.galva.network.response.MessageResponse
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@InternalGalvaApi
class WebViewBundleResolver(
    private val cache: WebViewBundleCache,
    private val downloader: WebViewBundleDownloader,
    private val logger: Logger,
) {
    private val downloadMutex = ConcurrentMutexMap()

    sealed class Outcome {
        data class Ready(val file: File) : Outcome()
        data class Failed(val reason: String, val cause: Throwable? = null) : Outcome()
    }

    suspend fun resolve(message: MessageResponse): Outcome {
        val webviewVersion = message.webviewVersion

        if (cache.exists(webviewVersion)) {
            logger.debug { "Bundle cached for $webviewVersion" }
            return Ready(cache.bundleFile(webviewVersion))
        }

        return downloadMutex.lock(webviewVersion).withLock {
            // Double-check under lock — another coroutine may have downloaded it
            if (cache.exists(webviewVersion)) {
                return@withLock Ready(cache.bundleFile(webviewVersion))
            }
            doDownload(webviewVersion)
        }
    }

    suspend fun doDownload(webviewVersion: String): Outcome {
        logger.debug { "Downloading bundle $webviewVersion" }
        return when (val r = downloader.download(webviewVersion)) {
            is ServiceResult.Success -> try {
                cache.write(webviewVersion, r.value)
                cache.pruneExcept(webviewVersion)              // housekeeping
                logger.debug { "Bundle $webviewVersion saved" }
                Ready(cache.bundleFile(webviewVersion))
            } catch (t: Throwable) {
                logger.error(t) { "Failed to write bundle for $webviewVersion" }
                Failed("Write failed: ${t.message}", t)
            }

            is ServiceResult.Failure -> {
                logger.warn { "Bundle $webviewVersion download rejected: HTTP ${r.status}" }
                Failed("HTTP ${r.status}")
            }

            is ServiceResult.Error -> {
                // Offline is routine: the message is retried on a later poll.
                logger.info { "Bundle $webviewVersion not downloaded (offline?): ${r.error.message}" }
                Failed(r.error.message ?: "Network error", r.error)
            }

            is ServiceResult.UnknownError -> {
                logger.error(r.exception) { "Bundle $webviewVersion download failed" }
                Failed("Unknown error: ${r.exception.message}", r.exception)
            }
        }
    }
}

/** Mutex-per-key. Avoids serializing downloads of different versions. */
internal class ConcurrentMutexMap {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    fun lock(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }
}