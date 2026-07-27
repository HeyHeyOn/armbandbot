package com.heyheyon.armbandbot

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** One independently evaluated moderation input. Collection values are normalized within this input. */
data class PumModerationInput(
    val text: String,
    val imageAlts: List<String> = emptyList(),
    val rawHtml: String = "",
    val mediaSources: List<String> = emptyList(),
)

/**
 * Typed source state for moderation. Construction is restricted so a resolved state always has its
 * source-only input and an unresolved state can never expose one.
 */
sealed class PumModerationSourceState {
    data object NotRequested : PumModerationSourceState()

    class Unresolved private constructor(val resolution: PumResolution) : PumModerationSourceState() {
        companion object {
            internal fun from(resolution: PumResolution): Unresolved {
                require(resolution.status != PumSourceStatus.RESOLVED)
                return Unresolved(resolution)
            }
        }
    }

    class Resolved private constructor(
        val resolution: PumResolution,
        val content: PumModerationInput,
    ) : PumModerationSourceState() {
        companion object {
            internal fun from(resolution: PumResolution, content: PumModerationInput): Resolved {
                require(resolution.status == PumSourceStatus.RESOLVED)
                return Resolved(resolution, content)
            }
        }
    }
}

/**
 * Moderation-only view of a repost and an optional resolved PUM source.
 *
 * [targetPostKey] is immutable and always identifies the repost. Origin-aware local filtering must
 * evaluate [outerOriginal] and [resolvedSourceOnly] separately. [moderationContent] and the compose
 * helpers are legacy combined projections retained only until BotService is migrated in Task 4.
 */
class PumModerationContent private constructor(
    val targetPostKey: PostKey,
    val moderationContent: String,
    val aiBody: String,
    val outerOriginal: PumModerationInput,
    val sourceState: PumModerationSourceState,
) {
    /** Legacy ordinary-content construction; cannot fabricate a source state. */
    constructor(targetPostKey: PostKey, moderationContent: String) : this(
        targetPostKey = targetPostKey,
        moderationContent = moderationContent,
        aiBody = moderationContent,
        outerOriginal = PumModerationInput(moderationContent),
        sourceState = PumModerationSourceState.NotRequested,
    )

    val sourceResolution: PumResolution?
        get() = when (val state = sourceState) {
            PumModerationSourceState.NotRequested -> null
            is PumModerationSourceState.Unresolved -> state.resolution
            is PumModerationSourceState.Resolved -> state.resolution
        }

    val resolvedSourceOnly: PumModerationInput?
        get() = (sourceState as? PumModerationSourceState.Resolved)?.content

    val hasResolvedSource: Boolean
        get() = sourceState is PumModerationSourceState.Resolved

    /**
     * Legacy combined helper for BotService. New origin-aware filtering must read
     * [outerOriginal].[PumModerationInput.imageAlts] and [resolvedSourceOnly] separately.
     */
    @Deprecated("Legacy combined input; evaluate outerOriginal and resolvedSourceOnly separately")
    fun composeImageAlts(repostImageAlts: List<String>): List<String> =
        normalizeValues(repostImageAlts + resolvedSourceOnly?.imageAlts.orEmpty())

    /**
     * Legacy combined helper for BotService. New origin-aware filtering must read each origin's
     * [PumModerationInput.rawHtml] separately.
     */
    @Deprecated("Legacy combined input; evaluate outerOriginal and resolvedSourceOnly separately")
    fun composeRawHtml(repostRawHtml: String): String {
        val sourceHtml = resolvedSourceOnly?.rawHtml.orEmpty()
        return when {
            sourceHtml.isBlank() || repostRawHtml.contains(sourceHtml) -> repostRawHtml
            repostRawHtml.isBlank() -> sourceHtml
            else -> "$repostRawHtml\n$sourceHtml"
        }
    }

    /**
     * Legacy combined helper for BotService/AI. New origin-aware filtering must read
     * [outerOriginal].[PumModerationInput.mediaSources] and [resolvedSourceOnly] separately.
     */
    @Deprecated("Legacy combined input; evaluate outerOriginal and resolvedSourceOnly separately")
    fun composeMediaSources(repostMediaSources: List<String>): List<String> =
        normalizeValues(repostMediaSources + resolvedSourceOnly?.mediaSources.orEmpty())

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
            originalImageAlts: List<String> = emptyList(),
            originalRawHtml: String = "",
            originalMediaSources: List<String> = emptyList(),
            resolveSource: () -> PumResolution,
        ): PumModerationContent {
            val outer = PumModerationInput(
                text = originalContent,
                imageAlts = normalizeValues(originalImageAlts),
                rawHtml = originalRawHtml,
                mediaSources = normalizeValues(originalMediaSources),
            )
            if (!enabled || !detection.isPum) {
                return PumModerationContent(
                    targetPostKey = originalPostKey,
                    moderationContent = originalContent,
                    aiBody = originalContent,
                    outerOriginal = outer,
                    sourceState = PumModerationSourceState.NotRequested,
                )
            }

            val resolution = resolveSource()
            if (resolution.status != PumSourceStatus.RESOLVED) {
                return PumModerationContent(
                    targetPostKey = originalPostKey,
                    moderationContent = originalContent,
                    aiBody = originalContent,
                    outerOriginal = outer,
                    sourceState = PumModerationSourceState.Unresolved.from(resolution),
                )
            }

            val normalizedImageAlts = normalizeValues(resolution.imageAlts)
            val normalizedMediaSources = normalizeValues(resolution.mediaSources)
            // Source rules receive a separate plain-text input. Include sanitized link/media URLs
            // as well as visible source fields so URL, DCCon and voice rules retain their inputs.
            val sourceText = normalizeValues(buildList {
                add(resolution.title)
                add(resolution.bodyText)
                addAll(normalizedImageAlts)
                resolution.sourceUrl?.let(::add)
                addAll(Jsoup.parseBodyFragment(resolution.sanitizedHtml)
                    .select("[href]")
                    .map { it.attr("href") })
                addAll(normalizedMediaSources)
            }).joinToString("\n")
            val sourceOnly = PumModerationInput(
                text = sourceText,
                imageAlts = normalizedImageAlts,
                rawHtml = resolution.sanitizedHtml,
                mediaSources = normalizedMediaSources,
            )
            val legacyCombinedText = when {
                sourceText.isEmpty() -> originalContent
                originalContent.isEmpty() -> sourceText
                else -> "$originalContent\n$sourceText"
            }

            // JSONObject/JSONArray perform the escaping; typed fields cannot be confused by
            // delimiter text or JSON-shaped values embedded in either side.
            val sourceJson = JSONObject()
                .put("title", resolution.title)
                .put("bodyText", resolution.bodyText)
                .put("imageAlts", JSONArray(resolution.imageAlts))
            val aiJson = JSONObject()
                .put("repostText", originalContent)
                .put("pumSource", sourceJson)
                .toString()
            return PumModerationContent(
                targetPostKey = originalPostKey,
                moderationContent = legacyCombinedText,
                aiBody = aiJson,
                outerOriginal = outer,
                sourceState = PumModerationSourceState.Resolved.from(resolution, sourceOnly),
            )
        }

        private fun normalizeValues(values: List<String>): List<String> =
            values.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
    }
}
