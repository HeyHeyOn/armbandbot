package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

object PumParser {
    private const val CARD_ENDPOINT = "https://gall.dcinside.com/ajax/pum_ajax/get_contents"

    /** The marker is emitted as a direct child of the detail link in a DC list-title cell. */
    fun hasListMarker(document: Document): Boolean =
        document.select("tr.ub-content > td.gall_tit.ub-word > a[href] > span.font_blue009")
            .any { it.text() == "(펌)" }

    fun parseDetail(document: Document, listMarker: Boolean = hasListMarker(document)): PumDetection {
        val loader = document.select(".write_div script").asSequence().mapNotNull(::parseLoader).firstOrNull()
        val status = when {
            listMarker && loader != null -> PumDetectionStatus.PUM_CONFIRMED
            listMarker -> PumDetectionStatus.PUM_MARKER_ONLY
            loader != null -> PumDetectionStatus.PUM_LOADER_ONLY
            else -> PumDetectionStatus.NOT_PUM
        }
        return PumDetection(status, loader)
    }

    fun parseCard(html: String, outerPost: PostKey?): PumCard {
        val document = Jsoup.parse(html, "https://gall.dcinside.com/")
        val body = document.selectFirst(".cloned_card_body") ?: return PumCard(PumCardStatus.INVALID)
        // DCInside marks the original-post control explicitly. Other links are card content.
        val links = body.select("a.source_link")
        val parsed = links.map { link ->
            if (!link.hasAttr("href")) return@map null
            val raw = link.absUrl("href").ifBlank { link.attr("href") }
            DcinsidePostUrls.parseSafeCanonicalPostUrl(raw, outerPost)
        }
        if (parsed.any { it == null }) return PumCard(PumCardStatus.INVALID)
        val candidates = parsed.filterNotNull().distinctBy { it.key }
        if (candidates.size == 1) {
            val source = candidates.single()
            return PumCard(PumCardStatus.RESOLVED, source.key, source.url)
        }
        if (candidates.size > 1 || links.isNotEmpty()) return PumCard(PumCardStatus.INVALID)
        val explicitMissing = body.select(".empty, .no_content, .cloned_card_empty").any {
            val text = it.text().replace(Regex("\\s+"), " ").trim()
            text.contains("삭제되었거나 존재하지 않는 원문") || text.contains("원문이 삭제") || text.contains("원문을 찾을 수 없")
        }
        return PumCard(if (explicitMissing) PumCardStatus.MISSING else PumCardStatus.INVALID)
    }

    private fun parseLoader(script: Element): PumLoaderRequest? {
        val code = script.data().ifBlank { script.html() }
        for ((ajaxStart, openParen) in ajaxCalls(code)) {
            val closeParen = matchingDelimiter(code, openParen, '(', ')') ?: continue
            val optionsOpen = skipTrivia(code, openParen + 1, closeParen)
            if (code.getOrNull(optionsOpen) != '{') continue
            val optionsClose = matchingDelimiter(code, optionsOpen, '{', '}') ?: continue
            if (optionsClose > closeParen) continue
            val options = objectProperties(code, optionsOpen, optionsClose) ?: continue
            val endpointRaw = options.firstOrNull { it.key.equals("url", true) }
                ?.let { stringValue(code, it.valueStart, it.valueEnd) } ?: continue
            val endpoint = canonicalCardEndpoint(endpointRaw) ?: continue
            val data = options.firstOrNull { it.key.equals("data", true) } ?: continue
            val dataOpen = skipTrivia(code, data.valueStart, data.valueEnd)
            if (code.getOrNull(dataOpen) != '{') continue
            val dataClose = matchingDelimiter(code, dataOpen, '{', '}') ?: continue
            if (dataClose >= data.valueEnd) continue
            val dataProperties = objectProperties(code, dataOpen, dataClose) ?: continue

            // Only assignments visible before this AJAX invocation may supply literal values.
            val assignments = literalAssignments(code, ajaxStart)
            val values = linkedMapOf<String, String>()
            dataProperties.forEach { property ->
                val literal = stringValue(code, property.valueStart, property.valueEnd)
                val variable = identifierValue(code, property.valueStart, property.valueEnd)
                val resolved = literal ?: variable?.let(assignments::get)
                if (!resolved.isNullOrBlank()) values[property.key] = resolved
            }
            val id = values["gall_id"] ?: values["id"] ?: continue
            val no = values["gall_no"] ?: values["no"] ?: continue
            val type = (values["gall_type"] ?: values["gallery_type"] ?: values["_GALLTYPE_"] ?: "G").uppercase()
            if (type !in setOf("G", "M", "MI") || !id.matches(Regex("[A-Za-z0-9_-]+")) || !no.matches(Regex("[0-9]+"))) continue
            return PumLoaderRequest(endpoint, PostKey(type, id, no), values.toMap())
        }
        return null
    }

