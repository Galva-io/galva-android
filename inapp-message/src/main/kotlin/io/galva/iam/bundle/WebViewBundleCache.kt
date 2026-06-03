package io.galva.iam.bundle


import java.io.File
import java.io.IOException

class WebViewBundleCache(private val baseDir: File) {
    fun bundleFile(webviewVersion: String): File =
        File(baseDir, "inapp_message_${webviewVersion}.html")

    fun exists(webviewVersion: String): Boolean =
        bundleFile(webviewVersion).isFile && bundleFile(webviewVersion).length() > 0

    /** Atomic write: write to a tmp file, rename on success. */
    fun write(webviewVersion: String, content: String) {
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw IOException("Cannot create bundle dir: ${baseDir.absolutePath}")
        }
        val target = bundleFile(webviewVersion)
        val tmp = File(baseDir, "${target.name}.tmp")
        try {
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                throw IOException("Failed to commit bundle: ${target.absolutePath}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Delete all bundle files older than the current version, for housekeeping. */
    fun pruneExcept(currentVersion: String) {
        baseDir.listFiles { f ->
            f.name.startsWith("inapp_message_") && f.name.endsWith(".html")
        }?.forEach { f ->
            if (f.name != "inapp_message_${currentVersion}.html") f.delete()
        }
    }
}