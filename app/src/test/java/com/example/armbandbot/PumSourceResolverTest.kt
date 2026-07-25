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
    private val loaderDoc get() = Jsoup.parse(fixture("pum_detail.html"))

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
        assertEquals(http.requests[1].url, http.requests[1].headers["Referer"])
        assertTrue(http.requests.all { !it.followRedirects })
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
        val otherOuter = Jsoup.parse(fixture("pum_detail.html").replace("'900'", "'901'"))
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

    @Test fun `regular minor and mini source requests use canonical detail as url and referer`() {
        val links = listOf(
            "https://gall.dcinside.com/board/view/?id=gall&no=1",
            "https://gall.dcinside.com/mgallery/board/view/?id=minor&no=2",
            "https://gall.dcinside.com/mini/board/view/?id=mini&no=3"
        )
        links.forEach { link ->
            val card = "<div class='cloned_card_body'><a class='source_link' href='$link'>원문</a></div>"
            val http = FakeClient().apply { body(card); body(fixture("source_detail.html")) }
            assertEquals(PumSourceStatus.RESOLVED, PumSourceResolver(http).resolve(loaderDoc, true).status)
            assertEquals(link, http.requests[1].url)
            assertEquals(link, http.requests[1].headers["Referer"])
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
