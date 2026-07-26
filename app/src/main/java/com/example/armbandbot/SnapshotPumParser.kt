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
    val bodyTruncated: Boolean = false,
)

internal const val SNAPSHOT_PUM_MAX_BODY_ELEMENTS = 64
internal const val SNAPSHOT_PUM_MAX_MEDIA_URLS = 24
internal const val SNAPSHOT_PUM_MAX_TEXT_CHARS = 20_000
private const val SNAPSHOT_PUM_MAX_SOURCE_NODES = 256

internal data class BoundedSnapshotBody(
    val elements: List<BodyElement>,
    val truncated: Boolean,
)

internal fun parseSnapshotPumPreview(card: Element?): SnapshotPumPreview? {
    card ?: return null
    if (card.id() == "pum_container" && card.hasClass("cloned_card")) {
        return parseNativeDcPumPreview(card)
    }
    val status = runCatching {
        PumSourceStatus.valueOf(card.attr("data-status").trim().uppercase(Locale.ROOT))
    }.getOrNull() ?: return null
    val sourceKey = parsePumSourceKey(card.attr("data-source-key"))
    val sourceUrl = card.attr("data-source-url")
        .takeIf(String::isNotBlank)
        ?.let { DcinsidePostUrls.parseSafeCanonicalPostUrl(it, null) }
        ?.takeIf { sourceKey != null && it.key == sourceKey }
        ?.url
    val boundedBody = if (status == PumSourceStatus.RESOLVED) {
        SnapshotBodyParser.parsePumChildren(card.selectFirst(".armbandbot-pum-body"))
    } else {
        BoundedSnapshotBody(emptyList(), truncated = false)
    }
    val bodyElements = boundedBody.elements
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
        bodyTruncated = boundedBody.truncated,
    )
}

