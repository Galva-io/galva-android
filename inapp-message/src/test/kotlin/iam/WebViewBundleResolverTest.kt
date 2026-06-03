package io.galva.iam

import android.R.id.message
import io.galva.common.logger.NoOpLogger
import io.galva.iam.bundle.WebViewBundleCache
import io.galva.iam.bundle.WebViewBundleDownloader
import io.galva.iam.bundle.WebViewBundleResolver
import io.galva.network.HttpMethod
import io.galva.network.NetworkError
import io.galva.network.response.MessageResponse
import io.galva.sdk.iam.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class WebViewBundleResolverTest {

    private lateinit var tempDir: File
    private lateinit var cache: WebViewBundleCache
    private lateinit var fakeHttp: FakeHttpClient
    private lateinit var resolver: WebViewBundleResolver

    @Before
    fun setUp() {
        tempDir = createTempDirectory("bundles").toFile()
        cache = WebViewBundleCache(tempDir)
        fakeHttp = FakeHttpClient()
        resolver = WebViewBundleResolver(
            cache = cache,
            downloader = WebViewBundleDownloader(fakeHttp, "https://galva.dev.io/"),
            logger = NoOpLogger,
        )
    }
    @After fun tearDown() {
        tempDir.deleteRecursively()
    }


    @Test
    fun `ready when versions match and bundle downloads`() = runTest {
        fakeHttp.enqueueStatus(200, "<html>bundle</html>")
        val msg = message( webviewVersion = "2.5.0")
        val outcome = resolver.resolve(msg)
        assertTrue(outcome is WebViewBundleResolver.Outcome.Ready)
        assertTrue((outcome as WebViewBundleResolver.Outcome.Ready).file.exists())
        assertEquals("<html>bundle</html>", outcome.file.readText())
    }


    @Test fun `cached bundle skips download`() = runTest {
        cache.write("2.5.0", "<html>cached</html>")
        val msg = message( webviewVersion = "2.5.0")

        val outcome = resolver.resolve(msg)

        assertTrue(outcome is WebViewBundleResolver.Outcome.Ready)
        assertEquals(0, fakeHttp.recorded.size)
    }

    @Test fun `download request uses correct url`() = runTest {
        fakeHttp.enqueueStatus(200, "<html/>")
        val msg = message( webviewVersion = "2.5.0")

        resolver.resolve(msg)

        val sent = fakeHttp.recorded.single()
        assertEquals(HttpMethod.GET, sent.method)
        assertEquals("https://galva.dev.io/2.5.0.html", sent.url)
    }

    @Test fun `http failure returns Failed`() = runTest {
        fakeHttp.enqueueStatus(503)
        val msg = message( webviewVersion = "2.5.0")

        val outcome = resolver.resolve(msg)

        assertTrue(outcome is WebViewBundleResolver.Outcome.Failed)
        assertEquals("HTTP 503", (outcome as WebViewBundleResolver.Outcome.Failed).reason)
        assertFalse(cache.exists("2.5.0"))
    }

    @Test fun `network error returns Failed with cause`() = runTest {
        fakeHttp.enqueueError(NetworkError.NoConnectivity())
        val msg = message( webviewVersion = "2.5.0")

        val outcome = resolver.resolve(msg)

        val failed = outcome as WebViewBundleResolver.Outcome.Failed
        assertTrue(failed.cause is NetworkError.NoConnectivity)
    }

    @Test fun `file name follows convention`() {
        assertEquals(
            "inapp_message_2.5.0.html",
            cache.bundleFile("2.5.0").name,
        )
    }

    @Test fun `pruneExcept keeps current and deletes others`() {
        cache.write("1.0.0", "old1")
        cache.write("1.5.0", "old2")
        cache.write("2.0.0", "current")

        cache.pruneExcept("2.0.0")

        assertFalse(cache.exists("1.0.0"))
        assertFalse(cache.exists("1.5.0"))
        assertTrue(cache.exists("2.0.0"))
    }

    @Test fun `concurrent resolve of same version downloads once`() = runTest {
        fakeHttp.enqueueStatus(200, "<html/>")
        val msg = message( webviewVersion = "2.5.0")

        val results = (1..5).map { async { resolver.resolve(msg) } }.awaitAll()

        assertTrue(results.all { it is WebViewBundleResolver.Outcome.Ready })
        assertEquals(1, fakeHttp.recorded.size)
    }

    @Test fun `concurrent resolve of different versions downloads each`() = runTest {
        fakeHttp.enqueueStatus(200, "<html v1/>")
        fakeHttp.enqueueStatus(200, "<html v2/>")
        val a = message( webviewVersion = "1.0.0")
        val b = message( webviewVersion = "2.0.0")

        val results = listOf(
            async { resolver.resolve(a) },
            async { resolver.resolve(b) },
        ).awaitAll()

        assertTrue(results.all { it is WebViewBundleResolver.Outcome.Ready })
        assertEquals(2, fakeHttp.recorded.size)
    }

    private fun message( webviewVersion: String) = MessageResponse(
        id = "m1",
        payload = null,valid = true,
        webviewVersion = webviewVersion,
    )

}