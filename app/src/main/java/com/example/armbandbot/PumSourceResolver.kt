package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class PumHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val formData: Map<String, String> = emptyMap(),
    val followRedirects: Boolean = false,
    val timeoutBudgetMs: Long = Long.MAX_VALUE,
)

data class PumHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val contentLength: Long,
    val body: InputStream,
)

fun interface PumHttpClient {
    @Throws(IOException::class)
    fun execute(request: PumHttpRequest): PumHttpResponse
}

internal data class PumConnectionTimeouts(val connectMs: Int, val readMs: Int)

internal fun pumConnectionTimeouts(
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    requestBudgetMs: Long,
): PumConnectionTimeouts {
    require(connectTimeoutMs > 0 && readTimeoutMs > 0)
    val safeBudgetMs = requestBudgetMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return PumConnectionTimeouts(
        connectMs = minOf(connectTimeoutMs, safeBudgetMs),
        readMs = minOf(readTimeoutMs, safeBudgetMs),
    )
}

/** Small production adapter; redirect policy and response limits are enforced by [PumSourceResolver]. */
class UrlConnectionPumHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : PumHttpClient {
    override fun execute(request: PumHttpRequest): PumHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        val timeouts = pumConnectionTimeouts(connectTimeoutMs, readTimeoutMs, request.timeoutBudgetMs)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeouts.connectMs
        connection.readTimeout = timeouts.readMs
        connection.requestMethod = request.method
        request.headers.forEach(connection::setRequestProperty)
        if (request.method == "POST") {
            val encoded = request.formData.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }.toByteArray()
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setFixedLengthStreamingMode(encoded.size)
            connection.outputStream.use { it.write(encoded) }
        }
        val status = connection.responseCode
        val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        return PumHttpResponse(status, headers, connection.contentLengthLong, stream ?: ByteArrayInputStream(byteArrayOf()))
    }
}