private fun parseNativeDcPumPreview(card: Element): SnapshotPumPreview? {
    val sourceAnchor = card.selectFirst(".cloned_card_body > a[href]") ?: return null
    val rawHref = sourceAnchor.attr("href").trim()
    val absoluteHref = when {
        rawHref.startsWith("/") && !rawHref.startsWith("//") -> "https://gall.dcinside.com$rawHref"
        rawHref.startsWith("//") -> "https:$rawHref"
        else -> rawHref
    }
    val canonical = DcinsidePostUrls.parseSafeCanonicalPostUrl(absoluteHref, null) ?: return null

    val galleryLabel = card.selectFirst("header.gallview_head h3.title")?.clone()?.also {
        it.select(".blind, .pagehead_titicon").remove()
    }?.text().orEmpty()
    val title = sourceAnchor.selectFirst(".cloned_subject h4")?.clone()?.also {
        it.select("span").remove()
    }?.text().orEmpty()
    val writer = card.selectFirst("header.gallview_head .gall_writer")
    val nickname = writer?.selectFirst(".nickname em")?.text().orEmpty()
    val identity = writer?.selectFirst(".nickname .ip")?.text().orEmpty()
    val author = if (identity.isNotBlank()) "$nickname$identity" else nickname
    val previewText = sourceAnchor.children().firstOrNull { it.tagName() == "p" }?.text().orEmpty()
    val thumbnailUrl = sourceAnchor.selectFirst(".cloned_media img")
        ?.let { image -> image.attr("src").ifBlank { image.attr("data-original") }.ifBlank { image.attr("data-src") } }
        ?.let(::safeSnapshotMediaUrl)

    return SnapshotPumPreview(
        status = PumSourceStatus.RESOLVED,
        galleryLabel = galleryLabel,
        sourceKey = canonical.key,
        sourceUrl = canonical.url,
        title = title,
        author = author,
        previewText = previewText,
        thumbnailUrl = thumbnailUrl,
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

    fun parsePumChildren(root: Element?): BoundedSnapshotBody {
        if (root == null) return BoundedSnapshotBody(emptyList(), truncated = false)
        val elements = mutableListOf<BodyElement>()
        var mediaCount = 0
        var textChars = 0
        var truncated = false

        fun addText(raw: String) {
            if (elements.size >= SNAPSHOT_PUM_MAX_BODY_ELEMENTS) {
                truncated = true
                return
            }
            if (raw.isEmpty()) {
                elements += BodyElement.TextElement("")
                return
            }
            val remaining = SNAPSHOT_PUM_MAX_TEXT_CHARS - textChars
            if (remaining <= 0) {
                truncated = true
                return
            }
            val text = raw.take(remaining)
            elements += BodyElement.TextElement(text)
            textChars += text.length
            if (text.length != raw.length) truncated = true
        }

        fun addMedia(url: String, isDccon: Boolean) {
            if (elements.size >= SNAPSHOT_PUM_MAX_BODY_ELEMENTS || mediaCount >= SNAPSHOT_PUM_MAX_MEDIA_URLS) {
                truncated = true
                return
            }
            elements += BodyElement.ImageElement(url, isDccon)
            mediaCount++
        }

        fun addElement(child: Element) {
            if (elements.size >= SNAPSHOT_PUM_MAX_BODY_ELEMENTS) {
                truncated = true
                return
            }
            if (isVoice(child)) {
                addText("[보이스리플]")
                return
            }
            val dccons = DcconFilter.extractDcconRefsForDisplay(child.outerHtml())
            val images = child.select("img")
            when {
                dccons.isNotEmpty() -> {
                    val available = minOf(SNAPSHOT_PUM_MAX_MEDIA_URLS - mediaCount, dccons.size)
                    val urls = dccons.asSequence()
                        .map { DcconFilter.buildImageUrl(it.token) }
                        .mapNotNull(::safeSnapshotMediaUrl)
                        .take(available)
                        .toList()
                    if (urls.size < dccons.size) truncated = true
                    if (urls.isNotEmpty() && elements.size < SNAPSHOT_PUM_MAX_BODY_ELEMENTS) {
                        elements += if (urls.size == 1) BodyElement.ImageElement(urls.first(), isDccon = true)
                        else BodyElement.DcconRowElement(urls)
                        mediaCount += urls.size
                    } else if (urls.isNotEmpty()) {
                        truncated = true
                    }
                }
                images.isNotEmpty() -> {
                    for (image in images) {
                        if (mediaCount >= SNAPSHOT_PUM_MAX_MEDIA_URLS ||
                            elements.size >= SNAPSHOT_PUM_MAX_BODY_ELEMENTS) {
                            truncated = true
                            break
                        }
                        val raw = image.attr("src").ifEmpty { image.attr("data-original") }.ifEmpty { image.attr("data-src") }
                        val isDccon = raw.contains("dccon.php", ignoreCase = true)
                        val url = if (isDccon) {
                            DcconFilter.normalizeBlacklistEntry(raw)
                                ?.let(DcconFilter::buildImageUrl)
                                ?.let(::safeSnapshotMediaUrl)
                        } else {
                            safeSnapshotMediaUrl(raw)
                        }
                        url?.let { addMedia(it, isDccon) }
                    }
                }
                else -> {
                    val rawHtml = child.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "⏎")
                    val text = org.jsoup.Jsoup.parseBodyFragment(rawHtml).body()?.text()
                        ?.replace("⏎", "\n")
                        ?.replace(Regex("\n[ \\t]+"), "\n")
                        ?: child.text()
                    addText(text)
                }
            }
        }

        val nodes = root.childNodes()
        for ((index, node) in nodes.withIndex()) {
            if (index >= SNAPSHOT_PUM_MAX_SOURCE_NODES || elements.size >= SNAPSHOT_PUM_MAX_BODY_ELEMENTS) {
                truncated = true
                break
            }
            when (node) {
                is Element -> addElement(node)
                is org.jsoup.nodes.TextNode -> node.text().trim().takeIf(String::isNotEmpty)?.let(::addText)
            }
        }
        return BoundedSnapshotBody(elements, truncated)
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
    if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.port != -1 || uri.host.isNullOrBlank()) {
        return null
    }
    val host = uri.host.lowercase(Locale.ROOT)
    val approvedHost = Regex("(?:image|images|dcimg[0-9]+)\\.dcinside\\.(?:com|co\\.kr)")
    if (!approvedHost.matches(host)) return null
    return uri.toASCIIString()
}
