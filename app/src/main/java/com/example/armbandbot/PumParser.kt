package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

object PumParser {
    private const val CARD_ENDPOINT = "https://gall.dcinside.com/ajax/pum_ajax/get_contents"

    /** DC emits the marker either inside the detail link or as its direct sibling. */
    fun hasListMarker(document: Document): Boolean =
        document.select("tr.ub-content > td.gall_tit.ub-word > a[href]")
            .any(::hasListMarker)

    /** Checks only the supplied list row's title link, never another post in the document. */
    fun hasListMarker(titleLink: Element): Boolean {
        if (titleLink.tagName() != "a" || !titleLink.hasAttr("href")) return false
        val titleCell = titleLink.parent() ?: return false
        val row = titleCell.parent() ?: return false
        if (titleCell.tagName() != "td" || !titleCell.hasClass("gall_tit") || !titleCell.hasClass("ub-word")) return false
        if (row.tagName() != "tr" || !row.hasClass("ub-content")) return false
        val directMarkers = titleLink.children() + titleCell.children().filter { it.parent() == titleCell && it !== titleLink }
        return directMarkers.any {
            it.tagName() in setOf("span", "b") && it.hasClass("font_blue009") && it.text() == "(펌)"
        }
    }

    /** Detail pages expose the same exact marker as a direct child of the scoped title. */
    fun hasDetailMarker(document: Document): Boolean =
        document.select(".gallview_head h3.title > span.font_blue009, .gallview_head h3.title > b.font_blue009")
            .any { it.text() == "(펌)" }

    fun parseDetail(
        document: Document,
        listMarker: Boolean = hasListMarker(document) || hasDetailMarker(document),
    ): PumDetection {
        val loader = document.select(".write_div script").asSequence().mapNotNull(::parseLoader).firstOrNull()
        val status = when {
            listMarker && loader != null -> PumDetectionStatus.PUM_CONFIRMED
            listMarker -> PumDetectionStatus.PUM_MARKER_ONLY
            loader != null -> PumDetectionStatus.PUM_LOADER_ONLY
            else -> PumDetectionStatus.NOT_PUM
        }
        return PumDetection(status, loader)
    }