/** Resolves one PUM card and at most one canonical source detail. Create one instance per scan cycle. */
class PumSourceResolver(
    private val http: PumHttpClient,
    private val cookies: () -> String? = { null },
    private val userAgent: String = "Mozilla/5.0 (compatible; ArmbandBot)",
    private val resolutionTimeoutMs: Long = DEFAULT_RESOLUTION_TIMEOUT_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val sourceCache = mutableMapOf<PostKey, PumResolution>()

    init {
        require(resolutionTimeoutMs > 0 && resolutionTimeoutMs <= Long.MAX_VALUE / NANOS_PER_MILLISECOND)
    }

    fun resolve(outerDetail: Document, listMarker: Boolean = PumParser.hasListMarker(outerDetail)): PumResolution {
        val loader = PumParser.parseDetail(outerDetail, listMarker).loader
            ?: return PumResolution(PumSourceStatus.INVALID_SOURCE)
        val deadline = ResolutionDeadline(nanoTime(), resolutionTimeoutMs * NANOS_PER_MILLISECOND)
        val outerReferer = DcinsidePostUrls.canonicalDetailUrl(loader.outerPost)
        val cardResult = try {
            fetch(
                PumHttpRequest(
                    loader.endpoint,
                    method = "POST",
                    headers = credentialHeaders(outerReferer),
                    formData = loader.formData,
                ),
                CARD_MAX_BYTES,
                RedirectKind.CARD,
                loader.outerPost,
                deadline,
            )
        } catch (_: UnsafeRedirectException) {
            return PumResolution(PumSourceStatus.INVALID_SOURCE)
        } catch (_: Exception) {
            return PumResolution(PumSourceStatus.TEMPORARY_FAILURE)
        }
        if (cardResult.statusCode !in 200..299) return PumResolution(PumSourceStatus.TEMPORARY_FAILURE)
        val card = PumParser.parseCard(cardResult.text, loader.outerPost)
        if (deadline.expired()) return PumResolution(PumSourceStatus.TEMPORARY_FAILURE)
        when (card.status) {
            PumCardStatus.MISSING -> return PumResolution(PumSourceStatus.MISSING)
            PumCardStatus.INVALID -> return PumResolution(PumSourceStatus.INVALID_SOURCE)
            PumCardStatus.RESOLVED -> Unit
        }
        val key = card.sourceKey ?: return PumResolution(PumSourceStatus.INVALID_SOURCE)
        val sourceUrl = card.sourceUrl ?: return PumResolution(PumSourceStatus.INVALID_SOURCE)
        sourceCache[key]?.let { return it }
        val resolved = resolveSource(key, sourceUrl, deadline)
        if (resolved.status != PumSourceStatus.TEMPORARY_FAILURE) sourceCache[key] = resolved
        return resolved
    }

    private fun resolveSource(key: PostKey, sourceUrl: String, deadline: ResolutionDeadline): PumResolution {
        val fetched = try {
            fetch(
                PumHttpRequest(sourceUrl, headers = credentialHeaders(sourceUrl)),
                SOURCE_MAX_BYTES,
                RedirectKind.SOURCE,
                key,
                deadline,
            )
        } catch (_: UnsafeRedirectException) {
            return PumResolution(PumSourceStatus.INVALID_SOURCE, key, sourceUrl)
        } catch (_: Exception) {
            return PumResolution(PumSourceStatus.TEMPORARY_FAILURE, key, sourceUrl)
        }
        if (fetched.statusCode == 404 || fetched.statusCode == 410) return PumResolution(PumSourceStatus.MISSING, key, sourceUrl)
        if (fetched.statusCode !in 200..299) return PumResolution(PumSourceStatus.TEMPORARY_FAILURE, key, sourceUrl)
        val document = Jsoup.parse(fetched.text, sourceUrl)
        if (PumParser.parseDetail(document, false).loader != null) {
            return PumResolution(PumSourceStatus.UNSUPPORTED_SOURCE, key, sourceUrl)
        }
        val body = document.selectFirst(".write_div")
            ?: return PumResolution(PumSourceStatus.TEMPORARY_FAILURE, key, sourceUrl)
        val title = document.selectFirst(".title_subject, .write_subject, .write_title")?.text()?.normalizeWhitespace().orEmpty()
        val sanitized = sanitizeBody(body)
        val bodyText = sanitized.text().normalizeWhitespace()
        val imageAlts = sanitized.select("img[alt]").map { it.attr("alt").normalizeWhitespace() }.filter { it.isNotEmpty() }
        val media = sanitized.select("img[src], video[src], audio[src], source[src], [poster]")
            .flatMap { element -> listOf("src", "poster").mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } } }
            .distinct()
        val canonicalHtml = sanitized.html()
        val hashInput = listOf(
            key.gallType, key.gallId, key.postNo, sourceUrl, title, bodyText,
            imageAlts.joinToString("\n"), media.joinToString("\n"), canonicalHtml,
        ).joinToString("") { value -> "${value.toByteArray(Charsets.UTF_8).size}:$value" }
        val hash = MessageDigest.getInstance("SHA-256").digest(hashInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (deadline.expired()) return PumResolution(PumSourceStatus.TEMPORARY_FAILURE, key, sourceUrl)
        return PumResolution(PumSourceStatus.RESOLVED, key, sourceUrl, title, bodyText, imageAlts, canonicalHtml, media, hash)
    }

    private fun sanitizeBody(original: Element): Element {
        val body = original.clone()
        body.select(".comment_box, .comment_wrap, .reply_box, .cmt_info, [id^=comment]").remove()
        body.select("script, style, meta, link, base, form, button, input, textarea, select, option, iframe, frame, object, embed, canvas, template, noscript, svg, math").remove()
        removeComments(body)

        // Unknown presentation tags are unwrapped so their static text survives; active-content
        // families above are removed with their descendants.
        body.allElements.toList().asReversed().forEach { element ->
            if (element !== body && element.tagName().lowercase(Locale.ROOT) !in ALLOWED_TAGS) element.unwrap()
        }
        body.allElements.forEach { element ->
            val tag = element.tagName().lowercase(Locale.ROOT)
            val allowed = ALLOWED_ATTRIBUTES[tag].orEmpty()
            val retained = element.attributes().asList().mapNotNull { attribute ->
                val name = attribute.key.lowercase(Locale.ROOT)
                if (name !in allowed) return@mapNotNull null
                var value = attribute.value
                if (name in URL_ATTRIBUTES) {
                    value = element.absUrl(name)
                    val uri = try { URI(value) } catch (_: Exception) { null }
                    if (uri?.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
                        return@mapNotNull null
                    }
                }
                name to value
            }.sortedBy { it.first }
            element.attributes().asList().forEach { element.removeAttr(it.key) }
            retained.forEach { (name, value) -> element.attr(name, value) }
        }
        body.ownerDocument()?.outputSettings()?.prettyPrint(false)
        return body
    }

    private fun removeComments(node: Node) {
        node.childNodes().toList().forEach { child ->
            if (child is Comment) child.remove() else removeComments(child)
        }
    }

    private fun credentialHeaders(referer: String): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Referer", referer)
        cookies()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    private fun fetch(
        initial: PumHttpRequest,
        maxBytes: Int,
        kind: RedirectKind,
        self: PostKey,
        deadline: ResolutionDeadline,
    ): BufferedResponse {
        var request = initial
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val remainingBudgetMs = deadline.remainingTimeoutMs()
            val response = http.execute(
                request.copy(followRedirects = false, timeoutBudgetMs = remainingBudgetMs),
            )
            response.body.use { body ->
                deadline.throwIfExpired()
                if (response.statusCode in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) throw IOException("Too many redirects")
                    val location = response.header("Location") ?: throw UnsafeRedirectException()
                    val absolute = try { URI(request.url).resolve(location).toString() } catch (_: Exception) { throw UnsafeRedirectException() }
                    val safeUrl = when (kind) {
                        RedirectKind.SOURCE -> DcinsidePostUrls.parseSafeCanonicalPostUrl(absolute, null)
                            ?.takeIf { it.key == self }
                            ?.url
                        RedirectKind.CARD -> safeCardEndpoint(absolute)
                    } ?: throw UnsafeRedirectException()
                    request = if (response.statusCode == 307 || response.statusCode == 308) {
                        request.copy(url = safeUrl)
                    } else {
                        // RFC 7231: 303 is always retrieval; retain conventional browser behavior
                        // for POST requests receiving 301/302.
                        request.copy(url = safeUrl, method = "GET", formData = emptyMap())
                    }
                    return@repeat
                }
                return BufferedResponse(response.statusCode, readLimited(body, response.contentLength, maxBytes, deadline))
            }
        }
        throw IOException("Too many redirects")
    }

    private fun safeCardEndpoint(raw: String): String? {
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) || !uri.host.equals("gall.dcinside.com", true) || uri.port != -1 || uri.userInfo != null) return null
        if (uri.path != "/ajax/pum_ajax/get_contents" || uri.rawQuery != null || uri.rawFragment != null) return null
        return "https://gall.dcinside.com/ajax/pum_ajax/get_contents"
    }

    private fun readLimited(input: InputStream, contentLength: Long, maxBytes: Int, deadline: ResolutionDeadline): String {
        if (contentLength > maxBytes) throw IOException("Response too large")
        val output = ByteArrayOutputStream(minOf(maxBytes, if (contentLength >= 0) contentLength.toInt() else 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            deadline.throwIfExpired()
            val count = input.read(buffer)
            deadline.throwIfExpired()
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Response too large")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun String.normalizeWhitespace(): String = trim().replace(Regex("\\s+"), " ")
    private fun PumHttpResponse.header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    private data class BufferedResponse(val statusCode: Int, val text: String)
    private inner class ResolutionDeadline(
        private val startedAtNanos: Long,
        private val timeoutNanos: Long,
    ) {
        fun expired(): Boolean = nanoTime() - startedAtNanos >= timeoutNanos
        fun remainingTimeoutMs(): Long {
            val remainingNanos = timeoutNanos - (nanoTime() - startedAtNanos)
            if (remainingNanos <= 0) throw ResolutionDeadlineExceededException()
            return remainingNanos / NANOS_PER_MILLISECOND +
                if (remainingNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
        }
        fun throwIfExpired() {
            if (expired()) throw ResolutionDeadlineExceededException()
        }
    }
    private enum class RedirectKind { CARD, SOURCE }
    private class UnsafeRedirectException : IOException()
    private class ResolutionDeadlineExceededException : IOException("PUM resolution deadline exceeded")

    companion object {
        const val CARD_MAX_BYTES = 512 * 1024
        const val SOURCE_MAX_BYTES = 2 * 1024 * 1024
        const val DEFAULT_RESOLUTION_TIMEOUT_MS = 30_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val URL_ATTRIBUTES = setOf("href", "src", "poster")
        private val ALLOWED_TAGS = setOf(
            "div", "p", "br", "span", "strong", "b", "em", "i", "u", "s", "del", "blockquote",
            "pre", "code", "ul", "ol", "li", "hr", "a", "img", "video", "audio", "source",
        )
        private val ALLOWED_ATTRIBUTES = mapOf(
            "a" to setOf("href", "title"),
            "img" to setOf("src", "alt", "title"),
            "video" to setOf("src", "poster", "controls", "title"),
            "audio" to setOf("src", "controls", "title"),
            "source" to setOf("src", "type"),
        )
    }
}
