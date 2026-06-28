package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import java.net.URLDecoder


data class DcconRef(
    val token: String,
    val url: String,
    val label: String? = null,
    val source: String? = null
)

data class DcconMatch(
    val ref: DcconRef,
    val blacklistToken: String
)

object DcconFilter {
    private const val MIN_PREFIX_MATCH_LENGTH = 40
    private val dcconUrlPattern = Regex("(?:https?:)?//[^\\s'\"<>]+/dccon\\.php\\?[^\\s'\"<>]*no=([^&\\s'\"<>]+)[^\\s'\"<>]*|(?:^|[^A-Za-z0-9_./-])(dccon\\.php\\?[^\\s'\"<>]*no=([^&\\s'\"<>]+)[^\\s'\"<>]*)")
    private val tokenCharsPattern = Regex("^[A-Za-z0-9._%+=:-]+$")

    fun normalizeBlacklistEntry(entry: String): String? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return null
        extractTokenFromText(trimmed)?.let { return it }
        return trimmed
            .takeIf { it.matches(tokenCharsPattern) }
            ?.let { decodeHtmlAndUrl(it) }
    }

    fun extractDcconRefs(html: String): List<DcconRef> {
        if (html.isBlank() || !html.contains("dccon.php", ignoreCase = true)) return emptyList()
        val doc = Jsoup.parseBodyFragment(html)
        val refs = linkedMapOf<String, DcconRef>()

        doc.select("*[src], *[data-src]").forEach { element ->
            val label = firstNonBlank(element.attr("conalt"), element.attr("alt"), element.attr("title"))
            listOf("src", "data-src").forEach { attr ->
                val value = element.attr(attr)
                extractTokenFromText(value)?.let { token ->
                    val url = extractUrlFromText(value) ?: value
                    refs.putIfAbsent(token, DcconRef(token, decodeHtmlAndUrl(url), label, attr))
                }
            }
        }

        doc.select("*").forEach { element ->
            val label = firstNonBlank(element.attr("conalt"), element.attr("alt"), element.attr("title"))
            element.attributes().forEach { attribute ->
                val value = attribute.value
                if (value.contains("dccon.php", ignoreCase = true)) {
                    dcconUrlPattern.findAll(value).forEach { match ->
                        val token = decodeHtmlAndUrl(match.groupValues[1].ifBlank { match.groupValues[3] })
                        val url = extractUrlFromText(match.value) ?: match.value.trim()
                        refs.putIfAbsent(token, DcconRef(token, decodeHtmlAndUrl(url), label, attribute.key))
                    }
                }
            }
        }

        if (refs.isEmpty()) {
            dcconUrlPattern.findAll(html).forEach { match ->
                val token = decodeHtmlAndUrl(match.groupValues[1].ifBlank { match.groupValues[3] })
                val url = extractUrlFromText(match.value) ?: match.value.trim()
                refs.putIfAbsent(token, DcconRef(token, decodeHtmlAndUrl(url), null, "raw"))
            }
        }
        return refs.values.toList()
    }

    fun findBlockedDccon(html: String, blacklist: List<String>): DcconMatch? {
        val tokens = blacklist.mapNotNull { normalizeBlacklistEntry(it) }
        if (tokens.isEmpty()) return null
        return extractDcconRefs(html).firstNotNullOfOrNull { ref ->
            tokens.firstOrNull { blocked -> tokensMatch(blocked, ref.token) }?.let { DcconMatch(ref, it) }
        }
    }

    fun tokensMatch(blacklistToken: String, detectedToken: String): Boolean {
        if (blacklistToken.isBlank() || detectedToken.isBlank()) return false
        if (blacklistToken == detectedToken) return true
        if (blacklistToken.length < MIN_PREFIX_MATCH_LENGTH || detectedToken.length < MIN_PREFIX_MATCH_LENGTH) return false
        return detectedToken.startsWith(blacklistToken) || blacklistToken.startsWith(detectedToken)
    }

    private fun extractTokenFromText(text: String): String? {
        if (!text.contains("dccon.php", ignoreCase = true)) return null
        val normalized = text.replace("&amp;", "&")
        Regex("(?:[?&]|&amp;)no=([^&\\s'\"<>]+)", RegexOption.IGNORE_CASE).find(normalized)?.let {
            return decodeHtmlAndUrl(it.groupValues[1])
        }
        val match = dcconUrlPattern.find(normalized) ?: return null
        return decodeHtmlAndUrl(match.groupValues[1].ifBlank { match.groupValues[3] })
    }

    private fun extractUrlFromText(text: String): String? {
        val normalized = text.replace("&amp;", "&")
        val start = normalized.indexOf("dccon.php", ignoreCase = true)
        if (start < 0) return null
        val httpsStart = normalized.lastIndexOf("https://", start, ignoreCase = true)
        val httpStart = normalized.lastIndexOf("http://", start, ignoreCase = true)
        val protocolRelativeStart = normalized.lastIndexOf("//", start)
        val prefixStart = when {
            httpsStart >= 0 -> httpsStart
            httpStart >= 0 -> httpStart
            protocolRelativeStart >= 0 -> protocolRelativeStart
            else -> start
        }
        val end = normalized.indexOfAny(charArrayOf('\'', '"', '<', '>', ' ', '\n', '\r', '\t'), prefixStart).let { if (it < 0) normalized.length else it }
        return normalized.substring(prefixStart, end).trimStart('(', ',', ' ')
    }

    private fun firstNonBlank(vararg values: String): String? = values.firstOrNull { it.isNotBlank() }

    private fun decodeHtmlAndUrl(value: String): String {
        val htmlDecoded = value.replace("&amp;", "&").trim()
        return runCatching { URLDecoder.decode(htmlDecoded, "UTF-8") }.getOrDefault(htmlDecoded)
    }
}
