package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PumFilterPolicyTest {
    @Test fun `source content keyword matching bypasses actor audience gates without actor lookup`() {
        var actorLookups = 0
        val allowed = KeywordAudiencePolicy.shouldApply(
            contentOnly = true,
            evaluateActorAudience = {
                actorLookups++
                false
            },
        )

        assertTrue(allowed)
        assertEquals(0, actorLookups)
    }

    @Test fun `ordinary keyword matching preserves actor audience semantics`() {
        var actorLookups = 0
        val denied = KeywordAudiencePolicy.shouldApply(contentOnly = false) {
            actorLookups++
            false
        }
        assertFalse(denied)
        assertEquals(1, actorLookups)
    }

    @Test fun `parse exceptions become explicit parse failure and invoke diagnostics`() {
        var reported: Exception? = null
        val result = parsePumStructure(shouldInspect = true, parse = {
            throw IllegalArgumentException("broken markup")
        }, onFailure = { reported = it })

        assertEquals(PumStructuralState.PARSE_FAILED, result.structuralState)
        assertEquals(null, result.detection)
        assertEquals("broken markup", reported?.message)
    }

    @Test(expected = OutOfMemoryError::class)
    fun `parser VM errors are not swallowed`() {
        parsePumStructure(shouldInspect = true, parse = {
            throw OutOfMemoryError("fatal parser error")
        })
    }

    @Test fun `skipped structural parsing does not invoke parser`() {
        var calls = 0
        val result = parsePumStructure(shouldInspect = false, parse = {
            calls++
            PumDetection(PumDetectionStatus.PUM_CONFIRMED)
        })
        assertEquals(PumStructuralState.SKIPPED, result.structuralState)
        assertEquals(0, calls)
    }

    @Test fun `parser outcomes map to explicit structural states`() {
        assertEquals(
            PumStructuralState.DETECTED,
            PumStructuralState.fromDetection(PumDetection(PumDetectionStatus.PUM_CONFIRMED)),
        )
        assertEquals(
            PumStructuralState.DETECTED,
            PumStructuralState.fromDetection(PumDetection(PumDetectionStatus.PUM_LOADER_ONLY)),
        )
        assertEquals(
            PumStructuralState.NOT_PUM,
            PumStructuralState.fromDetection(PumDetection(PumDetectionStatus.PUM_MARKER_ONLY)),
        )
        assertEquals(
            PumStructuralState.NOT_PUM,
            PumStructuralState.fromDetection(PumDetection(PumDetectionStatus.NOT_PUM)),
        )
    }

    @Test fun `outer filter always wins`() {
        listOf(
            decision(false, PumStructuralState.SKIPPED, false, true, false),
            decision(true, PumStructuralState.DETECTED, true, true, true),
            decision(true, PumStructuralState.PARSE_FAILED, false, true, true),
        ).forEach { assertEquals(PumFilterOrigin.OUTER_FILTER, it.origin) }
    }

    @Test fun `enabled structural PUM uses block all before source result`() {
        assertEquals(
            PumFilterOrigin.PUM_BLOCK_ALL,
            decision(true, PumStructuralState.DETECTED, true, false, false).origin,
        )
        assertEquals(
            PumFilterOrigin.PUM_BLOCK_ALL,
            decision(true, PumStructuralState.DETECTED, true, false, true).origin,
        )
    }

    @Test fun `block all snapshot policy still resolves source for mandatory inspection`() {
        var calls = 0

        val result = PumSnapshotSourcePolicy.resolve(blockAllActive = true) {
            calls++
            "resolved"
        }

        assertEquals("resolved", result)
        assertEquals(1, calls)
    }

    @Test fun `inactive block-all snapshot policy retains source decoration`() {
        var calls = 0
        val result = PumSnapshotSourcePolicy.resolve(blockAllActive = false) {
            calls++
            "resolved"
        }

        assertEquals("resolved", result)
        assertEquals(1, calls)
    }

    @Test fun `source rule blocks resolved PUM only when block all is off`() {
        assertEquals(
            PumFilterOrigin.PUM_SOURCE_FILTER,
            decision(true, PumStructuralState.DETECTED, false, false, true).origin,
        )
        assertEquals(
            PumFilterOrigin.ALLOW,
            decision(true, PumStructuralState.DETECTED, false, false, false).origin,
        )
    }

    @Test fun `whitelisted posts never enter AI stage`() {
        assertFalse(PostAiStagePolicy.shouldRun(
            aiEnabled = true,
            localOrigin = PumFilterOrigin.ALLOW,
            whitelisted = true,
        ))
        assertTrue(PostAiStagePolicy.shouldRun(
            aiEnabled = true,
            localOrigin = PumFilterOrigin.ALLOW,
            whitelisted = false,
        ))
        assertFalse(PostAiStagePolicy.shouldRun(
            aiEnabled = true,
            localOrigin = PumFilterOrigin.PUM_BLOCK_ALL,
            whitelisted = false,
        ))
    }

    @Test fun `legacy source toggle does not gate confirmed PUM moderation`() {
        assertEquals(
            PumFilterOrigin.PUM_BLOCK_ALL,
            decision(false, PumStructuralState.DETECTED, true, false, true).origin,
        )
        assertEquals(
            PumFilterOrigin.PUM_SOURCE_FILTER,
            decision(false, PumStructuralState.DETECTED, false, false, true).origin,
        )
        listOf(
            decision(true, PumStructuralState.NOT_PUM, true, false, true),
            decision(true, PumStructuralState.PARSE_FAILED, true, false, true),
            decision(true, PumStructuralState.SKIPPED, true, false, true),
        ).forEach { assertEquals(PumFilterOrigin.ALLOW, it.origin) }
    }

    @Test fun `resolution failure cannot be treated as a source rule match`() {
        assertEquals(
            PumFilterOrigin.ALLOW,
            decision(
                master = true,
                structure = PumStructuralState.DETECTED,
                blockAll = false,
                outer = false,
                source = true,
                sourceResolved = false,
            ).origin,
        )
    }

    @Test fun `parse failure with block all off evaluates outer only`() {
        assertEquals(
            PumFilterOrigin.ALLOW,
            decision(true, PumStructuralState.PARSE_FAILED, false, false, true).origin,
        )
        assertEquals(
            PumFilterOrigin.OUTER_FILTER,
            decision(true, PumStructuralState.PARSE_FAILED, false, true, true).origin,
        )
    }

    @Test fun `isBlocked is false only for allow`() {
        assertFalse(PumFilterResult(PumFilterOrigin.ALLOW).isBlocked)
        listOf(
            PumFilterOrigin.OUTER_FILTER,
            PumFilterOrigin.PUM_BLOCK_ALL,
            PumFilterOrigin.PUM_SOURCE_FILTER,
        ).forEach { assertTrue(PumFilterResult(it).isBlocked) }
    }

    @Test fun `runtime routing uses pum override only for block all`() {
        val target = PostKey("M", "outer-gallery", "901")
        val outer = PumRuntimeRouting.route(
            origin = PumFilterOrigin.OUTER_FILTER,
            targetPostKey = target,
            outerFilterSource = "url",
            outerReason = "허용되지 않은 URL 감지 (https://bad.example)",
        )
        assertEquals(target, outer.targetPostKey)
        assertEquals("url", outer.actionOverridePrefix)
        assertEquals("url", outer.notificationType)
        assertEquals("허용되지 않은 URL 감지 (https://bad.example)", outer.reason)

        val blockAll = PumRuntimeRouting.route(
            origin = PumFilterOrigin.PUM_BLOCK_ALL,
            targetPostKey = target,
            outerFilterSource = "keyword",
            outerReason = "ignored",
        )
        assertEquals(target, blockAll.targetPostKey)
        assertEquals("pum", blockAll.actionOverridePrefix)
        assertEquals("keyword", blockAll.notificationType)
        assertEquals("펌 게시글 전체 차단", blockAll.reason)

        val source = PumRuntimeRouting.route(
            origin = PumFilterOrigin.PUM_SOURCE_FILTER,
            targetPostKey = target,
            outerFilterSource = "image",
            outerReason = null,
            sourceReason = "금지 보이스 ID 감지 (voice-7)",
            sourceFilterSource = "voice",
        )
        assertEquals(target, source.targetPostKey)
        assertEquals("voice", source.actionOverridePrefix)
        assertEquals("voice", source.notificationType)
        assertEquals("금지 보이스 ID 감지 (voice-7)", source.reason)
    }

    @Test fun `source matches retain detector action notification and reason`() {
        val target = PostKey("M", "g", "1")
        val cases = listOf(
            Triple("keyword", "keyword", "keyword"),
            Triple("url", "url", "url"),
            Triple("image", "image", "image"),
            Triple("dccon", "dccon", "image"),
            Triple("voice", "voice", "voice"),
            Triple("spam", "spam", "spam"),
            Triple("special_char", null, "spam"),
        )
        cases.forEach { (detector, expectedAction, expectedNotification) ->
            val route = PumRuntimeRouting.route(
                origin = PumFilterOrigin.PUM_SOURCE_FILTER,
                targetPostKey = target,
                outerFilterSource = null,
                outerReason = null,
                sourceReason = null,
                sourceFilterSource = detector,
            )
            assertEquals(expectedAction, route.actionOverridePrefix)
            assertEquals(expectedNotification, route.notificationType)
            assertEquals("필터 규칙 감지", route.reason)
        }
    }

    @Test fun `action config prefix is selected once from actual moderation winner`() {
        val target = PostKey("M", "g", "2")
        listOf("keyword", "url", "image", "dccon", "voice").forEach { detector ->
            val route = PumRuntimeRouting.route(
                PumFilterOrigin.OUTER_FILTER, target, detector, "reason"
            )
            assertEquals(detector, PumRuntimeRouting.actionPrefix(ModerationWinner.OUTER_FILTER, route))
        }
        val blockAll = PumRuntimeRouting.route(PumFilterOrigin.PUM_BLOCK_ALL, target, "dccon", null)
        assertEquals("pum", PumRuntimeRouting.actionPrefix(ModerationWinner.PUM, blockAll))
        val source = PumRuntimeRouting.route(
            PumFilterOrigin.PUM_SOURCE_FILTER, target, null, null,
            sourceReason = "금지 디시콘 감지", sourceFilterSource = "dccon",
        )
        assertEquals("dccon", PumRuntimeRouting.actionPrefix(ModerationWinner.PUM, source))
        val allow = PumRuntimeRouting.route(PumFilterOrigin.ALLOW, target, null, null)
        assertEquals("ai", PumRuntimeRouting.actionPrefix(ModerationWinner.AI, allow))
        assertEquals(null, PumRuntimeRouting.actionPrefix(ModerationWinner.ALLOW, allow))
        val unknown = PumRuntimeRouting.route(PumFilterOrigin.OUTER_FILTER, target, "unknown", "reason")
        assertEquals(null, PumRuntimeRouting.actionPrefix(ModerationWinner.OUTER_FILTER, unknown))
    }

    @Test fun `source detector details survive PUM origin routing`() {
        val target = PostKey("M", "outer-gallery", "902")
        listOf(
            "일반 금지어 감지 (금칙어)",
            "허용되지 않은 URL 감지 (https://bad.example)",
            "이미지 alt 유사도 차단 (감지='blocked image')",
            "금지 디시콘 감지 (token=dccon-1)",
            "금지 보이스 ID 감지 (voice-7)",
        ).forEach { detectorReason ->
            val routed = PumRuntimeRouting.route(
                origin = PumFilterOrigin.PUM_SOURCE_FILTER,
                targetPostKey = target,
                outerFilterSource = null,
                outerReason = null,
                sourceReason = detectorReason,
            )
            assertEquals(detectorReason, routed.reason)
            assertEquals(target, routed.targetPostKey)
        }
    }

    @Test fun `AI action route remains independent from local PUM origin routing`() {
        assertEquals("ai", PumRuntimeRouting.AI_ACTION_OVERRIDE_PREFIX)
    }

    @Test fun `outer local block wins over AI block`() {
        assertEquals(
            ModerationWinner.OUTER_FILTER,
            ModerationWinnerPolicy.decide(PumFilterOrigin.OUTER_FILTER, AiStageOutcome.BLOCK),
        )
    }

    @Test fun `PUM block wins over AI block`() {
        listOf(PumFilterOrigin.PUM_BLOCK_ALL, PumFilterOrigin.PUM_SOURCE_FILTER).forEach { origin ->
            assertEquals(
                ModerationWinner.PUM,
                ModerationWinnerPolicy.decide(origin, AiStageOutcome.BLOCK),
            )
        }
    }

    @Test fun `AI block wins only after local allow`() {
        assertEquals(
            ModerationWinner.AI,
            ModerationWinnerPolicy.decide(PumFilterOrigin.ALLOW, AiStageOutcome.BLOCK),
        )
    }

    @Test fun `local allow with AI allow or error preserves allow behavior`() {
        listOf(AiStageOutcome.ALLOW, AiStageOutcome.ERROR, AiStageOutcome.SKIPPED).forEach { aiOutcome ->
            assertEquals(
                ModerationWinner.ALLOW,
                ModerationWinnerPolicy.decide(PumFilterOrigin.ALLOW, aiOutcome),
            )
        }
    }

    private fun decision(
        master: Boolean,
        structure: PumStructuralState,
        blockAll: Boolean,
        outer: Boolean,
        source: Boolean,
        sourceResolved: Boolean = true,
    ): PumFilterResult = PumFilterPolicy.decide(
        masterEnabled = master,
        structuralState = structure,
        blockAllPumPosts = blockAll,
        outerBlocked = outer,
        sourceFilter = if (sourceResolved) {
            PumSourceFilterState.Resolved(blocked = source)
        } else {
            PumSourceFilterState.NotResolved
        },
    )
}
