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
        removeGaejukDecoration(document)

        val controlClasses = listOf(
            "btn_recom_up", "btn_recom_down", "btn_silbechu", "btn_cloned",
            "btn_snsmore", "btn_snscrap", "btn_report",
        )
        val authoritativeBox = document.selectFirst(
            "#container article > .view_content_wrap > .gallview_contents > .positionr > .btn_recommend_box"
        )
        val retainedButtons = authoritativeBox?.let { box ->
            val candidates = box.select("button").filter { button ->
                controlClasses.count(button::hasClass) == 1
            }
            controlClasses.mapNotNull { controlClass ->
                candidates.firstOrNull { it.hasClass(controlClass) }
            }.takeIf { it.size == controlClasses.size }?.toSet()
        }.orEmpty()

        document.select(".btn_recommend_box")
            .filterNot { it === authoritativeBox && retainedButtons.size == controlClasses.size }
            .forEach(Element::remove)
        document.select("button").filterNot(retainedButtons::contains).forEach(Element::remove)
        retainedButtons.forEach(::sanitizeButtonDescendants)

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
            "script, base, meta[http-equiv=refresh], form, iframe, object, embed, input, " +
                "textarea, select, option, frame, canvas, template, svg, math"
        ).remove()

        val inertButtonAttributes = setOf(
            "action", "method", "enctype", "target", "popover", "disabled",
            "form", "formaction", "formmethod", "formenctype", "formtarget", "name", "value",
            "autofocus", "popovertarget", "popovertargetaction", "command", "commandfor",
            "formnovalidate", "interestfor", "interesttarget"
        )
        val alwaysRemove = setOf(
            "srcdoc", "srcset", "cite", "background", "xlink:href", "action", "formaction",
            "data", "ping", "manifest", "usemap", "codebase", "archive"
        )
        val validatedUrls = setOf("href", "src", "poster", "data-original", "data-src")
        document.allElements.forEach { element ->
            if (element.tagName() == "button") element.attr("type", "button")
            element.attributes().asList().toList().forEach { attribute ->
                val name = attribute.key.lowercase(Locale.ROOT)
                when {
                    name.startsWith("on") || name in alwaysRemove ||
                        (element.tagName() == "button" && name in inertButtonAttributes) -> element.removeAttr(attribute.key)
                    name == "style" && !isSafeInlineStyle(attribute.value) -> element.removeAttr(attribute.key)
                    name in validatedUrls -> {
                        val safe = safeUrl(attribute.value, allowFragment = name == "href")
                        if (safe == null) element.removeAttr(attribute.key) else element.attr(attribute.key, safe)
                    }
                }
            }
        }
    }

    private fun sanitizeButtonDescendants(button: Element) {
        button.select(
            "script, base, meta, link, style, iframe, frame, object, embed, img, picture, source, " +
                "video, audio, track, canvas, template, svg, math"
        ).remove()

        button.getAllElements().drop(1).asReversed().forEach { descendant ->
            if (descendant.tagName() != "span" && descendant.tagName() != "em") descendant.unwrap()
        }

        val unsafeDescendantAttributes = setOf(
            "href", "src", "srcset", "poster", "data-original", "data-src", "srcdoc", "cite",
            "background", "xlink:href", "action", "formaction", "data", "ping", "manifest",
            "usemap", "codebase", "archive", "form", "tabindex", "contenteditable", "draggable",
            "autofocus", "popover", "popovertarget", "popovertargetaction", "command", "commandfor",
            "interestfor", "interesttarget",
        )
        button.getAllElements().drop(1).forEach { descendant ->
            descendant.attributes().asList().toList().forEach { attribute ->
                val name = attribute.key.lowercase(Locale.ROOT)
                if (name.startsWith("on") || name in unsafeDescendantAttributes) {
                    descendant.removeAttr(attribute.key)
                }
            }
        }
    }

    internal fun removeGaejukDecoration(document: Document) {
        document.select("#gaejukimg, style#styleGaejuki").remove()
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
