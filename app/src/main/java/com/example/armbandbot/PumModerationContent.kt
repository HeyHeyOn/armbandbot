package com.heyheyon.armbandbot

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
) {
    val hasResolvedSource: Boolean
        get() = sourceResolution?.status == PumSourceStatus.RESOLVED

    companion object {
        const val SOURCE_BEGIN = "<<<PUM_SOURCE_CONTENT>>>"
        const val SOURCE_END = "<<<END_PUM_SOURCE_CONTENT>>>"

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

            val sourceText = buildList {
                resolution.title.trim().takeIf(String::isNotEmpty)?.let { add("TITLE: $it") }
                resolution.bodyText.trim().takeIf(String::isNotEmpty)?.let { add("BODY: $it") }
                resolution.imageAlts
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .takeIf(List<String>::isNotEmpty)
                    ?.let { add("IMAGE_TEXT:\n${it.joinToString("\n")}") }
            }.joinToString("\n")

            val enriched = if (sourceText.isEmpty()) {
                originalContent
            } else {
                "$originalContent\n\n$SOURCE_BEGIN\n$sourceText\n$SOURCE_END"
            }
            return PumModerationContent(originalPostKey, enriched, resolution)
        }
    }
}
