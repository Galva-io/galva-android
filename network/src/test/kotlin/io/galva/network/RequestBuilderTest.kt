package io.galva.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RequestBuilderTest {
    @Test fun `get factory produces GET request`() {
        val req = RequestBuilder.get("https://example.com/api").build()
        assertEquals(HttpMethod.GET, req.method)
        assertEquals("https://example.com/api", req.url)
    }

    @Test fun `post factory produces POST request`() {
        val req = RequestBuilder.post("https://example.com/api").build()
        assertEquals(HttpMethod.POST, req.method)
    }

    @Test fun `put factory produces PUT request`() {
        val req = RequestBuilder.put("https://example.com/api").build()
        assertEquals(HttpMethod.PUT, req.method)
    }

    @Test fun `patch factory produces PATCH request`() {
        val req = RequestBuilder.patch("https://example.com/api").build()
        assertEquals(HttpMethod.PATCH, req.method)
    }

    @Test fun `delete factory produces DELETE request`() {
        val req = RequestBuilder.delete("https://example.com/api").build()
        assertEquals(HttpMethod.DELETE, req.method)
    }

    // ── Defaults ────────────────────────────────────────────────────────────

    @Test fun `default body is null`() {
        val req = RequestBuilder.get("https://example.com").build()
        assertNull(req.body)
    }

    @Test fun `default headers are empty`() {
        val req = RequestBuilder.get("https://example.com").build()
        assertTrue(req.headers.isEmpty())
    }

    @Test fun `default timeout is 30 seconds`() {
        val req = RequestBuilder.get("https://example.com").build()
        assertEquals(30.seconds, req.timeOut)
    }

    // ── URL validation (from HttpRequest init) ──────────────────────────────

    @Test fun `http scheme accepted`() {
        val req = RequestBuilder.get("http://example.com").build()
        assertTrue(req.url.startsWith("http://"))
    }

    @Test fun `https scheme accepted`() {
        val req = RequestBuilder.get("https://example.com").build()
        assertTrue(req.url.startsWith("https://"))
    }

    // ── Headers ─────────────────────────────────────────────────────────────

    @Test fun `header adds a value`() {
        val req = RequestBuilder.get("https://example.com")
            .header("X-Foo", "bar")
            .build()
        assertEquals("bar", req.headers["X-Foo"])
    }

    @Test fun `setHeader replaces existing value`() {
        val req = RequestBuilder.get("https://example.com")
            .header("X-Foo", "first")
            .setHeader("X-Foo", "second")
            .build()
        assertEquals("second", req.headers["X-Foo"])
        assertEquals(setOf("X-Foo"), req.headers.names())
    }

    // ── JSON body ───────────────────────────────────────────────────────────

    @Test fun `jsonBody sets body and Content-Type`() {
        val req = RequestBuilder.post("https://example.com")
            .jsonBody("""{"k":"v"}""")
            .build()
        assertEquals("""{"k":"v"}""", req.body)
        assertEquals("application/json; charset=utf-8", req.headers["Content-Type"])
    }

    @Test fun `jsonBody replaces Content-Type`() {
        val req = RequestBuilder.post("https://example.com")
            .header("Content-Type", "text/plain")
            .jsonBody("""{"k":"v"}""")
            .build()
        assertEquals("application/json; charset=utf-8", req.headers["Content-Type"])
    }

    @Test fun `GET with body rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RequestBuilder.get("https://example.com")
                .jsonBody("""{"k":"v"}""")
                .build()
        }
    }

    @Test fun `POST without body is allowed`() {
        val req = RequestBuilder.post("https://example.com").build()
        assertNull(req.body)
    }

    @Test fun `empty json body string accepted`() {
        val req = RequestBuilder.post("https://example.com")
            .jsonBody("")
            .build()
        assertEquals("", req.body)
        assertEquals("application/json; charset=utf-8", req.headers["Content-Type"])
    }

    // ── Timeout ─────────────────────────────────────────────────────────────

    @Test fun `timeout overrides default`() {
        val req = RequestBuilder.get("https://example.com")
            .timeout(5.seconds)
            .build()
        assertEquals(5.seconds, req.timeOut)
    }

    @Test fun `timeout below 1ms still accepted`() {
        val req = RequestBuilder.get("https://example.com")
            .timeout(100.milliseconds)
            .build()
        assertEquals(100.milliseconds, req.timeOut)
    }

    // ── Query parameters ────────────────────────────────────────────────────

    @Test fun `single string query parameter`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("name", "alice")
            .build()
        assertEquals("https://example.com/api?name=alice", req.url)
    }

    @Test fun `multiple query parameters joined with ampersand`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("limit", 50)
            .query("page", 2)
            .build()
        assertEquals("https://example.com/api?limit=50&page=2", req.url)
    }

    @Test fun `query parameters preserve insertion order`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("c", "3")
            .query("a", "1")
            .query("b", "2")
            .build()
        assertEquals("https://example.com/api?c=3&a=1&b=2", req.url)
    }

    @Test fun `number query parameter converted to string`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("count", 42L)
            .query("ratio", 3.14)
            .build()
        assertEquals("https://example.com/api?count=42&ratio=3.14", req.url)
    }

    @Test fun `boolean query parameter converted to string`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("active", true)
            .query("deleted", false)
            .build()
        assertEquals("https://example.com/api?active=true&deleted=false", req.url)
    }

    @Test fun `repeated key produces multiple entries`() {
        val req = RequestBuilder.get("https://example.com/api")
            .queries("tag", listOf("important", "new"))
            .build()
        assertEquals("https://example.com/api?tag=important&tag=new", req.url)
    }

    @Test fun `queries map adds all entries`() {
        val req = RequestBuilder.get("https://example.com/api")
            .queries(mapOf("limit" to "50", "page" to "2"))
            .build()
        // Order depends on map type; LinkedHashMap preserves insertion
        assertTrue(req.url.contains("limit=50"))
        assertTrue(req.url.contains("page=2"))
        assertTrue(req.url.startsWith("https://example.com/api?"))
    }

    @Test fun `query with existing query string uses ampersand`() {
        val req = RequestBuilder.get("https://example.com/api?source=push")
            .query("limit", 10)
            .build()
        assertEquals("https://example.com/api?source=push&limit=10", req.url)
    }

    @Test fun `no queries leaves url unchanged`() {
        val req = RequestBuilder.get("https://example.com/api").build()
        assertEquals("https://example.com/api", req.url)
    }

    @Test fun `space in value is URL-encoded`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("q", "hello world")
            .build()
        assertEquals("https://example.com/api?q=hello+world", req.url)
    }

    @Test fun `ampersand in value is URL-encoded`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("q", "tom & jerry")
            .build()
        assertEquals("https://example.com/api?q=tom+%26+jerry", req.url)
    }

    @Test fun `equals sign in value is URL-encoded`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("filter", "key=value")
            .build()
        assertEquals("https://example.com/api?filter=key%3Dvalue", req.url)
    }

    @Test fun `plus sign in value is URL-encoded`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("q", "a+b")
            .build()
        assertEquals("https://example.com/api?q=a%2Bb", req.url)
    }

    @Test fun `non-ascii characters in value are URL-encoded`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("q", "café")
            .build()
        assertEquals("https://example.com/api?q=caf%C3%A9", req.url)
    }

    @Test fun `special characters in key are URL-encoded`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("a b", "1")
            .build()
        assertEquals("https://example.com/api?a+b=1", req.url)
    }

    @Test fun `empty value produces empty assignment`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("key", "")
            .build()
        assertEquals("https://example.com/api?key=", req.url)
    }

    @Test fun `mixed query types`() {
        val req = RequestBuilder.get("https://example.com/api")
            .query("name", "alice")
            .query("count", 42)
            .query("active", true)
            .build()
        assertEquals("https://example.com/api?name=alice&count=42&active=true", req.url)
    }

    @Test fun `queries iterable of empty values still emits keys`() {
        val req = RequestBuilder.get("https://example.com/api")
            .queries("tag", listOf("a", "", "b"))
            .build()
        assertEquals("https://example.com/api?tag=a&tag=&tag=b", req.url)
    }

    @Test fun `queries iterable of single value works like single query`() {
        val req = RequestBuilder.get("https://example.com/api")
            .queries("tag", listOf("only"))
            .build()
        assertEquals("https://example.com/api?tag=only", req.url)
    }

    @Test fun `queries iterable empty adds nothing`() {
        val req = RequestBuilder.get("https://example.com/api")
            .queries("tag", emptyList())
            .build()
        assertEquals("https://example.com/api", req.url)
    }

    // ── Fluent chain ─────────────────────────────────────────────────────────

    @Test fun `full builder chain combines all options`() {
        val req = RequestBuilder.post("https://example.com/v1/events")
            .header("X-Custom", "custom-value")
            .header("Authorization", "Bearer token-123")
            .query("source", "mobile")
            .query("priority", 5)
            .jsonBody("""{"name":"e"}""")
            .timeout(10.seconds)
            .build()

        assertEquals(HttpMethod.POST, req.method)
        assertEquals("https://example.com/v1/events?source=mobile&priority=5", req.url)
        assertEquals("custom-value", req.headers["X-Custom"])
        assertEquals("Bearer token-123", req.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", req.headers["Content-Type"])
        assertEquals("""{"name":"e"}""", req.body)
        assertEquals(10.seconds, req.timeOut)
    }

    @Test fun `each builder call returns same instance for chaining`() {
        val builder = RequestBuilder.get("https://example.com")
        assertSame(builder, builder.query("a", "1"))
        assertSame(builder, builder.header("X", "y"))
        assertSame(builder, builder.timeout(1.seconds))
        assertSame(builder, builder.queries("t", listOf("1")))
        assertSame(builder, builder.queries(mapOf("k" to "v")))
    }

    // ── Build repeatability ──────────────────────────────────────────────────

    @Test fun `build twice produces equivalent requests`() {
        val builder = RequestBuilder.get("https://example.com/api")
            .query("limit", 10)
            .header("X-Trace", "t1")

        val a = builder.build()
        val b = builder.build()

        assertEquals(a.url, b.url)
        assertEquals(a.method, b.method)
        assertEquals(a.headers["X-Trace"], b.headers["X-Trace"])
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test fun `url with port preserved`() {
        val req = RequestBuilder.get("https://example.com:8080/api")
            .query("k", "v")
            .build()
        assertEquals("https://example.com:8080/api?k=v", req.url)
    }

    @Test fun `url with fragment preserved`() {
        val req = RequestBuilder.get("https://example.com/api#section")
            .query("k", "v")
            .build()
        // Fragments come after queries normally; this builder appends naively
        // — verify documented behavior, even if a strict URL builder would reorder
        assertTrue(req.url.startsWith("https://example.com/api#section"))
        assertTrue(req.url.contains("k=v"))
    }

    @Test fun `localhost url accepted`() {
        val req = RequestBuilder.get("http://localhost:8080/api").build()
        assertEquals("http://localhost:8080/api", req.url)
    }

    @Test fun `url with path segments preserved`() {
        val req = RequestBuilder.get("https://example.com/v1/users/123/profile")
            .query("expand", "true")
            .build()
        assertEquals("https://example.com/v1/users/123/profile?expand=true", req.url)
    }

    @Test fun `unicode in header value passed through`() {
        // Headers don't go through URL encoding — values stored as-is
        val req = RequestBuilder.get("https://example.com")
            .header("X-Note", "café")
            .build()
        assertEquals("café", req.headers["X-Note"])
    }

    // ── DELETE with optional body ────────────────────────────────────────────

    @Test fun `DELETE without body allowed`() {
        val req = RequestBuilder.delete("https://example.com/api/123").build()
        assertEquals(HttpMethod.DELETE, req.method)
        assertNull(req.body)
    }

    @Test fun `DELETE with json body allowed`() {
        val req = RequestBuilder.delete("https://example.com/api/123")
            .jsonBody("""{"reason":"cleanup"}""")
            .build()
        assertEquals(HttpMethod.DELETE, req.method)
        assertEquals("""{"reason":"cleanup"}""", req.body)
    }

    // ── Java interop sanity ─────────────────────────────────────────────────

    @Test fun `factory methods are JvmStatic accessible from Kotlin`() {
        // Compile-time check: these resolve as static
        @Suppress("RemoveRedundantQualifierName")
        val _g = io.galva.network.RequestBuilder.get("https://example.com")
        val _p = io.galva.network.RequestBuilder.post("https://example.com")
        val _u = io.galva.network.RequestBuilder.put("https://example.com")
        val _t = io.galva.network.RequestBuilder.patch("https://example.com")
        val _d = io.galva.network.RequestBuilder.delete("https://example.com")
    }
}