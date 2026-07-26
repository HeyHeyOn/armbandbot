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

internal fun matchingPumResolution(
    targetKey: PostKey,
    currentKey: PostKey,
    currentResolution: PumResolution?,
): PumResolution? = currentResolution.takeIf { targetKey == currentKey }

/** Builds a self-contained, inert PUM source card without ever changing the live detail document. */
object PumSnapshot {
    fun withStaticCard(
        liveDocument: Document,
        resolution: PumResolution?,
        checkedAt: String = currentCheckedAt(),
    ): Document {
        val snapshot = liveDocument.clone()
        val effectiveResolution = resolution ?: if (hasUnparsedPumLoader(snapshot)) {
            PumResolution(PumSourceStatus.INVALID_SOURCE)
        } else {
            null
        }
        removeExecutableBehavior(snapshot)
        if (effectiveResolution == null) return snapshot

        snapshot.select(".armbandbot-pum-card").remove()
        val card = buildCard(effectiveResolution, checkedAt)
        val body = snapshot.selectFirst(".write_div") ?: snapshot.body()
        body?.appendChild(card)
        return snapshot
    }

    internal fun removeExecutableBehavior(document: Document) {
        sanitizeTree(document, preservePageStyles = true)
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

    internal fun sanitizeSourceBody(html: String): Element {
        val parsed = Jsoup.parseBodyFragment(html, "https://gall.dcinside.com/")
        val body = parsed.body()
        removeComments(body)
        sanitizeTree(body, preservePageStyles = false)
        return Element("div").also { container -> body.childNodes().toList().forEach(container::appendChild) }
    }

    private fun sanitizeTree(root: Element, preservePageStyles: Boolean) {
        // Preserve voice evidence as inert text before deleting every browsing context.
        root.select("iframe").toList().forEach { iframe ->
            val safe = safeHttpUrl(iframe.attr("src"))
            if (safe != null && runCatching { URI(safe).path.orEmpty().contains("/voice/player") }.getOrDefault(false)) {
                iframe.replaceWith(Element("span").addClass("armbandbot-pum-voice").text("보이스 원문 (정적 표시)"))
            } else {
                iframe.remove()
            }
        }
        if (preservePageStyles) {
            root.select("style").filter { it.parent()?.tagName() != "head" }.forEach(Element::remove)
            root.select("link").filterNot(::isSafeStylesheet).forEach(Element::remove)
        }
        val styleSelector = if (preservePageStyles) "" else ", style, link"
        root.select(
            "script, meta, base, form, iframe, object, embed, input, button, " +
                "textarea, select, option, frame, canvas, template, noscript, svg, math$styleSelector"
        ).remove()

        val alwaysRemove = setOf(
            "style", "srcdoc", "srcset", "cite", "background", "xlink:href", "action",
            "formaction", "data", "ping", "manifest", "usemap", "codebase", "archive"
        )
        val validatedUrls = setOf("href", "src", "poster", "data-original", "data-src")
        root.allElements.forEach { element ->
            element.attributes().asList().toList().forEach { attribute ->
                val name = attribute.key.lowercase(Locale.ROOT)
                when {
                    name.startsWith("on") || name in alwaysRemove -> element.removeAttr(attribute.key)
                    name in validatedUrls -> {
                        val raw = element.attr(attribute.key)
                        val safe = if (name == "href") safeLinkUrl(raw) else safeHttpUrl(raw)
                        if (safe == null) element.removeAttr(attribute.key) else element.attr(attribute.key, safe)
                    }
                }
            }
        }
    }

    private fun hasUnparsedPumLoader(document: Document): Boolean =
        document.select(".write_div script").any {
            val code = it.data().ifBlank { it.html() }
            code.contains("/ajax/pum_ajax/get_contents")
        }

    private fun isSafeStylesheet(link: Element): Boolean {
        if (!link.attr("rel").split(Regex("\\s+")).any { it.equals("stylesheet", true) }) return false
        val raw = link.absUrl("href").ifBlank { link.attr("href") }
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme.equals("https", true) && uri.port == -1 && uri.userInfo == null &&
            (host == "dcinside.com" || host.endsWith(".dcinside.com"))
    }

    private fun safeSourceUrl(resolution: PumResolution): String? {
        val key = resolution.sourceKey ?: return null
        return resolution.sourceUrl
            ?.let { DcinsidePostUrls.parseSafeCanonicalPostUrl(it, null) }
            ?.takeIf { it.key == key }
            ?.url
    }

    private fun safeLinkUrl(raw: String): String? {
        val anchor = raw.trim()
        if (anchor.matches(Regex("#[A-Za-z0-9_.:-]+"))) return anchor
        return safeHttpUrl(raw)
    }

    private fun safeHttpUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val normalized = if (raw.trim().startsWith("//")) "https:${raw.trim()}" else raw.trim()
        val uri = try { URI(normalized) } catch (_: Exception) { return null }
        if ((!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null) return null
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
