package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

internal fun matchingPumResolution(
    targetKey: PostKey,
    currentKey: PostKey,
    currentResolution: PumResolution?,
): PumResolution? = currentResolution.takeIf { targetKey == currentKey }

/** Freezes DC's dynamic PUM card into an inert clone while preserving DC's visual DOM and CSS. */
object PumSnapshot {
    @Suppress("UNUSED_PARAMETER")
    fun withStaticCard(
        liveDocument: Document,
        resolution: PumResolution?,
        checkedAt: String = "",
    ): Document {
        val snapshot = liveDocument.clone()
        DcinsidePostUrls.parseSafeCanonicalPostUrl(snapshot.location(), null)?.url?.let { baseUrl ->
            snapshot.select("meta[name=armbandbot-base-url]").remove()
            snapshot.head()?.appendElement("meta")
                ?.attr("name", "armbandbot-base-url")
                ?.attr("content", baseUrl)
        }
        snapshot.select("#pum_container, .armbandbot-pum-card").remove()

        resolution?.dynamicCardHtml?.takeIf(String::isNotBlank)?.let { rawCardHtml ->
            val parsed = Jsoup.parseBodyFragment(rawCardHtml, snapshot.location())
            val container = Element("div")
                .attr("id", "pum_container")
                .addClass("cloned_card")
            parsed.body().childNodes().toList().forEach(container::appendChild)
            val host = snapshot.selectFirst("#pum_card")
                ?: snapshot.selectFirst("#container .write_div, .write_div")
                ?: snapshot.body()
            host?.appendChild(container)
        }

        removeExecutableBehavior(snapshot)
        return snapshot
    }

    internal fun removeExecutableBehavior(document: Document) {
        document.select("iframe").toList().forEach { iframe ->
            val source = safeUrl(iframe.attr("src"), allowFragment = false)
            if (source != null && runCatching { URI(source).path.orEmpty().contains("/voice/player") }.getOrDefault(false)) {
                iframe.replaceWith(Element("span").addClass("armbandbot-pum-voice").text("보이스 원문 (정적 표시)"))
            } else {
                iframe.remove()
            }
        }

        document.select("link").filterNot(::isSafeStylesheet).forEach(Element::remove)
        document.select("style").filter { it.parent()?.tagName() != "head" }.forEach(Element::remove)
        document.select(
            "script, base, meta[http-equiv=refresh], form, iframe, object, embed, input, button, " +
                "textarea, select, option, frame, canvas, template, svg, math"
        ).remove()

        val alwaysRemove = setOf(
            "srcdoc", "srcset", "cite", "background", "xlink:href", "action", "formaction",
            "data", "ping", "manifest", "usemap", "codebase", "archive"
        )
        val validatedUrls = setOf("href", "src", "poster", "data-original", "data-src")
        document.allElements.forEach { element ->
            element.attributes().asList().toList().forEach { attribute ->
                val name = attribute.key.lowercase(Locale.ROOT)
                when {
                    name.startsWith("on") || name in alwaysRemove -> element.removeAttr(attribute.key)
                    name == "style" && !isSafeInlineStyle(attribute.value) -> element.removeAttr(attribute.key)
                    name in validatedUrls -> {
                        val safe = safeUrl(attribute.value, allowFragment = name == "href")
                        if (safe == null) element.removeAttr(attribute.key) else element.attr(attribute.key, safe)
                    }
                }
            }
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

    private fun isSafeInlineStyle(raw: String): Boolean {
        val normalized = raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), "")
        return listOf("javascript:", "vbscript:", "data:", "expression(", "url(", "@import", "-moz-binding").none(normalized::contains)
    }

    private fun safeUrl(raw: String, allowFragment: Boolean): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (allowFragment && value.matches(Regex("#[A-Za-z0-9_.:-]+"))) return value
        if (value.startsWith("/") && !value.startsWith("//")) {
            return runCatching { URI(value) }.getOrNull()?.takeIf { it.scheme == null && it.host == null }?.toASCIIString()
        }
        val normalized = if (value.startsWith("//")) "https:$value" else value
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if ((!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null) return null
        return uri.toASCIIString()
    }
}