    private data class JsProperty(val key: String, val valueStart: Int, val valueEnd: Int)

    /** Finds executable ajax(...) calls, skipping comments and all JS string literal forms. */
    private fun ajaxCalls(code: String): List<Pair<Int, Int>> {
        val calls = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < code.length) {
            i = skipTrivia(code, i, code.length)
            val c = code.getOrNull(i) ?: break
            if (c == '\'' || c == '"' || c == '`') {
                i = stringEnd(code, i) ?: code.length
                continue
            }
            if (isIdentifierStart(c)) {
                val end = identifierEnd(code, i)
                if (code.substring(i, end).equals("ajax", true)) {
                    val open = skipTrivia(code, end, code.length)
                    if (code.getOrNull(open) == '(') calls += i to open
                }
                i = end
            } else i++
        }
        return calls
    }

    private fun literalAssignments(code: String, limit: Int): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var i = 0
        while (i < limit) {
            i = skipTrivia(code, i, limit)
            val c = code.getOrNull(i) ?: break
            if (c == '\'' || c == '"' || c == '`') {
                i = stringEnd(code, i) ?: limit
                continue
            }
            if (!isIdentifierStart(c)) { i++; continue }
            val nameEnd = identifierEnd(code, i)
            val equals = skipTrivia(code, nameEnd, limit)
            val valueStart = skipTrivia(code, equals + 1, limit)
            if (code.getOrNull(equals) == '=' && code.getOrNull(equals + 1) != '=' &&
                code.getOrNull(valueStart) in setOf('\'', '"')) {
                val valueEnd = stringEnd(code, valueStart)
                if (valueEnd != null && valueEnd <= limit) {
                    result[code.substring(i, nameEnd)] = code.substring(valueStart + 1, valueEnd - 1)
                    i = valueEnd
                    continue
                }
            }
            i = nameEnd
        }
        return result
    }

    private fun objectProperties(code: String, open: Int, close: Int): List<JsProperty>? {
        val properties = mutableListOf<JsProperty>()
        var i = open + 1
        while (true) {
            i = skipTrivia(code, i, close)
            while (code.getOrNull(i) == ',') i = skipTrivia(code, i + 1, close)
            if (i >= close) return properties
            val key: String
            if (code[i] == '\'' || code[i] == '"') {
                val end = stringEnd(code, i) ?: return null
                if (end > close) return null
                key = code.substring(i + 1, end - 1)
                i = end
            } else if (isIdentifierStart(code[i])) {
                val end = identifierEnd(code, i)
                key = code.substring(i, end)
                i = end
            } else return null
            i = skipTrivia(code, i, close)
            if (code.getOrNull(i) != ':') return null
            val valueStart = skipTrivia(code, i + 1, close)
            val valueEnd = topLevelValueEnd(code, valueStart, close) ?: return null
            properties += JsProperty(key, valueStart, valueEnd)
            i = valueEnd
        }
    }

    private fun topLevelValueEnd(code: String, start: Int, objectClose: Int): Int? {
        var round = 0
        var curly = 0
        var square = 0
        var i = start
        while (i < objectClose) {
            i = skipTrivia(code, i, objectClose)
            if (i >= objectClose) break
            when (code[i]) {
                '\'', '"', '`' -> { i = stringEnd(code, i) ?: return null; continue }
                '(' -> round++
                ')' -> round--
                '{' -> curly++
                '}' -> curly--
                '[' -> square++
                ']' -> square--
                ',' -> if (round == 0 && curly == 0 && square == 0) return i
            }
            if (round < 0 || curly < 0 || square < 0) return null
            i++
        }
        return objectClose
    }

    private fun stringValue(code: String, start: Int, end: Int): String? {
        val at = skipTrivia(code, start, end)
        if (code.getOrNull(at) !in setOf('\'', '"')) return null
        val after = stringEnd(code, at) ?: return null
        if (skipTrivia(code, after, end) != end) return null
        return code.substring(at + 1, after - 1)
    }

    private fun identifierValue(code: String, start: Int, end: Int): String? {
        val at = skipTrivia(code, start, end)
        if (!isIdentifierStart(code.getOrNull(at) ?: return null)) return null
        val after = identifierEnd(code, at)
        return code.substring(at, after).takeIf { skipTrivia(code, after, end) == end }
    }

    private fun skipTrivia(code: String, from: Int, limit: Int): Int {
        var i = from
        while (i < limit) {
            if (code[i].isWhitespace()) { i++; continue }
            if (code[i] == '/' && code.getOrNull(i + 1) == '/') {
                i += 2
                while (i < limit && code[i] != '\n' && code[i] != '\r') i++
                continue
            }
            if (code[i] == '/' && code.getOrNull(i + 1) == '*') {
                val end = code.indexOf("*/", i + 2)
                i = if (end < 0 || end + 2 > limit) limit else end + 2
                continue
            }
            break
        }
        return i
    }

    /** Returns the index immediately after a quoted literal. Templates are deliberately opaque. */
    private fun stringEnd(code: String, quoteAt: Int): Int? {
        val quote = code[quoteAt]
        var escaped = false
        var i = quoteAt + 1
        while (i < code.length) {
            val c = code[i]
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == quote) return i + 1
            i++
        }
        return null
    }

    private fun isIdentifierStart(c: Char) = c == '_' || c == '$' || c.isLetter()
    private fun isIdentifierPart(c: Char) = isIdentifierStart(c) || c.isDigit()
    private fun identifierEnd(code: String, from: Int): Int {
        var i = from + 1
        while (i < code.length && isIdentifierPart(code[i])) i++
        return i
    }

    private fun canonicalCardEndpoint(raw: String): String? {
        val uri = try { URI("https://gall.dcinside.com/").resolve(raw) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) || !uri.host.equals("gall.dcinside.com", true) || uri.port != -1 || uri.userInfo != null) return null
        if (uri.path != "/ajax/pum_ajax/get_contents" || uri.rawQuery != null || uri.rawFragment != null) return null
        return CARD_ENDPOINT
    }

    /** Finds a delimiter while ignoring quoted strings and JavaScript comments. */
    private fun matchingDelimiter(text: String, openAt: Int, open: Char, close: Char): Int? {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var i = openAt
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            if (lineComment) { if (c == '\n') lineComment = false; i++; continue }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i += 2 } else i++; continue }
            if (quote != null) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
                i++; continue
            }
            if (c == '/' && next == '/') { lineComment = true; i += 2; continue }
            if (c == '/' && next == '*') { blockComment = true; i += 2; continue }
            if (c == '\'' || c == '"' || c == '`') { quote = c; i++; continue }
            if (c == open) depth++
            if (c == close && --depth == 0) return i
            i++
        }
        return null
    }
}
