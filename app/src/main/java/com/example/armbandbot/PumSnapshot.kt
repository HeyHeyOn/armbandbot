package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds a self-contained, inert PUM source card without ever changing the live detail document. */
object PumSnapshot {
    fun withStaticCard(
        liveDocument: Document,
        resolution: PumResolution?,
        checkedAt: String = currentCheckedAt(),
    ): Document {
        val snapshot = liveDocument.clone()
        if (resolution == null) return snapshot

        // In a PUM snapshot the remote loader must not survive even if the caller has not run the
        // general snapshot cleanup yet.
        removeExecutableBehavior(snapshot)
        snapshot.select(".armbandbot-pum-card").remove()
        val card = buildCard(resolution, checkedAt)
        val body = snapshot.selectFirst(".write_div") ?: snapshot.body()
        body?.appendChild(card)
        return snapshot
    }

    internal fun removeExecutableBehavior(document: Document) {
        document.select("iframe").toList().forEach { iframe ->
            val safe = safeHttpsUrl(iframe.absUrl("src").ifBlank { iframe.attr("src") })
            if (safe != null && runCatching { URI(safe).path.orEmpty().contains("/voice/player") }.getOrDefault(false)) {
                iframe.replaceWith(Element("span").addClass("armbandbot-pum-voice").text("보이스 원문 (정적 표시)"))
            } else {
                iframe.remove()
            }
        }
        document.select("script, form, object, embed, input, button, textarea, select, frame").remove()
        document.allElements.forEach { element ->
            element.attributes().asList().toList().forEach { attribute ->
                if (attribute.key.lowercase(Locale.ROOT).startsWith("on")) element.removeAttr(attribute.key)
            }
            listOf("href", "src", "poster", "action", "formaction").forEach { attr ->
                if (element.hasAttr(attr)) {
                    val raw = element.absUrl(attr).ifBlank { element.attr(attr) }
                    val safe = safeHttpsUrl(raw)
                    if (safe == null) element.removeAttr(attr) else element.attr(attr, safe)
                }
            }
        }
    }

    private fun buildCard(resolution: PumResolution, checkedAt: String): Element {
        val card = Element("section").addClass("armbandbot-pum-card")
        card.attr("data-status", resolution.status.name)
        safeMetadata(checkedAt)?.let { card.attr("data-checked-at", it) }
        resolution.sourceKey?.let { card.attr("data-source-key", "${it.gallType}/${it.gallId}/${it.postNo}") }
        resolution.contentHash?.takeIf { it.matches(Regex("[A-Fa-f0-9]{6,128}")) }
            ?.let { card.attr("data-content-hash", it.lowercase(Locale.ROOT)) }
        val sourceUrl = safeSourceUrl(resolution)
        sourceUrl?.let { card.attr("data-source-url", it) }

        card.appendElement("h2").addClass("armbandbot-pum-heading").text("펌 원문")
        resolution.sourceKey?.let { key ->
            card.appendElement("div").addClass("armbandbot-pum-gallery").text("${key.gallId} 갤러리")
        }
        if (resolution.status != PumSourceStatus.RESOLVED) {
            card.appendElement("div").addClass("armbandbot-pum-warning").text("원문을 불러오지 못했습니다")
            return card
        }

        resolution.title.trim().takeIf(String::isNotEmpty)
            ?.let { card.appendElement("h3").addClass("armbandbot-pum-title").text(it) }
        resolution.author.trim().takeIf(String::isNotEmpty)
            ?.let { card.appendElement("div").addClass("armbandbot-pum-author").text(it) }
        resolution.bodyText.trim().takeIf(String::isNotEmpty)
            ?.let { card.appendElement("p").addClass("armbandbot-pum-preview").text(it) }
        val sourceBody = sanitizeSourceBody(resolution.sanitizedHtml)
        sourceBody.addClass("armbandbot-pum-body")
        card.appendChild(sourceBody)
        sourceUrl?.let { url ->
            card.appendElement("a")
                .addClass("armbandbot-pum-source-link")
                .attr("href", url)
                .attr("rel", "noreferrer noopener")
                .text("원문 링크")
        }
        return card
    }

    private fun sanitizeSourceBody(html: String): Element {
        val parsed = Jsoup.parseBodyFragment(html, "https://gall.dcinside.com/")
        val body = parsed.body()
        removeComments(body)

        // Preserve voice replies as inert evidence before removing every browsing context.
        body.select("iframe").toList().forEach { iframe ->
            val safe = safeHttpsUrl(iframe.absUrl("src").ifBlank { iframe.attr("src") })
            if (safe != null && URI(safe).path.orEmpty().contains("/voice/player")) {
                iframe.replaceWith(Element("span").addClass("armbandbot-pum-voice").text("보이스 원문 (정적 표시)"))
            } else {
                iframe.remove()
            }
        }
        body.select("script, style, meta, link, base, form, button, input, textarea, select, option, frame, object, embed, canvas, template, noscript, svg, math").remove()

        body.allElements.forEach { element ->
            element.attributes().asList().toList().forEach { attribute ->
                val name = attribute.key.lowercase(Locale.ROOT)
                if (name.startsWith("on") || name in setOf("style", "srcdoc", "action", "formaction")) {
                    element.removeAttr(attribute.key)
                }
            }
            listOf("href", "src", "poster").forEach { attr ->
                if (element.hasAttr(attr)) {
                    val raw = element.absUrl(attr).ifBlank { element.attr(attr) }
                    val safe = safeHttpsUrl(raw)
                    if (safe == null) element.removeAttr(attr) else element.attr(attr, safe)
                }
            }
        }
        return Element("div").also { container -> body.childNodes().toList().forEach(container::appendChild) }
    }

    private fun safeSourceUrl(resolution: PumResolution): String? {
        val key = resolution.sourceKey ?: return null
        return resolution.sourceUrl
            ?.let { DcinsidePostUrls.parseSafeCanonicalPostUrl(it, null) }
            ?.takeIf { it.key == key }
            ?.url
    }

    private fun safeHttpsUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val normalized = if (raw.startsWith("//")) "https:$raw" else raw
        val uri = try { URI(normalized) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toASCIIString()
    }

    private fun safeMetadata(value: String): String? = value.trim().takeIf { it.isNotEmpty() && it.length <= 128 }

    private fun currentCheckedAt(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun removeComments(node: Node) {
        node.childNodes().toList().forEach { child ->
            if (child is Comment) child.remove() else removeComments(child)
        }
    }
}
