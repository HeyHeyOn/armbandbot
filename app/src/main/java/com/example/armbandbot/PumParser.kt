package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

object PumParser {
    private const val CARD_ENDPOINT = "https://gall.dcinside.com/ajax/pum_ajax/get_contents"

    fun hasListMarker(document: Document): Boolean = document.select(".font_blue009").any { marker ->
        marker.text() == "(펌)" && marker.tagName() !in setOf("button", "a") &&
            (marker.closest(".ub-word") != null || marker.closest(".gall_tit") != null)
    }

    fun parseDetail(document: Document, listMarker: Boolean = hasListMarker(document)): PumDetection {
        val loader = document.select(".write_div script")
            .asSequence()
            .mapNotNull(::parseLoader)
            .firstOrNull()
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
        var sawSourceLikeLink = false
        for (link in document.select("a[href]")) {
            val raw = link.absUrl("href").ifBlank { link.attr("href") }
            if (raw.contains("/board/view", ignoreCase = true)) sawSourceLikeLink = true
            val safe = DcinsidePostUrls.parseSafeCanonicalPostUrl(raw, outerPost) ?: continue
            return PumCard(PumCardStatus.RESOLVED, safe.key, safe.url)
        }
        return PumCard(if (sawSourceLikeLink) PumCardStatus.INVALID else PumCardStatus.MISSING)
    }

    private fun parseLoader(script: org.jsoup.nodes.Element): PumLoaderRequest? {
        val code = script.data().ifBlank { script.html() }
        if (!code.contains("pum_ajax", ignoreCase = true) || !code.contains("data", ignoreCase = true)) return null
        val endpointRaw = Regex("""(?:url\s*:\s*)['\"]([^'\"]*pum_ajax[^'\"]*)['\"]""", RegexOption.IGNORE_CASE)
            .find(code)?.groupValues?.get(1) ?: return null
        val dataBlock = Regex("""data\s*:\s*\{([^}]+)}""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(code)?.groupValues?.get(1) ?: return null
        val assignments = linkedMapOf<String, String>()
        Regex("""(?:var\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*['\"]([^'\"]+)['\"]""")
            .findAll(code).forEach { assignments[it.groupValues[1]] = it.groupValues[2] }
        val values = linkedMapOf<String, String>()
        Regex("""['\"]?([A-Za-z_][A-Za-z0-9_]*)['\"]?\s*:\s*(?:['\"]([^'\"]+)['\"]|([A-Za-z_][A-Za-z0-9_]*))""")
            .findAll(dataBlock).forEach { match ->
                val literal = match.groupValues[2]
                val variable = match.groupValues[3]
                val resolved = literal.ifBlank { assignments[variable].orEmpty() }
                if (resolved.isNotBlank()) values[match.groupValues[1]] = resolved
            }
        val id = values["gall_id"] ?: values["id"] ?: return null
        val no = values["gall_no"] ?: values["no"] ?: return null
        val type = (values["gall_type"] ?: values["gallery_type"] ?: values["_GALLTYPE_"] ?: "G").uppercase()
        if (type !in setOf("G", "M", "MI") || !id.matches(Regex("[A-Za-z0-9_-]+")) || !no.matches(Regex("[0-9]+"))) return null
        val endpoint = try { URI("https://gall.dcinside.com/").resolve(endpointRaw).toString() } catch (_: Exception) { return null }
        if (endpoint != CARD_ENDPOINT) return null
        return PumLoaderRequest(endpoint, PostKey(type, id, no), values.toMap())
    }
}
