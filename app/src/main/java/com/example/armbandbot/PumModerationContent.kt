package com.heyheyon.armbandbot

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Moderation-only view of a repost and an optional resolved PUM source.
 *
 * [targetPostKey] is deliberately immutable and always identifies the repost. Source identity and
 * sanitized source metadata are retained only in [sourceResolution] for moderation and later
 * snapshot work.
 */
data class PumModerationContent(
    val targetPostKey: PostKey,
    val moderationContent: String,
    val sourceResolution: PumResolution? = null,
    val aiBody: String = moderationContent,
) {
    val hasResolvedSource: Boolean
        get() = sourceResolution?.status == PumSourceStatus.RESOLVED

    /** Adds source image descriptions to the dedicated image-alt rule input. */
    fun composeImageAlts(repostImageAlts: List<String>): List<String> =
        composeDistinct(repostImageAlts, resolvedSource()?.imageAlts.orEmpty())

    /** Adds sanitized source markup to URL, voice and DCCon rule input. */
    fun composeRawHtml(repostRawHtml: String): String {
        val sourceHtml = resolvedSource()?.sanitizedHtml.orEmpty()
        return when {
            sourceHtml.isBlank() || repostRawHtml.contains(sourceHtml) -> repostRawHtml
            repostRawHtml.isBlank() -> sourceHtml
            else -> "$repostRawHtml\n$sourceHtml"
        }
    }

    /** Adds resolved source media URLs to AI's dedicated media input. */
    fun composeMediaSources(repostMediaSources: List<String>): List<String> =
        composeDistinct(repostMediaSources, resolvedSource()?.mediaSources.orEmpty())

    private fun resolvedSource(): PumResolution? = sourceResolution?.takeIf {
        it.status == PumSourceStatus.RESOLVED
    }

    private fun composeDistinct(first: List<String>, second: List<String>): List<String> =
        LinkedHashSet<String>().apply {
            first.filterTo(this) { it.isNotBlank() }
            second.filterTo(this) { it.isNotBlank() }
        }.toList()

    companion object {
        // Kept only so delimiter-shaped adversarial input can be regression-tested. AI framing no
        // longer uses these tokens and therefore cannot be spoofed by source or repost text.
        const val LEGACY_SOURCE_BEGIN = "<<<PUM_SOURCE_CONTENT>>>"
        const val LEGACY_SOURCE_END = "<<<END_PUM_SOURCE_CONTENT>>>"

        /**
         * Resolves only structurally detected PUM details. Disabled and non-PUM paths do not invoke
         * [resolveSource], preserving legacy request behavior exactly.
         */
        fun resolve(
            enabled: Boolean,
            detection: PumDetection,
            originalPostKey: PostKey,
            originalContent: String,
            resolveSource: () -> PumResolution,
        ): PumModerationContent {
            if (!enabled || !detection.isPum) {
                return PumModerationContent(originalPostKey, originalContent)
            }

            val resolution = resolveSource()
            if (resolution.status != PumSourceStatus.RESOLVED) {
                return PumModerationContent(originalPostKey, originalContent, resolution)
            }

            // Local rules intentionally receive plain combined text. Include sanitized link/media
            // URLs as well as visible source fields so URL and text rules see the complete source.
            val sourceText = buildList {
                resolution.title.trim().takeIf(String::isNotEmpty)?.let(::add)
                resolution.bodyText.trim().takeIf(String::isNotEmpty)?.let(::add)
                resolution.imageAlts.map(String::trim).filter(String::isNotEmpty).let(::addAll)
                Jsoup.parseBodyFragment(resolution.sanitizedHtml)
                    .select("[href]")
                    .map { it.attr("href").trim() }
                    .filter(String::isNotEmpty)
                    .let(::addAll)
                resolution.mediaSources.map(String::trim).filter(String::isNotEmpty).let(::addAll)
            }.distinct().joinToString("\n")
            val enriched = if (sourceText.isEmpty()) originalContent else "$originalContent\n$sourceText"

            // JSONObject/JSONArray perform the escaping; the two typed fields cannot be confused by
            // delimiter text or JSON-shaped values embedded in either side.
            val sourceJson = JSONObject()
                .put("title", resolution.title)
                .put("bodyText", resolution.bodyText)
                .put("imageAlts", JSONArray(resolution.imageAlts))
            val aiJson = JSONObject()
                .put("repostText", originalContent)
                .put("pumSource", sourceJson)
                .toString()
            return PumModerationContent(originalPostKey, enriched, resolution, aiJson)
        }
    }
}
