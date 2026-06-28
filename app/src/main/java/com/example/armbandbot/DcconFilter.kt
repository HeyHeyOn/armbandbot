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

data class DcconPackageDetail(
    val packageIdx: String,
    val title: String,
    val tokens: List<String>
)

data class ImageAltRef(
    val alt: String,
    val imageUrl: String? = null
)

data class DcconBlacklistGroup(
    val packageName: String,
    val tokens: List<String>,
    val representativeToken: String,
    val isUngrouped: Boolean = false
)

data class DcconPreviewItem(
    val token: String,
    val packageName: String,
    val imageUrl: String
)

object DcconFilter {
    private const val MIN_PREFIX_MATCH_LENGTH = 40
    private val dcconUrlPattern = Regex("""(?:https?:)?//[^\\\s'"<>]+/dccon\.php\?[^\\\s'"<>]*no=([^&\\\s'"<>]+)[^\\\s'"<>]*|(?:^|[^A-Za-z0-9_./-])(dccon\.php\?[^\\\s'"<>]*no=([^&\\\s'"<>]+)[^\\\s'"<>]*)""")
    private val tokenCharsPattern = Regex("^[A-Za-z0-9._%+=:-]+$")

    fun normalizeBlacklistEntry(entry: String): String? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return null
        extractTokenFromText(trimmed)?.let { return it }
        return trimmed
            .takeIf { it.matches(tokenCharsPattern) }
            ?.let { decodeHtmlAndUrl(it) }
    }

    fun normalizeBlacklistText(text: String): String = text
        .lineSequence()
        .map { raw ->
            val comment = raw.substringAfter("#", "").trim()
            val token = normalizeBlacklistEntry(raw.substringBefore("#")) ?: raw.substringBefore("#").trim()
            if (comment.isNotEmpty()) "$token #$comment" else token
        }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString("\n")

    fun extractImageAltRefs(html: String): List<String> {
        return extractImageAltImageRefs(html).map { it.alt }
    }

    fun extractImageAltImageRefs(html: String): List<ImageAltRef> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parseBodyFragment(html)
        val refs = linkedMapOf<String, ImageAltRef>()
        val roots = doc.select(".write_div")
        val imageElements = if (roots.isNotEmpty()) roots.select("img") else doc.select("img")
        imageElements
            .filterNot { isDcconElement(it) }
            .forEach { img ->
                val alt = img.attr("alt").trim().takeIf { it.isNotBlank() } ?: return@forEach
                refs.putIfAbsent(alt, ImageAltRef(alt, resolveImageUrl(img.attr("src").ifBlank { img.attr("data-original") }.ifBlank { img.attr("data-src") })))
            }
        return refs.values.toList()
    }

    fun imageAltMatchValue(entry: String): String = entry.substringBefore("#").trim()

    fun normalizeImageAltBlacklistText(text: String): String = text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { line ->
            val alt = imageAltMatchValue(line)
            val previewUrl = line.substringAfter("#", "").trim()
            if (previewUrl.isNotBlank()) "$alt #$previewUrl" else alt
        }
        .distinctBy { imageAltMatchValue(it) }
        .joinToString("\n")

    fun imageAltBlacklistValues(text: String): List<String> = normalizeImageAltBlacklistText(text)
        .lineSequence()
        .map { imageAltMatchValue(it) }
        .filter { it.isNotBlank() }
        .toList()

    fun addImageAltBlacklistEntries(existingText: String, newEntriesText: String): String {
        val entries = normalizeImageAltBlacklistText(existingText)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
        val existingAlts = entries.map { imageAltMatchValue(it) }.toMutableSet()
        normalizeImageAltBlacklistText(newEntriesText).lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val alt = imageAltMatchValue(line)
                if (alt.isNotBlank() && existingAlts.add(alt)) entries.add(line)
            }
        return entries.joinToString("\n")
    }

    fun removeImageAltBlacklistEntries(existingText: String, entriesToRemove: Set<String>): String {
        val removeAlts = entriesToRemove.map { imageAltMatchValue(it) }.filter { it.isNotBlank() }.toSet()
        if (removeAlts.isEmpty()) return normalizeImageAltBlacklistText(existingText)
        return normalizeImageAltBlacklistText(existingText)
            .lineSequence()
            .map { it.trim() }
            .filter { line -> imageAltMatchValue(line) !in removeAlts }
            .joinToString("\n")
    }

    fun addSelectedImageAltRefs(existingText: String, refs: List<ImageAltRef>, selectedAlts: Set<String>): String {
        if (selectedAlts.isEmpty()) return normalizeImageAltBlacklistText(existingText)
        val selectedEntries = refs
            .filter { it.alt in selectedAlts }
            .joinToString("\n") { ref -> if (ref.imageUrl != null) "${ref.alt} #${ref.imageUrl}" else ref.alt }
        return addImageAltBlacklistEntries(existingText, selectedEntries)
    }

    fun extractDcconRefsFromCommentApiJson(json: String): List<DcconRef> {
        if (json.isBlank() || !json.contains("dccon.php", ignoreCase = true)) return emptyList()
        val refs = linkedMapOf<String, DcconRef>()
        val commentObjectPattern = Regex("\\{[^{}]*\\\"memo\\\"\\s*:\\s*\\\"", RegexOption.DOT_MATCHES_ALL)
        commentObjectPattern.findAll(json).forEach { startMatch ->
            val objectStart = startMatch.range.first
            val objectEnd = findJsonObjectEnd(json, objectStart) ?: return@forEach
            val commentObject = json.substring(objectStart, objectEnd + 1)
            val commentNo = extractJsonStringField(commentObject, "no").orEmpty()
            val memo = extractJsonStringField(commentObject, "memo").orEmpty()
            extractDcconRefs(memo).forEach { ref ->
                refs.putIfAbsent(
                    ref.token,
                    ref.copy(source = "comment:${commentNo.ifBlank { refs.size.toString() }}")
                )
            }
        }
        return refs.values.toList()
    }

    fun parsePackageDetailJson(json: String): DcconPackageDetail? {
        if (json.isBlank() || json.trim() == "error") return null
        val infoObject = extractJsonObject(json, "info") ?: return null
        val packageIdx = extractJsonStringField(infoObject, "package_idx")?.takeIf { it.isNotBlank() } ?: return null
        val title = extractJsonStringField(infoObject, "title")?.ifBlank { "디시콘 세트" } ?: "디시콘 세트"
        val detailArray = extractJsonArray(json, "detail") ?: return null
        val tokens = linkedSetOf<String>()
        Regex("\\{[^{}]*\\}", RegexOption.DOT_MATCHES_ALL).findAll(detailArray).forEach { match ->
            val token = extractJsonStringField(match.value, "path").orEmpty()
            normalizeBlacklistEntry(token)?.let { tokens.add(it) }
        }
        if (tokens.isEmpty()) return null
        return DcconPackageDetail(packageIdx, title, tokens.toList())
    }

    fun mergePackageTokensIntoBlacklist(existingText: String, packageDetail: DcconPackageDetail): String {
        val entries = normalizedEntryMap(existingText).toMutableMap()
        packageDetail.tokens.forEach { rawToken ->
            val token = normalizeBlacklistEntry(rawToken) ?: return@forEach
            entries[token] = packageDetail.title
        }
        return entriesToText(entries)
    }

    fun addSingleTokenWithPackageTitle(existingText: String, tokenOrUrl: String, packageDetail: DcconPackageDetail?): String {
        val token = normalizeBlacklistEntry(tokenOrUrl) ?: return normalizeBlacklistText(existingText)
        val entries = normalizedEntryMap(existingText).toMutableMap()
        val packageTitle = packageDetail?.title?.takeIf { it.isNotBlank() }
        if (!entries.containsKey(token) || entries[token].isNullOrBlank() || packageTitle != null) {
            entries[token] = packageTitle ?: entries[token].orEmpty()
        }
        return entriesToText(entries)
    }

    fun isTokenBlocked(existingText: String, tokenOrUrl: String): Boolean {
        val token = normalizeBlacklistEntry(tokenOrUrl) ?: return false
        return normalizedEntryMap(existingText).keys.any { blocked -> tokensMatch(blocked, token) }
    }

    fun buildImageUrl(tokenOrUrl: String): String {
        val token = normalizeBlacklistEntry(tokenOrUrl) ?: tokenOrUrl.trim()
        return if (token.contains("dccon.php", ignoreCase = true)) token else "https://dcimg5.dcinside.com/dccon.php?no=$token"
    }

    private fun resolveImageUrl(rawUrl: String): String? {
        val src = rawUrl.trim()
        return when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> null
        }
    }

    fun addBlacklistEntries(existingText: String, newEntriesText: String): String {
        val lines = normalizeBlacklistText(existingText)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
        val existingTokens = lines.mapNotNull { normalizeBlacklistEntry(it.substringBefore("#")) }.toMutableSet()
        normalizeBlacklistText(newEntriesText).lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val token = normalizeBlacklistEntry(line.substringBefore("#")) ?: return@forEach
                val comment = line.substringAfter("#", "").trim()
                if (existingTokens.add(token)) {
                    lines.add(if (comment.isNotEmpty()) "$token #$comment" else token)
                }
            }
        return lines.joinToString("\n")
    }

    fun removeBlacklistTokens(existingText: String, tokensToRemove: Set<String>): String {
        val normalizedRemove = tokensToRemove.mapNotNull { normalizeBlacklistEntry(it) }.toSet()
        if (normalizedRemove.isEmpty()) return normalizeBlacklistText(existingText)
        return normalizeBlacklistText(existingText)
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                val token = normalizeBlacklistEntry(line.substringBefore("#"))
                token == null || token !in normalizedRemove
            }
            .joinToString("\n")
    }

    fun groupBlacklistEntries(text: String): List<DcconBlacklistGroup> {
        val groupedTokens = linkedMapOf<String, MutableList<String>>()
        normalizeBlacklistText(text)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val token = normalizeBlacklistEntry(line.substringBefore("#")) ?: return@forEach
                val packageName = line.substringAfter("#", "")
                    .trim()
                    .ifBlank { "개별 디시콘" }
                groupedTokens.getOrPut(packageName) { mutableListOf() }.add(token)
            }
        return groupedTokens.map { (packageName, tokens) ->
            val distinctTokens = tokens.distinct()
            DcconBlacklistGroup(
                packageName = packageName,
                tokens = distinctTokens,
                representativeToken = distinctTokens.first(),
                isUngrouped = packageName == "개별 디시콘"
            )
        }
    }

    fun toggleVisibleTokenSelection(
        currentSelection: Set<String>,
        visibleTokens: List<String>,
        selectAll: Boolean
    ): Set<String> {
        val normalizedVisible = visibleTokens.mapNotNull { normalizeBlacklistEntry(it) }.toSet()
        return if (selectAll) currentSelection + normalizedVisible else currentSelection - normalizedVisible
    }

    fun previewItemsFromBlacklistText(text: String): List<DcconPreviewItem> = normalizeBlacklistText(text)
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val token = normalizeBlacklistEntry(line.substringBefore("#")) ?: return@mapNotNull null
            val packageName = line.substringAfter("#", "").trim().ifBlank { "개별 디시콘" }
            DcconPreviewItem(token, packageName, buildImageUrl(token))
        }
        .distinctBy { it.token }
        .toList()

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
                if (attribute.key == "src" || attribute.key == "data-src") return@forEach
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

    fun extractDcconRefsForDisplay(html: String): List<DcconRef> {
        if (html.isBlank() || !html.contains("dccon.php", ignoreCase = true)) return emptyList()
        val doc = Jsoup.parseBodyFragment(html)
        val refs = mutableListOf<DcconRef>()

        fun addFrom(value: String, label: String?, source: String) {
            extractTokenFromText(value)?.let { token ->
                val url = extractUrlFromText(value) ?: value
                refs.add(DcconRef(token, decodeHtmlAndUrl(url), label, source))
            }
        }

        doc.select("*[src], *[data-src]").forEach { element ->
            val label = firstNonBlank(element.attr("conalt"), element.attr("alt"), element.attr("title"))
            listOf("src", "data-src").forEach { attr -> addFrom(element.attr(attr), label, attr) }
        }

        doc.select("*").forEach { element ->
            val label = firstNonBlank(element.attr("conalt"), element.attr("alt"), element.attr("title"))
            element.attributes().forEach { attribute ->
                if (attribute.key == "src" || attribute.key == "data-src") return@forEach
                val value = attribute.value
                if (value.contains("dccon.php", ignoreCase = true)) {
                    dcconUrlPattern.findAll(value).forEach { match ->
                        val token = decodeHtmlAndUrl(match.groupValues[1].ifBlank { match.groupValues[3] })
                        val url = extractUrlFromText(match.value) ?: match.value.trim()
                        refs.add(DcconRef(token, decodeHtmlAndUrl(url), label, attribute.key))
                    }
                }
            }
        }

        if (refs.isEmpty()) {
            dcconUrlPattern.findAll(html).forEach { match ->
                val token = decodeHtmlAndUrl(match.groupValues[1].ifBlank { match.groupValues[3] })
                val url = extractUrlFromText(match.value) ?: match.value.trim()
                refs.add(DcconRef(token, decodeHtmlAndUrl(url), null, "raw"))
            }
        }
        return refs
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

    private fun normalizedEntryMap(text: String): LinkedHashMap<String, String> {
        val entries = linkedMapOf<String, String>()
        normalizeBlacklistText(text)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val token = normalizeBlacklistEntry(line.substringBefore("#")) ?: return@forEach
                val comment = line.substringAfter("#", "").trim()
                entries.putIfAbsent(token, comment)
            }
        return entries
    }

    private fun entriesToText(entries: Map<String, String>): String = entries
        .map { (token, comment) -> if (comment.isNotBlank()) "$token #$comment" else token }
        .joinToString("\n")

    private fun extractTokenFromText(text: String): String? {
        if (!text.contains("dccon.php", ignoreCase = true)) return null
        val normalized = text.replace("&amp;", "&")
        Regex("""(?:[?&]|&amp;)no=([^&\\\s'"<>]+)""", RegexOption.IGNORE_CASE).find(normalized)?.let {
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
        val end = normalized.indexOfAny(charArrayOf('\\', '\'', '"', '<', '>', ' ', '\n', '\r', '\t'), prefixStart).let { if (it < 0) normalized.length else it }
        return normalized.substring(prefixStart, end).trimStart('(', ',', ' ')
    }

    private fun isDcconElement(element: org.jsoup.nodes.Element): Boolean {
        val src = listOf("src", "data-src").joinToString(" ") { element.attr(it) }
        return element.hasClass("written_dccon") ||
                src.contains("dccon.php", ignoreCase = true) ||
                element.hasAttr("conalt") ||
                element.hasAttr("data-dcconoverstatus")
    }

    private fun firstNonBlank(vararg values: String): String? = values.firstOrNull { it.isNotBlank() }

    private fun extractJsonObject(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIndex = json.indexOf(key)
        if (keyIndex < 0) return null
        val objectStart = json.indexOf('{', keyIndex)
        if (objectStart < 0) return null
        val objectEnd = findJsonObjectEnd(json, objectStart) ?: return null
        return json.substring(objectStart, objectEnd + 1)
    }

    private fun extractJsonArray(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIndex = json.indexOf(key)
        if (keyIndex < 0) return null
        val arrayStart = json.indexOf('[', keyIndex)
        if (arrayStart < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in arrayStart until json.length) {
            val ch = json[i]
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = !inString
            } else if (!inString) {
                if (ch == '[') depth++
                if (ch == ']') {
                    depth--
                    if (depth == 0) return json.substring(arrayStart, i + 1)
                }
            }
        }
        return null
    }

    private fun findJsonObjectEnd(json: String, objectStart: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in objectStart until json.length) {
            val ch = json[i]
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = !inString
            } else if (!inString) {
                if (ch == '{') depth++
                if (ch == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun extractJsonStringField(jsonObject: String, field: String): String? {
        val key = "\"$field\""
        val keyIndex = jsonObject.indexOf(key)
        if (keyIndex < 0) return null
        val colon = jsonObject.indexOf(':', keyIndex + key.length)
        if (colon < 0) return null
        val quote = jsonObject.indexOf('"', colon + 1)
        if (quote < 0) return null
        val out = StringBuilder()
        var i = quote + 1
        while (i < jsonObject.length) {
            val ch = jsonObject[i]
            if (ch == '"') {
                return decodeJsonUnicodeEscapes(out.toString())
            }
            if (ch == '\\') {
                val escaped = jsonObject.getOrNull(i + 1) ?: return null
                when (escaped) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (i + 6 > jsonObject.length) return null
                        val hex = jsonObject.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16) ?: return null
                        out.append(code.toChar())
                        i += 4
                    }
                    else -> out.append(escaped)
                }
                i += 2
            } else {
                out.append(ch)
                i += 1
            }
        }
        return null
    }

    private fun decodeJsonUnicodeEscapes(value: String): String {
        var decoded = value
        repeat(2) {
            val next = Regex("""\\+u([0-9a-fA-F]{4})""").replace(decoded) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            if (next == decoded) return decoded
            decoded = next
        }
        return decoded
    }

    private fun decodeHtmlAndUrl(value: String): String {
        val htmlDecoded = value.replace("&amp;", "&").trim()
        return runCatching { URLDecoder.decode(htmlDecoded, "UTF-8") }.getOrDefault(htmlDecoded)
    }
}
