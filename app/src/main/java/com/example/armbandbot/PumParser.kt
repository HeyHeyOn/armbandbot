package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

object PumParser {
    private const val CARD_ENDPOINT = "https://gall.dcinside.com/ajax/pum_ajax/get_contents"
    private val assignmentPattern = Regex("""(?:var\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*['\"]([^'\"]+)['\"]""")
    private val ajaxPattern = Regex("""(?:\$\s*\.\s*)?ajax\s*\(""", RegexOption.IGNORE_CASE)

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
        val links = body.select("a[href]")
        val parsed = links.map { link ->
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
        for (ajax in ajaxPattern.findAll(code)) {
            val openParen = ajax.range.last
            val closeParen = matchingDelimiter(code, openParen, '(', ')') ?: continue
            val block = code.substring(openParen + 1, closeParen)
            val endpointRaw = Regex("""url\s*:\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1) ?: continue
            val endpoint = canonicalCardEndpoint(endpointRaw) ?: continue
            val dataLabel = Regex("""\bdata\s*:""", RegexOption.IGNORE_CASE).find(block) ?: continue
            val dataOpen = block.indexOf('{', dataLabel.range.last + 1).takeIf { it >= 0 } ?: continue
            val dataClose = matchingDelimiter(block, dataOpen, '{', '}') ?: continue
            val dataBlock = block.substring(dataOpen + 1, dataClose)

            // Only assignments visible before this AJAX invocation may supply literal values.
            val assignments = linkedMapOf<String, String>()
            assignmentPattern.findAll(code.substring(0, ajax.range.first)).forEach {
                assignments[it.groupValues[1]] = it.groupValues[2]
            }
            val values = linkedMapOf<String, String>()
            Regex("""['\"]?([A-Za-z_][A-Za-z0-9_]*)['\"]?\s*:\s*(?:['\"]([^'\"]+)['\"]|([A-Za-z_][A-Za-z0-9_]*))""")
                .findAll(dataBlock).forEach { match ->
                    val resolved = match.groupValues[2].ifBlank { assignments[match.groupValues[3]].orEmpty() }
                    if (resolved.isNotBlank()) values[match.groupValues[1]] = resolved
                }
            val id = values["gall_id"] ?: values["id"] ?: continue
            val no = values["gall_no"] ?: values["no"] ?: continue
            val type = (values["gall_type"] ?: values["gallery_type"] ?: values["_GALLTYPE_"] ?: "G").uppercase()
            if (type !in setOf("G", "M", "MI") || !id.matches(Regex("[A-Za-z0-9_-]+")) || !no.matches(Regex("[0-9]+"))) continue
            return PumLoaderRequest(endpoint, PostKey(type, id, no), values.toMap())
        }
        return null
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
