package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

/** Small production adapter; redirect policy and response limits are enforced by [PumSourceResolver]. */
class UrlConnectionPumHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : PumHttpClient {
    override fun execute(request: PumHttpRequest): PumHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
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
) {
    private val sourceCache = mutableMapOf<PostKey, PumResolution>()

    fun resolve(outerDetail: Document, listMarker: Boolean = PumParser.hasListMarker(outerDetail)): PumResolution {
        val loader = PumParser.parseDetail(outerDetail, listMarker).loader
            ?: return PumResolution(PumSourceStatus.INVALID_SOURCE)
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
            )
        } catch (_: UnsafeRedirectException) {
            return PumResolution(PumSourceStatus.INVALID_SOURCE)
        } catch (_: Exception) {
            return PumResolution(PumSourceStatus.TEMPORARY_FAILURE)
        }
        if (cardResult.statusCode !in 200..299) return PumResolution(PumSourceStatus.TEMPORARY_FAILURE)
        val card = PumParser.parseCard(cardResult.text, loader.outerPost)
        when (card.status) {
            PumCardStatus.MISSING -> return PumResolution(PumSourceStatus.MISSING)
            PumCardStatus.INVALID -> return PumResolution(PumSourceStatus.INVALID_SOURCE)
            PumCardStatus.RESOLVED -> Unit
        }
        val key = card.sourceKey ?: return PumResolution(PumSourceStatus.INVALID_SOURCE)
        val sourceUrl = card.sourceUrl ?: return PumResolution(PumSourceStatus.INVALID_SOURCE)
        return sourceCache.getOrPut(key) { resolveSource(key, sourceUrl) }
    }

    private fun resolveSource(key: PostKey, sourceUrl: String): PumResolution {
        val fetched = try {
            fetch(
                PumHttpRequest(sourceUrl, headers = credentialHeaders(sourceUrl)),
                SOURCE_MAX_BYTES,
                RedirectKind.SOURCE,
                key,
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
        val sanitized = body.clone()
        sanitized.select("script, form, iframe, object, embed, input").remove()
        for (element in sanitized.allElements) {
            element.attributes().asList().forEach { attr ->
                val name = attr.key.lowercase(Locale.ROOT)
                if (name.startsWith("on") || name == "srcdoc") element.removeAttr(attr.key)
            }
            for (attribute in listOf("src", "href", "poster")) {
                if (!element.hasAttr(attribute)) continue
                val absolute = element.absUrl(attribute)
                val uri = try { URI(absolute) } catch (_: Exception) { null }
                if (uri?.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrBlank()) {
                    element.removeAttr(attribute)
                } else {
                    element.attr(attribute, absolute)
                }
            }
        }
        val bodyText = sanitized.text().normalizeWhitespace()
        val imageAlts = sanitized.select("img[alt]").map { it.attr("alt").normalizeWhitespace() }.filter { it.isNotEmpty() }
        val media = sanitized.select("img[src], video[src], audio[src], source[src], [poster]")
            .flatMap { element -> listOf("src", "poster").mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } } }
            .distinct()
        val normalized = listOf(title, bodyText, imageAlts.joinToString("\n"), media.joinToString("\n")).joinToString("\n--\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return PumResolution(PumSourceStatus.RESOLVED, key, sourceUrl, title, bodyText, imageAlts, sanitized.html(), media, hash)
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
    ): BufferedResponse {
        var request = initial
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = http.execute(request.copy(followRedirects = false))
            response.body.use { body ->
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
                    request = request.copy(url = safeUrl, method = "GET", formData = emptyMap())
                    return@repeat
                }
                return BufferedResponse(response.statusCode, readLimited(body, response.contentLength, maxBytes))
            }
        }
        throw IOException("Too many redirects")
    }

    private fun safeCardEndpoint(raw: String): String? {
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) || !uri.host.equals("gall.dcinside.com", true) || uri.port != -1 || uri.userInfo != null) return null
        if (uri.path != "/ajax/pum_ajax/get_contents") return null
        return uri.toString()
    }

    private fun readLimited(input: InputStream, contentLength: Long, maxBytes: Int): String {
        if (contentLength > maxBytes) throw IOException("Response too large")
        val output = ByteArrayOutputStream(minOf(maxBytes, if (contentLength >= 0) contentLength.toInt() else 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
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
    private enum class RedirectKind { CARD, SOURCE }
    private class UnsafeRedirectException : IOException()

    companion object {
        const val CARD_MAX_BYTES = 512 * 1024
        const val SOURCE_MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
