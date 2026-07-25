package com.heyheyon.armbandbot

import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

data class SnapshotPumPreview(
    val status: PumSourceStatus,
    val galleryLabel: String = "",
    val sourceKey: PostKey? = null,
    val sourceUrl: String? = null,
    val title: String = "",
    val author: String = "",
    val checkedAt: String? = null,
    val contentHash: String? = null,
    val previewText: String = "",
    val thumbnailUrl: String? = null,
    val bodyElements: List<BodyElement> = emptyList(),
)

internal fun parseSnapshotPumPreview(card: Element?): SnapshotPumPreview? {
    card ?: return null
    val status = runCatching {
        PumSourceStatus.valueOf(card.attr("data-status").trim().uppercase(Locale.ROOT))
    }.getOrNull() ?: return null
    val sourceKey = parsePumSourceKey(card.attr("data-source-key"))
    val sourceUrl = card.attr("data-source-url")
        .takeIf(String::isNotBlank)
        ?.let { DcinsidePostUrls.parseSafeCanonicalPostUrl(it, null) }
        ?.takeIf { sourceKey != null && it.key == sourceKey }
        ?.url
    val bodyElements = if (status == PumSourceStatus.RESOLVED) {
        SnapshotBodyParser.parseChildren(card.selectFirst(".armbandbot-pum-body"), includeDirectText = true)
    } else {
        emptyList()
    }
    return SnapshotPumPreview(
        status = status,
        galleryLabel = card.selectFirst(".armbandbot-pum-gallery")?.text().orEmpty(),
        sourceKey = sourceKey,
        sourceUrl = sourceUrl,
        title = card.selectFirst(".armbandbot-pum-title")?.text().orEmpty(),
        author = card.selectFirst(".armbandbot-pum-author")?.text().orEmpty(),
        checkedAt = card.attr("data-checked-at").trim().takeIf(String::isNotEmpty),
        contentHash = card.attr("data-content-hash").trim().takeIf(String::isNotEmpty),
        previewText = card.selectFirst(".armbandbot-pum-preview")?.text().orEmpty(),
        thumbnailUrl = bodyElements.firstNotNullOfOrNull { element ->
            when (element) {
                is BodyElement.ImageElement -> element.url
                is BodyElement.DcconRowElement -> element.urls.firstOrNull()
                is BodyElement.TextElement -> null
            }
        },
        bodyElements = bodyElements,
    )
}

internal fun pumStatusLabel(status: PumSourceStatus): String = when (status) {
    PumSourceStatus.RESOLVED -> "원문 확인 완료"
    PumSourceStatus.MISSING -> "원문을 찾을 수 없음"
    PumSourceStatus.TEMPORARY_FAILURE -> "일시적인 불러오기 실패"
    PumSourceStatus.INVALID_SOURCE -> "올바르지 않은 원문"
    PumSourceStatus.UNSUPPORTED_SOURCE -> "지원하지 않는 원문"
}

internal fun pumImageReferer(preview: SnapshotPumPreview): String =
    preview.sourceUrl
        ?: preview.sourceKey?.let(DcinsidePostUrls::canonicalDetailUrl)
        ?: "https://gall.dcinside.com/"

private fun parsePumSourceKey(raw: String): PostKey? {
    val parts = raw.trim().split('/')
    if (parts.size != 3 || !parts[0].matches(Regex("G|M|MI")) ||
        !parts[1].matches(Regex("[A-Za-z0-9_-]+")) || !parts[2].matches(Regex("[0-9]+"))) return null
    return runCatching { PostKey(parts[0], parts[1], parts[2]) }.getOrNull()
}

internal object SnapshotBodyParser {
    fun parseChildren(root: Element?, includeDirectText: Boolean = false): List<BodyElement> = buildList {
        if (includeDirectText) {
            root?.childNodes()?.forEach { node ->
                when (node) {
                    is Element -> addChild(node)
                    is org.jsoup.nodes.TextNode -> node.text().trim().takeIf(String::isNotEmpty)
                        ?.let { add(BodyElement.TextElement(it)) }
                }
            }
        } else {
            root?.children()?.forEach { child -> addChild(child) }
        }
    }

    private fun MutableList<BodyElement>.addChild(child: Element) {
        if (isVoice(child)) {
            add(BodyElement.TextElement("[보이스리플]"))
            return
        }
        val dccons = DcconFilter.extractDcconRefsForDisplay(child.outerHtml())
        val images = child.select("img")
        when {
            dccons.isNotEmpty() -> {
                val urls = dccons.map { DcconFilter.buildImageUrl(it.token) }
                    .mapNotNull(::safeSnapshotMediaUrl)
                if (urls.size == 1) add(BodyElement.ImageElement(urls.first(), isDccon = true))
                else if (urls.isNotEmpty()) add(BodyElement.DcconRowElement(urls))
            }
            images.isNotEmpty() -> images.forEach { image ->
                val raw = image.attr("src").ifEmpty { image.attr("data-original") }.ifEmpty { image.attr("data-src") }
                if (raw.contains("dccon.php", ignoreCase = true)) {
                    DcconFilter.normalizeBlacklistEntry(raw)
                        ?.let(DcconFilter::buildImageUrl)
                        ?.let(::safeSnapshotMediaUrl)
                        ?.let { add(BodyElement.ImageElement(it, isDccon = true)) }
                } else {
                    safeSnapshotMediaUrl(raw)?.let { add(BodyElement.ImageElement(it)) }
                }
            }
            else -> {
                val rawHtml = child.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "⏎")
                val text = org.jsoup.Jsoup.parseBodyFragment(rawHtml).body()?.text()
                    ?.replace("⏎", "\n")
                    ?.replace(Regex("\n[ \\t]+"), "\n")
                    ?: child.text()
                if (text.isNotBlank()) add(BodyElement.TextElement(text))
                else add(BodyElement.TextElement(""))
            }
        }
    }

    private fun isVoice(element: Element): Boolean =
        element.hasClass("vr_player") || element.hasClass("vr_player_tag") ||
            element.hasClass("voice_wrap") || element.hasClass("armbandbot-pum-voice") ||
            element.select(".vr_player, .vr_player_tag, div.voice_wrap, .armbandbot-pum-voice, iframe[src*=voice/player]").isNotEmpty() ||
            element.html().contains("voice/player")
}

internal fun safeSnapshotMediaUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    if (uri.userInfo != null || uri.host.isNullOrBlank() ||
        (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true))) return null
    return uri.toASCIIString()
}
