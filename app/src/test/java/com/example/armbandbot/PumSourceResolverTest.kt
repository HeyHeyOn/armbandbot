package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class PumSourceResolverTest {
    private fun fixture(name: String) = javaClass.getResource("/pum/$name")!!.readText()
    private val outer = PostKey("M", "laboratory1", "900")
    private val outerUrl = "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=900"
    private val loaderDoc get() = Jsoup.parse(fixture("pum_detail.html"), outerUrl)

    private class FakeClient : PumHttpClient {
        val requests = mutableListOf<PumHttpRequest>()
        val responses = ArrayDeque<Any>()
        override fun execute(request: PumHttpRequest): PumHttpResponse {
            requests += request
            val next = responses.removeFirst()
            if (next is IOException) throw next
            return next as PumHttpResponse
        }
        fun body(text: String, code: Int = 200, headers: Map<String, String> = emptyMap(), length: Long = text.toByteArray().size.toLong()) {
            responses += PumHttpResponse(code, headers, length, ByteArrayInputStream(text.toByteArray()))
        }
    }

    @Test fun `resolves loader card and one source detail without comments`() {
        val http = FakeClient().apply { body(fixture("pum_card_resolved.html")); body(fixture("source_detail.html")) }
        val result = PumSourceResolver(http, cookies = { "session=secret" }).resolve(loaderDoc, true)
        assertEquals(PumSourceStatus.RESOLVED, result.status)
        assertEquals("원본 제목", result.title)
        assertEquals("Hello world bad", result.bodyText)
        assertEquals(listOf("고양이"), result.imageAlts)
        assertFalse(result.sanitizedHtml.contains("script"))
        assertFalse(result.sanitizedHtml.contains("onclick"))
        assertFalse(result.sanitizedHtml.contains("javascript:"))
        assertFalse(result.bodyText.contains("댓글 금지어"))
        assertTrue(result.mediaSources.contains("https://gall.dcinside.com/images/a.jpg"))
        assertNotNull(result.contentHash)
        assertEquals(2, http.requests.size)
        assertEquals("POST", http.requests[0].method)
        assertEquals("https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=900", http.requests[0].headers["Referer"])
        assertEquals("https://gall.dcinside.com/mini/board/view/?id=tinygallery&no=12345", http.requests[1].url)
        assertEquals(outerUrl, http.requests[1].headers["Referer"])
        assertTrue(http.requests.all { !it.followRedirects })
    }

    @Test fun `live loader source hint stays separate from outer repost identity`() {
        val live = Jsoup.parse("""
            <div class='write_div'><script>
              var u='https://gall.dcinside.com/ajax/pum_ajax/get_contents';
              var data={ci_t:null,_GALLTYPE_:'',id:'tinygallery',no:12345};
              ${'$'}.ajax({url:u,data:data});
            </script></div>
        """.trimIndent(), outerUrl)
        val classlessCard = "<div class='cloned_card_body'><a href='/mini/board/view/?id=tinygallery&no=12345'>원문</a></div>"
        val http = FakeClient().apply { body(classlessCard); body(fixture("source_detail.html")) }

        val result = PumSourceResolver(http).resolve(live, outerUrl, true)

        assertEquals(PumSourceStatus.RESOLVED, result.status)
        assertEquals(outerUrl, http.requests[0].headers["Referer"])
        assertEquals(outerUrl, http.requests[1].headers["Referer"])
        assertEquals("https://gall.dcinside.com/mini/board/view/?id=tinygallery&no=12345", http.requests[1].url)
    }

    @Test fun `outer repost is the fixed referer for card source and redirects`() {
        val sourceUrl = "https://gall.dcinside.com/mini/board/view/?id=tinygallery&no=12345"
        val http = FakeClient().apply {
            body("", 307, mapOf("Location" to "https://gall.dcinside.com/ajax/pum_ajax/get_contents"))
            body(fixture("pum_card_resolved.html"))
            body("", 307, mapOf("Location" to sourceUrl))
            body(fixture("source_detail.html"))
        }

        assertEquals(PumSourceStatus.RESOLVED, PumSourceResolver(http).resolve(loaderDoc, outerUrl, true).status)
        assertEquals(4, http.requests.size)
        assertTrue(http.requests.all { it.headers["Referer"] == outerUrl })
    }

    @Test fun `invalid outer url is omitted as referer instead of using request itself`() {
        val http = FakeClient().apply { body(fixture("pum_card_resolved.html")); body(fixture("source_detail.html")) }

        assertEquals(PumSourceStatus.RESOLVED, PumSourceResolver(http).resolve(loaderDoc, "https://evil.example/repost", true).status)
        assertTrue(http.requests.all { "Referer" !in it.headers })
    }

    @Test fun `cycle-local resolvers keep bot cookies isolated on validated dcinside requests`() {
        fun resolvedClient() = FakeClient().apply {
            body(fixture("pum_card_resolved.html"))
            body(fixture("source_detail.html"))
        }
        val first = resolvedClient()
        val second = resolvedClient()

        assertEquals(PumSourceStatus.RESOLVED, PumSourceResolver(first, cookies = { "bot=one" }).resolve(loaderDoc, true).status)
        assertEquals(PumSourceStatus.RESOLVED, PumSourceResolver(second, cookies = { "bot=two" }).resolve(loaderDoc, true).status)

        assertTrue(first.requests.all { it.url.startsWith("https://gall.dcinside.com/") && it.headers["Cookie"] == "bot=one" })
        assertTrue(second.requests.all { it.url.startsWith("https://gall.dcinside.com/") && it.headers["Cookie"] == "bot=two" })
    }

    @Test fun `maps missing invalid timeout and malformed source states`() {
        val missing = FakeClient().apply { body(fixture("pum_card_missing.html")) }
        assertEquals(PumSourceStatus.MISSING, PumSourceResolver(missing).resolve(loaderDoc, true).status)

        val invalid = FakeClient().apply { body("<a href='https://evil.example/board/view/?id=x&no=1'>source</a>") }
        assertEquals(PumSourceStatus.INVALID_SOURCE, PumSourceResolver(invalid).resolve(loaderDoc, true).status)

        val timeout = FakeClient().apply { responses += IOException("timeout") }
        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, PumSourceResolver(timeout).resolve(loaderDoc, true).status)

        val malformed = FakeClient().apply { body(fixture("pum_card_resolved.html")); body("<html>no write body</html>") }
        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, PumSourceResolver(malformed).resolve(loaderDoc, true).status)

        val vanished = FakeClient().apply { body(fixture("pum_card_resolved.html")); body("gone", code = 404) }
        assertEquals(PumSourceStatus.MISSING, PumSourceResolver(vanished).resolve(loaderDoc, true).status)
    }

    @Test fun `does not recurse when source is itself only a pum loader`() {
        val http = FakeClient().apply { body(fixture("pum_card_resolved.html")); body(fixture("pum_detail.html")) }
        val result = PumSourceResolver(http).resolve(loaderDoc, true)
        assertEquals(PumSourceStatus.UNSUPPORTED_SOURCE, result.status)
        assertEquals(2, http.requests.size)
    }

    @Test fun `one-cycle cache is keyed by full source PostKey and shared by outer reposts`() {
        val http = FakeClient().apply {
            body(fixture("pum_card_resolved.html")); body(fixture("source_detail.html"))
            body(fixture("pum_card_resolved.html"))
        }
        val resolver = PumSourceResolver(http)
        val first = resolver.resolve(loaderDoc, true)
        val otherOuterUrl = "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=901"
        val otherOuter = Jsoup.parse(fixture("pum_detail.html"), otherOuterUrl)
        val second = resolver.resolve(otherOuter, true)
        assertSame(first, second)
        assertEquals(3, http.requests.size) // two cards, one shared detail
    }

    @Test fun `external redirect is rejected without sending a second request or credentials`() {
        val http = FakeClient().apply { body("", 302, mapOf("Location" to "https://evil.example/steal")) }
        val result = PumSourceResolver(http, cookies = { "session=secret" }).resolve(loaderDoc, true)
        assertEquals(PumSourceStatus.INVALID_SOURCE, result.status)
        assertEquals(1, http.requests.size)
        assertEquals("session=secret", http.requests.single().headers["Cookie"])
    }

    @Test fun `declared and streaming size limits stop before full buffering`() {
        class CountingStream(private val total: Int) : InputStream() {
            var reads = 0
            override fun read(): Int = if (reads++ < total) 'x'.code else -1
        }
        val declaredStream = CountingStream(600_000)
        val declared = object : PumHttpClient {
            override fun execute(request: PumHttpRequest) = PumHttpResponse(200, emptyMap(), 600_000, declaredStream)
        }
        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, PumSourceResolver(declared).resolve(loaderDoc, true).status)
        assertEquals(0, declaredStream.reads)

        val stream = CountingStream(600_000)
        val unknown = object : PumHttpClient {
            override fun execute(request: PumHttpRequest) = PumHttpResponse(200, emptyMap(), -1, stream)
        }
        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, PumSourceResolver(unknown).resolve(loaderDoc, true).status)
        assertTrue("read ${stream.reads} bytes", stream.reads > 524_288 && stream.reads < 600_000)
    }

    @Test fun `regular minor and mini source requests use canonical detail with outer referer`() {
        val links = listOf(
            "https://gall.dcinside.com/board/view/?id=gall&no=1",
            "https://gall.dcinside.com/mgallery/board/view/?id=minor&no=2",
            "https://gall.dcinside.com/mini/board/view/?id=mini&no=3"
        )
        links.forEach { link ->
            val source = DcinsidePostUrls.parseSafeCanonicalPostUrl(link, null)!!
            val loader = Jsoup.parse("""
                <div class='write_div'><script>
                  ${'$'}.ajax({url:'/ajax/pum_ajax/get_contents',data:{gall_id:'${source.key.gallId}',gall_no:'${source.key.postNo}',gall_type:'${source.key.gallType}'}});
                </script></div>
            """.trimIndent(), outerUrl)
            val card = "<div class='cloned_card_body'><a class='source_link' href='$link'>원문</a></div>"
            val http = FakeClient().apply { body(card); body(fixture("source_detail.html")) }
            assertEquals(PumSourceStatus.RESOLVED, PumSourceResolver(http).resolve(loader, true).status)
            assertEquals(link, http.requests[1].url)
            assertEquals(outerUrl, http.requests[1].headers["Referer"])
        }
    }

    @Test fun `sanitizer is strict allowlist and hash includes canonical markup`() {
        val malicious = fixture("source_detail_malicious.html")
        fun resolve(source: String): PumResolution {
            val http = FakeClient().apply { body(fixture("pum_card_resolved.html")); body(source) }
            return PumSourceResolver(http).resolve(loaderDoc, true)
        }
        val first = resolve(malicious)
        val second = resolve(malicious)
        assertEquals(PumSourceStatus.RESOLVED, first.status)
        assertEquals(first.contentHash, second.contentHash)
        val html = first.sanitizedHtml.lowercase()
        listOf("style=", "<style", "<meta", "<svg", "<math", "srcset", "xlink:href", "formaction", "onclick", "javascript:", "<!--", "comment_box", "data-secret", "class=").forEach {
            assertFalse("must remove $it from $html", html.contains(it))
        }
        assertTrue(html.contains("<strong>kept</strong>"))
        assertTrue(html.contains("alt=\"safe alt\""))
        assertTrue(first.mediaSources.contains("https://gall.dcinside.com/safe.jpg"))
        val markupOnlyChange = malicious.replace("<strong>kept</strong>", "<em>kept</em>")
        val changedMarkup = resolve(markupOnlyChange)
        assertEquals(first.bodyText, changedMarkup.bodyText)
        assertNotEquals(first.contentHash, changedMarkup.contentHash)
        assertNotEquals(first.contentHash, resolve(malicious.replace("safe alt", "changed alt")).contentHash)
    }

    @Test fun `card redirects require canonical endpoint and honor redirect methods`() {
        val bad = FakeClient().apply { body("", 302, mapOf("Location" to "https://gall.dcinside.com/ajax/pum_ajax/get_contents?x=1")) }
        assertEquals(PumSourceStatus.INVALID_SOURCE, PumSourceResolver(bad).resolve(loaderDoc, true).status)
        assertEquals(1, bad.requests.size)
        val seeOther = FakeClient().apply {
            body("", 303, mapOf("Location" to "https://gall.dcinside.com/ajax/pum_ajax/get_contents")); body(fixture("pum_card_missing.html"))
        }
        PumSourceResolver(seeOther).resolve(loaderDoc, true)
        assertEquals("GET", seeOther.requests[1].method)
        assertTrue(seeOther.requests[1].formData.isEmpty())
        val preserve = FakeClient().apply {
            body("", 307, mapOf("Location" to "https://gall.dcinside.com/ajax/pum_ajax/get_contents")); body(fixture("pum_card_missing.html"))
        }
        PumSourceResolver(preserve).resolve(loaderDoc, true)
        assertEquals("POST", preserve.requests[1].method)
        assertEquals(preserve.requests[0].formData, preserve.requests[1].formData)
    }

    @Test fun `redirect loops are bounded`() {
        val http = FakeClient().apply { repeat(4) { body("", 308, mapOf("Location" to "https://gall.dcinside.com/ajax/pum_ajax/get_contents")) } }
        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, PumSourceResolver(http).resolve(loaderDoc, true).status)
        assertEquals(4, http.requests.size)
    }

    @Test fun `overall deadline is shared across card source and redirects`() {
        var nowNanos = 0L
        val cardHtml = fixture("pum_card_resolved.html")
        val sourceUrl = "https://gall.dcinside.com/mini/board/view/?id=tinygallery&no=12345"
        val http = object : PumHttpClient {
            val requests = mutableListOf<PumHttpRequest>()
            override fun execute(request: PumHttpRequest): PumHttpResponse {
                requests += request
                nowNanos += 40_000_000L
                return if (requests.size == 1) {
                    val bytes = cardHtml.toByteArray()
                    PumHttpResponse(200, emptyMap(), bytes.size.toLong(), ByteArrayInputStream(bytes))
                } else {
                    PumHttpResponse(
                        307,
                        mapOf("Location" to sourceUrl),
                        0,
                        ByteArrayInputStream(byteArrayOf()),
                    )
                }
            }
        }
        val resolver = PumSourceResolver(
            http = http,
            resolutionTimeoutMs = 100,
            nanoTime = { nowNanos },
        )

        val result = resolver.resolve(loaderDoc, true)

        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, result.status)
        assertEquals(3, http.requests.size)
        assertEquals(listOf("POST", "GET", "GET"), http.requests.map { it.method })
    }

    @Test fun `request timeout budgets decrease across card source and redirect calls`() {
        var nowNanos = 0L
        val sourceUrl = "https://gall.dcinside.com/mini/board/view/?id=tinygallery&no=12345"
        val http = object : PumHttpClient {
            val requests = mutableListOf<PumHttpRequest>()
            override fun execute(request: PumHttpRequest): PumHttpResponse {
                requests += request
                nowNanos += 20_000_000L
                return when (requests.size) {
                    1 -> fixture("pum_card_resolved.html").let {
                        PumHttpResponse(200, emptyMap(), it.toByteArray().size.toLong(), ByteArrayInputStream(it.toByteArray()))
                    }
                    2 -> PumHttpResponse(307, mapOf("Location" to sourceUrl), 0, ByteArrayInputStream(byteArrayOf()))
                    else -> fixture("source_detail.html").let {
                        PumHttpResponse(200, emptyMap(), it.toByteArray().size.toLong(), ByteArrayInputStream(it.toByteArray()))
                    }
                }
            }
        }

        val result = PumSourceResolver(http, resolutionTimeoutMs = 100, nanoTime = { nowNanos }).resolve(loaderDoc, true)

        assertEquals(PumSourceStatus.RESOLVED, result.status)
        assertEquals(listOf(100L, 80L, 60L), http.requests.map { it.timeoutBudgetMs })
    }

    @Test fun `url connection timeout policy bounds both configured timeouts by request budget`() {
        assertEquals(
            PumConnectionTimeouts(connectMs = 37, readMs = 37),
            pumConnectionTimeouts(connectTimeoutMs = 15_000, readTimeoutMs = 20_000, requestBudgetMs = 37),
        )
        assertEquals(
            PumConnectionTimeouts(connectMs = 1, readMs = 1),
            pumConnectionTimeouts(connectTimeoutMs = 15_000, readTimeoutMs = 20_000, requestBudgetMs = 0),
        )
        assertEquals(
            PumConnectionTimeouts(connectMs = 11, readMs = 17),
            pumConnectionTimeouts(connectTimeoutMs = 11, readTimeoutMs = 17, requestBudgetMs = Long.MAX_VALUE),
        )
    }

    @Test fun `streaming reads recompute the remaining request budget and disconnect on close`() {
        var nowNanos = 0L
        var delegateReads = 0
        var disconnected = false
        val appliedTimeouts = mutableListOf<Int>()
        val delegate = object : InputStream() {
            override fun read(): Int {
                delegateReads++
                if (delegateReads == 1) nowNanos = 99_000_000L
                return 'x'.code
            }
        }
        val stream = DeadlineBoundInputStream(
            delegate = delegate,
            configuredReadTimeoutMs = 20_000,
            requestBudgetMs = 100,
            nanoTime = { nowNanos },
            applyReadTimeout = appliedTimeouts::add,
            onClose = { disconnected = true },
        )

        assertEquals('x'.code, stream.read())
        assertEquals('x'.code, stream.read())
        assertEquals(listOf(100, 1), appliedTimeouts)
        nowNanos = 100_000_000L
        assertThrows(IOException::class.java) { stream.read() }
        assertEquals(2, delegateReads)

        stream.close()
        assertTrue(disconnected)
    }

    @Test fun `deadline expiring while source is parsed maps to temporary failure`() {
        var clockReads = 0
        val http = FakeClient().apply { body(fixture("pum_card_resolved.html")); body(fixture("source_detail.html")) }
        val result = PumSourceResolver(
            http,
            resolutionTimeoutMs = 100,
            nanoTime = { if (++clockReads >= 15) 100_000_000L else 0L },
        ).resolve(loaderDoc, true)

        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, result.status)
        assertEquals(15, clockReads)
        assertEquals(2, http.requests.size)
    }

    @Test fun `deadline crossing during unsupported source parsing is temporary and is not cached`() {
        var clockReads = 0
        var expire = true
        val http = FakeClient().apply {
            body(fixture("pum_card_resolved.html")); body(fixture("pum_detail.html"))
            body(fixture("pum_card_resolved.html")); body(fixture("pum_detail.html"))
        }
        val resolver = PumSourceResolver(
            http,
            resolutionTimeoutMs = 100,
            nanoTime = {
                clockReads++
                if (expire && clockReads >= 15) 100_000_000L else 0L
            },
        )

        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, resolver.resolve(loaderDoc, true).status)
        expire = false
        assertEquals(PumSourceStatus.UNSUPPORTED_SOURCE, resolver.resolve(loaderDoc, true).status)
        assertEquals(4, http.requests.size)
    }

    @Test fun `source two MiB declared limit rejects before buffering`() {
        class CountingStream : InputStream() {
            var reads = 0
            override fun read(): Int { reads++; return 'x'.code }
        }
        val stream = CountingStream()
        val http = FakeClient().apply {
            body(fixture("pum_card_resolved.html"))
            responses += PumHttpResponse(200, emptyMap(), PumSourceResolver.SOURCE_MAX_BYTES.toLong() + 1, stream)
        }
        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, PumSourceResolver(http).resolve(loaderDoc, true).status)
        assertEquals(0, stream.reads)
    }

    @Test fun `temporary source failures are not cached but stable outcomes are`() {
        val http = FakeClient().apply {
            body(fixture("pum_card_resolved.html")); responses += IOException("temporary")
            body(fixture("pum_card_resolved.html")); body(fixture("source_detail.html"))
            body(fixture("pum_card_resolved.html"))
        }
        val resolver = PumSourceResolver(http)
        assertEquals(PumSourceStatus.TEMPORARY_FAILURE, resolver.resolve(loaderDoc, true).status)
        val stable = resolver.resolve(loaderDoc, true)
        assertEquals(PumSourceStatus.RESOLVED, stable.status)
        assertSame(stable, resolver.resolve(loaderDoc, true))
        assertEquals(5, http.requests.size)
    }
}