    fun parseCard(html: String, outerPost: PostKey?, sourceHint: PumSourceHint? = null): PumCard {
        val document = Jsoup.parse(html, "https://gall.dcinside.com/")
        val body = document.selectFirst(".cloned_card_body") ?: return PumCard(PumCardStatus.INVALID)
        // Older cards use .source_link. Current cards expose the original as a direct child;
        // the classless form is trusted only when it exactly matches the loader's source hint.
        val explicitLinks = body.select("a.source_link[href]")
        val links = if (explicitLinks.isNotEmpty()) {
            explicitLinks
        } else if (sourceHint != null) {
            body.children().filter { it.tagName() == "a" && it.hasAttr("href") }
        } else {
            emptyList()
        }
        val parsed = links.map { link ->
            val raw = link.absUrl("href").ifBlank { link.attr("href") }
            DcinsidePostUrls.parseSafeCanonicalPostUrl(raw, outerPost)
                ?.takeIf { sourceHint == null || sourceHint.matches(it.key) }
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
        if (!isLexicallyWellFormed(code)) return null
        return (listOf(code) + immediatelyInvokedFunctionBodies(code))
            .asSequence()
            .mapNotNull(::parseLoaderCode)
            .firstOrNull()
    }

    private fun parseLoaderCode(code: String): PumLoaderRequest? {
        for ((ajaxStart, openParen) in ajaxCalls(code)) {
            if (!isTopLevelAjaxStatement(code, ajaxStart)) continue
            val closeParen = matchingDelimiter(code, openParen, '(', ')') ?: continue
            if (!hasSupportedAjaxTail(code, closeParen + 1)) continue
            val optionsOpen = skipTrivia(code, openParen + 1, closeParen)
            if (code.getOrNull(optionsOpen) != '{') continue
            val optionsClose = matchingDelimiter(code, optionsOpen, '{', '}') ?: continue
            if (optionsClose > closeParen) continue
            val options = objectProperties(code, optionsOpen, optionsClose) ?: continue

            // Only top-level assignments visible before this AJAX invocation may be referenced.
            val assignments = topLevelAssignments(code, ajaxStart) ?: continue
            val endpointProperty = options.firstOrNull { it.key.equals("url", true) } ?: continue
            val endpointRaw = resolveScalar(code, endpointProperty.valueStart, endpointProperty.valueEnd, assignments)
                ?.takeIf { it.isNotBlank() } ?: continue
            val endpoint = canonicalCardEndpoint(endpointRaw) ?: continue

            val dataProperty = options.firstOrNull { it.key.equals("data", true) } ?: continue
            val dataExpression = resolveExpression(code, dataProperty.valueStart, dataProperty.valueEnd, assignments) ?: continue
            val dataOpen = skipTrivia(code, dataExpression.start, dataExpression.end)
            if (code.getOrNull(dataOpen) != '{') continue
            val dataClose = matchingDelimiter(code, dataOpen, '{', '}') ?: continue
            if (dataClose >= dataExpression.end || skipTrivia(code, dataClose + 1, dataExpression.end) != dataExpression.end) continue
            val dataProperties = objectProperties(code, dataOpen, dataClose) ?: continue

            val values = linkedMapOf<String, String>()
            dataProperties.forEach { property ->
                resolveScalar(code, property.valueStart, property.valueEnd, assignments)?.let { resolved ->
                    values[property.key] = resolved
                }
            }
            val id = values["gall_id"] ?: values["id"] ?: continue
            val no = values["gall_no"] ?: values["no"] ?: continue
            val rawType = values["gall_type"] ?: values["gallery_type"] ?: values["_GALLTYPE_"]
            val type = rawType?.takeIf { it.isNotBlank() }?.uppercase()
            if ((type != null && type !in setOf("G", "M", "MI")) ||
                !id.matches(Regex("[A-Za-z0-9_-]+")) || !no.matches(Regex("[0-9]+"))) continue
            return PumLoaderRequest(endpoint, PumSourceHint(type, id, no), values.toMap())
        }
        return null
    }

    /**
     * DC's live loader consumes the card with one jQuery `.done(...)` callback. The callback is not
     * interpreted; it only has to be one balanced call that terminates the top-level AJAX statement.
     * Other chains and trailing statements remain unsupported so decoy prefixes still fail closed.
     */
    private fun hasSupportedAjaxTail(code: String, start: Int): Boolean {
        var at = skipTrivia(code, start, code.length)
        if (at == code.length) return true
        if (code.getOrNull(at) == ';') return skipTrivia(code, at + 1, code.length) == code.length
        if (code.getOrNull(at) != '.') return false
        at = skipTrivia(code, at + 1, code.length)
        val nameEnd = identifierEnd(code, at)
        if (nameEnd == at || code.substring(at, nameEnd) != "done") return false
        val open = skipTrivia(code, nameEnd, code.length)
        if (code.getOrNull(open) != '(') return false
        val close = matchingDelimiter(code, open, '(', ')') ?: return false
        at = skipTrivia(code, close + 1, code.length)
        if (code.getOrNull(at) == ';') at = skipTrivia(code, at + 1, code.length)
        return at == code.length
    }

    private data class JsProperty(val key: String, val valueStart: Int, val valueEnd: Int)
    private data class JsExpression(val start: Int, val end: Int)

    /** Extracts only anonymous `(function(...) { ... })(...)` bodies used by DC's live loader. */
    private fun immediatelyInvokedFunctionBodies(code: String): List<String> {
        val bodies = mutableListOf<String>()
        val roundOpens = mutableListOf<Int>()
        var i = 0
        var statementStart = 0
        var braceDepth = 0
        var squareDepth = 0
        while (i < code.length) {
            i = skipTrivia(code, i, code.length)
            val current = code.getOrNull(i) ?: break
            if (current == '\'' || current == '"' || current == '`') {
                i = stringEnd(code, i) ?: return emptyList()
                continue
            }
            if (!isIdentifierStart(current)) {
                when (current) {
                    '{' -> braceDepth++
                    '}' -> if (--braceDepth < 0) return emptyList()
                    '(' -> roundOpens += i
                    ')' -> if (roundOpens.isEmpty()) return emptyList() else roundOpens.removeAt(roundOpens.lastIndex)
                    '[' -> squareDepth++
                    ']' -> if (--squareDepth < 0) return emptyList()
                    ';' -> if (braceDepth == 0 && roundOpens.isEmpty() && squareDepth == 0) statementStart = i + 1
                }
                i++
                continue
            }
            val identifierEnd = identifierEnd(code, i)
            if (code.substring(i, identifierEnd) != "function") {
                i = identifierEnd
                continue
            }
            val wrapperOpen = roundOpens.singleOrNull()
            val parametersOpen = skipTrivia(code, identifierEnd, code.length)
            if (braceDepth != 0 || squareDepth != 0 || wrapperOpen == null ||
                skipTrivia(code, 0, wrapperOpen) != wrapperOpen ||
                skipTrivia(code, wrapperOpen + 1, i) != i || code.getOrNull(parametersOpen) != '(') {
                i = identifierEnd
                continue
            }
            val parametersClose = matchingDelimiter(code, parametersOpen, '(', ')') ?: return emptyList()
            val bodyOpen = skipTrivia(code, parametersClose + 1, code.length)
            if (code.getOrNull(bodyOpen) != '{') {
                i = parametersClose + 1
                continue
            }
            val bodyClose = matchingDelimiter(code, bodyOpen, '{', '}') ?: return emptyList()
            val wrapperClose = skipTrivia(code, bodyClose + 1, code.length)
            val invocationOpen = skipTrivia(code, wrapperClose + 1, code.length)
            val invocationClose = if (code.getOrNull(invocationOpen) == '(') {
                matchingDelimiter(code, invocationOpen, '(', ')')
            } else null
            if (code.getOrNull(wrapperClose) != ')' || invocationClose == null) {
                i = bodyClose + 1
                continue
            }
            var afterInvocation = skipTrivia(code, invocationClose + 1, code.length)
            if (code.getOrNull(afterInvocation) == ';') {
                afterInvocation = skipTrivia(code, afterInvocation + 1, code.length)
            }
            if (afterInvocation != code.length) {
                i = bodyClose + 1
                continue
            }
            bodies += code.substring(bodyOpen + 1, bodyClose)
            i = bodyClose + 1
        }
        return bodies.takeIf { braceDepth == 0 && squareDepth == 0 && roundOpens.isEmpty() }.orEmpty()
    }

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

    /** Allows only a standalone top-level `ajax(...)` or `$.ajax(...)` statement. */
    private fun isTopLevelAjaxStatement(code: String, ajaxStart: Int): Boolean {
        var braceDepth = 0
        var roundDepth = 0
        var squareDepth = 0
        var statementStart = 0
        var i = 0
        while (i < ajaxStart) {
            i = skipTrivia(code, i, ajaxStart)
            if (i >= ajaxStart) break
            when (code[i]) {
                '\'', '"', '`' -> {
                    i = stringEnd(code, i) ?: return false
                    continue
                }
                '{' -> braceDepth++
                '}' -> if (--braceDepth < 0) return false
                '(' -> roundDepth++
                ')' -> if (--roundDepth < 0) return false
                '[' -> squareDepth++
                ']' -> if (--squareDepth < 0) return false
                ';' -> if (braceDepth == 0 && roundDepth == 0 && squareDepth == 0) statementStart = i + 1
            }
            i++
        }
        if (braceDepth != 0 || roundDepth != 0 || squareDepth != 0) return false

        val start = skipTrivia(code, statementStart, ajaxStart)
        if (start == ajaxStart) return true
        if (code.getOrNull(start) != '$') return false
        val dot = skipTrivia(code, start + 1, ajaxStart)
        return code.getOrNull(dot) == '.' && skipTrivia(code, dot + 1, ajaxStart) == ajaxStart
    }

    /** Rejects a valid-looking loader prefix inside a syntactically truncated script. */
    private fun isLexicallyWellFormed(code: String): Boolean {
        val expectedClosers = mutableListOf<Char>()
        var i = 0
        while (i < code.length) {
            if (code[i] == '*' && code.getOrNull(i + 1) == '/') return false
            if (code[i] == '/' && code.getOrNull(i + 1) == '/') {
                i += 2
                while (i < code.length && code[i] != '\n' && code[i] != '\r') i++
                continue
            }
            if (code[i] == '/' && code.getOrNull(i + 1) == '*') {
                val end = code.indexOf("*/", i + 2)
                if (end < 0) return false
                i = end + 2
                continue
            }
            if (code[i] == '\'' || code[i] == '"' || code[i] == '`') {
                i = stringEnd(code, i) ?: return false
                continue
            }
            when (code[i]) {
                '(' -> expectedClosers += ')'
                '{' -> expectedClosers += '}'
                '[' -> expectedClosers += ']'
                ')', '}', ']' -> {
                    if (expectedClosers.lastOrNull() != code[i]) return false
                    expectedClosers.removeAt(expectedClosers.lastIndex)
                }
            }
            i++
        }
        return expectedClosers.isEmpty()
    }

    private fun topLevelAssignments(code: String, limit: Int): Map<String, JsExpression>? {
        val result = linkedMapOf<String, JsExpression>()
        var braceDepth = 0
        var roundDepth = 0
        var squareDepth = 0
        var statementStart = 0
        var i = 0
        while (i < limit) {
            i = skipTrivia(code, i, limit)
            val c = code.getOrNull(i) ?: break
            if (c == '\'' || c == '"' || c == '`') {
                i = stringEnd(code, i) ?: return null
                continue
            }
            if (c == '{') { braceDepth++; i++; continue }
            if (c == '}') {
                if (braceDepth == 0) return null
                braceDepth--; i++; continue
            }
            if (c == '(') { roundDepth++; i++; continue }
            if (c == ')') {
                if (roundDepth == 0) return null
                roundDepth--; i++; continue
            }
            if (c == '[') { squareDepth++; i++; continue }
            if (c == ']') {
                if (squareDepth == 0) return null
                squareDepth--; i++; continue
            }
            if (c == ';') {
                if (braceDepth == 0 && roundDepth == 0 && squareDepth == 0) statementStart = i + 1
                i++
                continue
            }
            if (!isIdentifierStart(c)) { i++; continue }
            val nameEnd = identifierEnd(code, i)
            val equals = skipTrivia(code, nameEnd, limit)
            val valueStart = skipTrivia(code, equals + 1, limit)
            if (braceDepth == 0 && roundDepth == 0 && squareDepth == 0 &&
                isAssignmentTargetContext(code, statementStart, i) && code.getOrNull(equals) == '=' &&
                code.getOrNull(equals - 1) !in setOf('=', '!', '<', '>') && code.getOrNull(equals + 1) !in setOf('=', '>')) {
                val valueEnd = assignmentValueEnd(code, valueStart, limit) ?: return null
                result[code.substring(i, nameEnd)] = JsExpression(valueStart, valueEnd)
                i = if (code.getOrNull(valueEnd) == ';') {
                    statementStart = valueEnd + 1
                    valueEnd + 1
                } else valueEnd
                continue
            }
            i = nameEnd
        }
        return result.takeIf { braceDepth == 0 && roundDepth == 0 && squareDepth == 0 }
    }

    /** Accepts declarations and standalone `name = ...` statements, never member or nested targets. */
    private fun isAssignmentTargetContext(code: String, statementStart: Int, targetStart: Int): Boolean {
        val prefixStart = skipTrivia(code, statementStart, targetStart)
        if (prefixStart == targetStart) return true
        if (!isIdentifierStart(code.getOrNull(prefixStart) ?: return false)) return false
        val keywordEnd = identifierEnd(code, prefixStart)
        val keyword = code.substring(prefixStart, keywordEnd)
        return keyword in setOf("var", "let", "const") && skipTrivia(code, keywordEnd, targetStart) == targetStart
    }

    private fun assignmentValueEnd(code: String, start: Int, limit: Int): Int? {
        var round = 0
        var curly = 0
        var square = 0
        var i = start
        while (i < limit) {
            i = skipTrivia(code, i, limit)
            if (i >= limit) break
            when (code[i]) {
                '\'', '"', '`' -> { i = stringEnd(code, i) ?: return null; continue }
                '(' -> round++
                ')' -> round--
                '{' -> curly++
                '}' -> curly--
                '[' -> square++
                ']' -> square--
                ';' -> if (round == 0 && curly == 0 && square == 0) return i
            }
            if (round < 0 || curly < 0 || square < 0) return null
            i++
        }
        return limit.takeIf { round == 0 && curly == 0 && square == 0 }
    }

    private fun resolveExpression(
        code: String,
        start: Int,
        end: Int,
        assignments: Map<String, JsExpression>,
    ): JsExpression? {
        val identifier = identifierValue(code, start, end)
        return identifier?.let(assignments::get) ?: JsExpression(start, end)
    }

    private fun resolveScalar(
        code: String,
        start: Int,
        end: Int,
        assignments: Map<String, JsExpression>,
    ): String? {
        stringValue(code, start, end)?.let { return it }
        numberValue(code, start, end)?.let { return it }
        nullValue(code, start, end)?.let { return it }
        val identifier = identifierValue(code, start, end) ?: return null
        val expression = assignments[identifier] ?: return null
        return stringValue(code, expression.start, expression.end)
            ?: numberValue(code, expression.start, expression.end)
            ?: nullValue(code, expression.start, expression.end)
    }

    private fun numberValue(code: String, start: Int, end: Int): String? {
        val at = skipTrivia(code, start, end)
        var after = at
        while (after < end && code[after].isDigit()) after++
        if (after == at || skipTrivia(code, after, end) != end) return null
        return code.substring(at, after)
    }

    private fun nullValue(code: String, start: Int, end: Int): String? {
        val at = skipTrivia(code, start, end)
        val after = at + 4
        return "".takeIf { after <= end && code.regionMatches(at, "null", 0, 4) && skipTrivia(code, after, end) == end }
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
        return decodeJavascriptString(code.substring(at + 1, after - 1))
    }

    /** Decodes literal escapes only; arbitrary JavaScript expressions are never evaluated. */
    private fun decodeJavascriptString(raw: String): String? {
        val decoded = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val current = raw[i++]
            if (current != '\\') {
                decoded.append(current)
                continue
            }
            val escaped = raw.getOrNull(i++) ?: return null
            when (escaped) {
                '\\', '/', '\'', '"' -> decoded.append(escaped)
                'b' -> decoded.append('\b')
                'f' -> decoded.append('\u000C')
                'n' -> decoded.append('\n')
                'r' -> decoded.append('\r')
                't' -> decoded.append('\t')
                'v' -> decoded.append('\u000B')
                '\n' -> Unit
                '\r' -> if (raw.getOrNull(i) == '\n') i++
                'x' -> {
                    val hex = raw.substringOrNull(i, i + 2) ?: return null
                    decoded.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                    i += 2
                }
                'u' -> {
                    val hex = raw.substringOrNull(i, i + 4) ?: return null
                    decoded.append(hex.toIntOrNull(16)?.toChar() ?: return null)
                    i += 4
                }
                else -> return null
            }
        }
        return decoded.toString()
    }

    private fun String.substringOrNull(start: Int, end: Int): String? =
        takeIf { start >= 0 && end >= start && end <= length }?.substring(start, end)

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
