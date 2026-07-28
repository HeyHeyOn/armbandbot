package com.heyheyon.armbandbot

enum class PumFilterOrigin {
    OUTER_FILTER,
    PUM_BLOCK_ALL,
    PUM_SOURCE_FILTER,
    ALLOW,
}

data class PumFilterResult(val origin: PumFilterOrigin) {
    /** True for every blocking origin and false only for [PumFilterOrigin.ALLOW]. */
    val isBlocked: Boolean get() = origin != PumFilterOrigin.ALLOW
}

/** Explicit outcome of the structural PUM parse step; failures cannot masquerade as NOT_PUM. */
enum class PumStructuralState {
    NOT_PUM,
    DETECTED,
    PARSE_FAILED,
    SKIPPED,
    ;

    companion object {
        fun fromDetection(detection: PumDetection): PumStructuralState =
            if (detection.isPum) DETECTED else NOT_PUM
    }
}

data class PumStructuralParseResult(
    val detection: PumDetection?,
    val structuralState: PumStructuralState,
)

/** Converts parser exceptions into an explicit fail-safe state instead of conflating them with non-PUM. */
inline fun parsePumStructure(
    shouldInspect: Boolean,
    parse: () -> PumDetection,
    onFailure: (Exception) -> Unit = {},
): PumStructuralParseResult {
    if (!shouldInspect) return PumStructuralParseResult(null, PumStructuralState.SKIPPED)
    return try {
        val detection = parse()
        PumStructuralParseResult(detection, PumStructuralState.fromDetection(detection))
    } catch (error: Exception) {
        onFailure(error)
        PumStructuralParseResult(null, PumStructuralState.PARSE_FAILED)
    }
}

/** Source content has no moderation actor, so actor-only audience gates must not be consulted. */
object KeywordAudiencePolicy {
    inline fun shouldApply(contentOnly: Boolean, evaluateActorAudience: () -> Boolean): Boolean =
        contentOnly || evaluateActorAudience()
}

/** Source filter input that cannot represent a match without a resolved source. */
sealed class PumSourceFilterState {
    data object NotResolved : PumSourceFilterState()
    data class Resolved(val blocked: Boolean) : PumSourceFilterState()
}

data class PumRuntimeRoute(
    val targetPostKey: PostKey,
    val actionOverridePrefix: String?,
    val notificationType: String?,
    val reason: String?,
)

/** Pure bridge from origin policy to runtime action/reason/notification routing. */
object PumRuntimeRouting {
    const val AI_ACTION_OVERRIDE_PREFIX = "ai"

    fun route(
        origin: PumFilterOrigin,
        targetPostKey: PostKey,
        outerFilterSource: String?,
        outerReason: String?,
        sourceReason: String? = null,
        sourceFilterSource: String? = null,
    ): PumRuntimeRoute = when (origin) {
        PumFilterOrigin.OUTER_FILTER -> PumRuntimeRoute(
            targetPostKey = targetPostKey,
            actionOverridePrefix = actionOverridePrefixFor(outerFilterSource),
            notificationType = notificationTypeFor(outerFilterSource),
            reason = outerReason,
        )
        PumFilterOrigin.PUM_BLOCK_ALL -> PumRuntimeRoute(
            targetPostKey = targetPostKey,
            actionOverridePrefix = "pum",
            notificationType = "keyword",
            reason = "펌 게시글 전체 차단",
        )
        PumFilterOrigin.PUM_SOURCE_FILTER -> PumRuntimeRoute(
            targetPostKey = targetPostKey,
            actionOverridePrefix = actionOverridePrefixFor(sourceFilterSource),
            notificationType = notificationTypeFor(sourceFilterSource),
            reason = sourceReason ?: "필터 규칙 감지",
        )
        PumFilterOrigin.ALLOW -> PumRuntimeRoute(targetPostKey, null, null, null)
    }

    fun actionPrefix(winner: ModerationWinner, localRoute: PumRuntimeRoute): String? = when (winner) {
        ModerationWinner.OUTER_FILTER, ModerationWinner.PUM -> localRoute.actionOverridePrefix
        ModerationWinner.AI -> AI_ACTION_OVERRIDE_PREFIX
        ModerationWinner.ALLOW -> null
    }

    private fun notificationTypeFor(filterSource: String?): String = when (filterSource) {
        "user", "nickname", "yudong", "kkang", "url", "image", "voice", "spam", "ai" -> filterSource
        "dccon" -> "image"
        "special_char" -> "spam"
        else -> "keyword"
    }

    private fun actionOverridePrefixFor(filterSource: String?): String? = filterSource?.takeIf {
        it in setOf(
            "keyword", "user", "nickname", "yudong", "kkang", "overseas_ip",
            "url", "image", "dccon", "voice", "spam", "pum", "ai",
        )
    }
}

/** Snapshot decoration retains mandatory source inspection even in block-all mode. */
object PumSnapshotSourcePolicy {
    inline fun <T> resolve(
        @Suppress("UNUSED_PARAMETER") blockAllActive: Boolean,
        resolver: () -> T?,
    ): T? = resolver()
}

enum class AiStageOutcome {
    BLOCK,
    ALLOW,
    ERROR,
    SKIPPED,
}

enum class ModerationWinner {
    OUTER_FILTER,
    PUM,
    AI,
    ALLOW,
}

/** Pure winner policy: AI is considered only after all local/PUM filters allow the post. */
object ModerationWinnerPolicy {
    fun decide(localOrigin: PumFilterOrigin, aiOutcome: AiStageOutcome): ModerationWinner =
        when (localOrigin) {
            PumFilterOrigin.OUTER_FILTER -> ModerationWinner.OUTER_FILTER
            PumFilterOrigin.PUM_BLOCK_ALL,
            PumFilterOrigin.PUM_SOURCE_FILTER,
            -> ModerationWinner.PUM
            PumFilterOrigin.ALLOW -> if (aiOutcome == AiStageOutcome.BLOCK) {
                ModerationWinner.AI
            } else {
                ModerationWinner.ALLOW
            }
        }
}

/** A protected author is exempt from every AI post-moderation path. */
object PostAiStagePolicy {
    fun shouldRun(
        aiEnabled: Boolean,
        localOrigin: PumFilterOrigin,
        whitelisted: Boolean,
    ): Boolean = aiEnabled && !whitelisted &&
        ModerationWinnerPolicy.decide(localOrigin, AiStageOutcome.SKIPPED) == ModerationWinner.ALLOW
}

/** Pure priority policy; callers remain responsible for evaluating the two origin-specific inputs. */
object PumFilterPolicy {
    fun decide(
        @Suppress("UNUSED_PARAMETER")
        masterEnabled: Boolean,
        structuralState: PumStructuralState,
        blockAllPumPosts: Boolean,
        outerBlocked: Boolean,
        sourceFilter: PumSourceFilterState,
    ): PumFilterResult {
        if (outerBlocked) return PumFilterResult(PumFilterOrigin.OUTER_FILTER)
        if (structuralState != PumStructuralState.DETECTED) {
            return PumFilterResult(PumFilterOrigin.ALLOW)
        }
        if (blockAllPumPosts) return PumFilterResult(PumFilterOrigin.PUM_BLOCK_ALL)
        if (sourceFilter is PumSourceFilterState.Resolved && sourceFilter.blocked) {
            return PumFilterResult(PumFilterOrigin.PUM_SOURCE_FILTER)
        }
        return PumFilterResult(PumFilterOrigin.ALLOW)
    }
}
