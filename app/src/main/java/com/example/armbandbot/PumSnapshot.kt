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
    private const val COMPACT_SNAPSHOT_CSS = """
        :root{color-scheme:light dark;font-family:system-ui,-apple-system,sans-serif}
        body{margin:0;background:#f4f6fa;color:#1f2937}
        .armbandbot-snapshot{box-sizing:border-box;max-width:900px;margin:24px auto;padding:24px;background:#fff;border-radius:16px}
        .armbandbot-snapshot-header{padding-bottom:16px;border-bottom:1px solid #d8dee9}
        .armbandbot-snapshot-header h1{margin:0 0 10px;font-size:24px}
        .gall_writer,.gall_date,.gall_count{display:inline-block;margin-right:12px;color:#667085;font-size:13px}
        .write_div{padding:24px 0;line-height:1.65;overflow-wrap:anywhere}
        .write_div img,.write_div video{max-width:100%;height:auto}
        .armbandbot-pum-card{margin-top:20px;padding:18px;border:1px solid #b9c8dc;border-radius:12px;background:#edf4fc}
        .armbandbot-pum-heading,.armbandbot-pum-title{margin:0 0 8px}
        .armbandbot-pum-preview{white-space:pre-wrap}
        .view_comment{padding-top:20px;border-top:1px solid #d8dee9}
        .cmt_list,.reply_list{list-style:none;margin:0;padding:0}
        .cmt_list>li,.reply_list>li{padding:12px 0;border-bottom:1px solid #e5e7eb}
        .reply_list{margin-left:24px}
        @media(prefers-color-scheme:dark){body{background:#111827;color:#e5e7eb}.armbandbot-snapshot{background:#1f2937}.armbandbot-pum-card{background:#243449;border-color:#52657d}}
    """

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

    /**
     * Rebuilds a snapshot as a small standalone evidence document. DC's full page shell contains
     * dormant overlays, adverts and navigation whose CSS can expand into a wall of panels offline;
     * the Compose viewer only needs these stable metadata/body/comment selectors.
     */
    internal fun compactForViewer(snapshot: Document): Document {
        val compact = Document.createShell(snapshot.location())
        compact.outputSettings(snapshot.outputSettings().clone())
        compact.title(snapshot.selectFirst(".title_subject")?.text().orEmpty().ifBlank { "완장봇 스냅샷" })
        compact.head().appendElement("meta").attr("charset", "UTF-8")
        compact.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
        compact.head().appendElement("style").appendText(COMPACT_SNAPSHOT_CSS)

        val main = compact.body().appendElement("main").addClass("armbandbot-snapshot")
        val header = main.appendElement("header").addClass("armbandbot-snapshot-header")
        header.appendElement("h1").appendElement("span").addClass("title_subject")
            .text(snapshot.selectFirst(".title_subject")?.text().orEmpty())

        snapshot.selectFirst(".gallview_head .gall_writer, .gall_writer")?.let { source ->
            header.appendElement("div").addClass("gall_writer")
                .attr("data-nick", source.attr("data-nick"))
                .attr("data-uid", source.attr("data-uid"))
                .attr("data-ip", source.attr("data-ip"))
                .text(source.attr("data-nick").ifBlank { source.text() })
        }
        snapshot.selectFirst(".gall_date")?.let { source ->
            header.appendElement("span").addClass("gall_date")
                .attr("title", source.attr("title"))
                .text(source.text())
        }
        snapshot.selectFirst(".gall_count")?.let { source ->
            header.appendElement("span").addClass("gall_count").text(source.text())
        }

        val body = snapshot.selectFirst(".write_div")?.clone()
            ?: Element("div").addClass("write_div")
        val detachedCard = snapshot.selectFirst(".armbandbot-pum-card")
        if (body.select(".armbandbot-pum-card").isEmpty() && detachedCard != null) {
            body.appendChild(detachedCard.clone())
        }
        main.appendChild(body)
        snapshot.selectFirst(".view_comment")?.clone()?.let(main::appendChild)

        removeExecutableBehavior(compact)
        return compact
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

    private fun hasUnparsedPumLoader(document: Document): Boolean {
        if (PumParser.parseDetail(document).status != PumDetectionStatus.NOT_PUM) return true
        return document.select(".write_div script").any {
            val code = it.data().ifBlank { it.html() }
            code.replace("\\/", "/").contains("/ajax/pum_ajax/get_contents")
        }
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
