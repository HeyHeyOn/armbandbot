package com.heyheyon.armbandbot

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class RecommendationGuardTest {
    private val key = PostKey("MI", "fixture", "123")
    private val policy = RecommendationPolicy(180, 10)
    private fun row(count: Long, other: PostKey = key) = RecommendationRow(other, count, "", "1.2", "닉")

    @Test fun twoMinuteIncreaseSatisfiesThreeMinuteWindow() {
        val t = RecommendationTracker(); assertNull(t.observe(row(50), 0, policy))
        val m = t.observe(row(60), 120_000, policy)!!
        assertEquals(50, m.from); assertEquals(60, m.to); assertEquals(120_000, m.detectedAt - m.startedAt)
    }
    @Test fun cannotTurnFourMinutesIntoThree() {
        val t = RecommendationTracker(); t.observe(row(0), 0, policy)
        assertNull(t.observe(row(6), 120_000, policy)); assertNull(t.observe(row(12), 240_000, policy))
    }
    @Test fun boundaryIsInclusiveButNotOneMillisecondLonger() {
        for ((at, expected) in listOf(180_000L to true, 180_001L to false)) {
            val t = RecommendationTracker(); t.observe(row(0), 0, policy)
            assertEquals(expected, t.observe(row(10), at, policy) != null)
        }
    }
    @Test fun firstSeenHighCountIsNotAnIncrease() { assertNull(RecommendationTracker().observe(row(100_000), 0, policy)) }
    @Test fun windowMovesAcrossClockMinuteBoundaries() {
        val t = RecommendationTracker(); t.observe(row(0), 170_000, policy)
        t.observe(row(6), 200_000, policy)
        assertNotNull(t.observe(row(10), 220_000, policy))
    }
    @Test fun longGapStartsNewBaseline() {
        val t = RecommendationTracker(); t.observe(row(0), 0, policy)
        assertNull(t.observe(row(100), 600_000, policy))
        assertNotNull(t.observe(row(110), 630_000, policy))
    }
    @Test fun decreasingCountDiscardsOldEvidenceAndPendingMatch() {
        val t = RecommendationTracker(); t.observe(row(0), 0, policy)
        assertNotNull(t.observe(row(10), 30_000, policy))
        assertNull(t.observe(row(5), 60_000, policy)); assertNull(t.observe(row(10), 90_000, policy))
    }
    @Test fun cachedObservationCannotSupplyASecondPoint() {
        val t = RecommendationTracker(); t.observe(row(0), 20_000, policy)
        assertNull(t.observe(row(10), 20_000, policy)); assertNull(t.observe(row(10), 19_000, policy))
    }
    @Test fun pendingEvidenceIsBoundedForLaterPromotion() {
        val t = RecommendationTracker(); t.observe(row(0), 0, policy)
        val detected = t.observe(row(10), 30_000, policy)
        assertEquals(detected, t.observe(row(10), 90_000, policy))
        assertNull(t.observe(row(10), 240_000, policy))
    }
    @Test fun differentGalleriesAndPostsNeverShareCounts() {
        val t = RecommendationTracker(); t.observe(row(0), 0, policy)
        assertNull(t.observe(row(100, PostKey("M", "fixture", "123")), 30_000, policy))
        assertNull(t.observe(row(100, key.copy(postNo = "124")), 30_000, policy))
    }
    @Test fun forgettingProtectedPostsRemovesBaseline() {
        val t = RecommendationTracker(); t.observe(row(0), 0, policy); t.forget(key)
        assertNull(t.observe(row(10), 30_000, policy))
    }
    @Test fun memoryBoundEvictsOldestPostWithoutFalseDetection() {
        val t = RecommendationTracker(1); t.observe(row(0), 0, policy)
        t.observe(row(0, key.copy(postNo = "124")), 1, policy)
        assertNull(t.observe(row(10), 30_000, policy))
    }
    @Test fun invalidConfigurationFailsClosed() {
        for ((seconds, increase) in listOf(0 to 10, 9 to 10, 3601 to 10, 180 to 0, 180 to 1_000_001)) {
            assertTrue(runCatching { RecommendationPolicy(seconds, increase) }.isFailure)
        }
        assertEquals(5_000, RecommendationPolicy(10, 1).pollMs)
        assertEquals(30_000, policy.pollMs)
    }
    private fun list(type: String, count: String = "1,234", kind: String = "icon_txt", linkNo: String = "123") = Jsoup.parse("""
        <table><tr class="ub-content" data-no="123" data-type="$kind"><td class="gall_num">123</td>
        <td class="gall_tit"><a href="/${if(type == "M") "mgallery" else "mini"}/board/view/?id=fixture&amp;no=$linkNo">글</a></td>
        <td class="gall_writer" data-uid="writer" data-ip="" data-nick="닉"></td><td class="gall_recommend">$count</td></tr></table>
    """, "https://gall.dcinside.com/")
    @Test fun minorAndMiniNumericRowsParseSameWay() {
        for (type in listOf("M", "MI")) {
            val row = parseRecommendationRows(list(type), type, "fixture").single()
            assertEquals(1234, row.count); assertEquals(type, row.key.gallType); assertEquals("writer", row.uid)
        }
    }
    @Test fun missingOrMalformedCountsAreNotZero() {
        for (text in listOf("", "-", "1.2만", "-1", "1,23", "999999999999999999999999")) {
            assertTrue(parseRecommendationRows(list("MI", text), "MI", "fixture").isEmpty())
        }
        assertEquals(0, parseRecommendationRows(list("MI", "0"), "MI", "fixture").single().count)
    }
    @Test fun noticesPinnedAndMismatchedIdentifiersAreExcluded() {
        for (kind in listOf("icon_notice", "icon_slow")) assertTrue(parseRecommendationRows(list("MI", kind = kind), "MI", "fixture").isEmpty())
        assertTrue(parseRecommendationRows(list("MI", linkNo = "999"), "MI", "fixture").isEmpty())
        assertTrue(parseRecommendationRows(list("MI"), "M", "fixture").isEmpty())
    }
    @Test fun releaseEndpointAndPayloadUseExactSingleTarget() {
        for (type in listOf("M", "MI")) {
            val target = key.copy(gallType = type)
            assertTrue(nativeActionUrl(target, "set_recommend").endsWith("${if(type == "M") "minor" else "mini"}_manager_board_ajax/set_recommend"))
            val payload = recommendationReleasePayload(target, "fixture-token")
            assertEquals(setOf("ci_t", "id", "nos[]", "_GALLTYPE_", "mode"), payload.keys)
            assertEquals("REL", payload["mode"]); assertEquals("123", payload["nos[]"])
        }
    }
    @Test fun settingsCopyKeepsCriteriaButRequiresExplicitEnable() {
        val source = mapOf(RECOMMEND_GUARD_ENABLED to true, RECOMMEND_GUARD_SECONDS to 120, RECOMMEND_GUARD_COUNT to 33)
        val copied = prepareCopiedBotSettingsSnapshot(source, "copy")
        assertEquals(false, copied[RECOMMEND_GUARD_ENABLED]); assertEquals(120, copied[RECOMMEND_GUARD_SECONDS]); assertEquals(33, copied[RECOMMEND_GUARD_COUNT])
        assertTrue(RECOMMEND_GUARD_ENABLED in EXPORTABLE_BOOLEAN_KEYS)
        assertTrue(RECOMMEND_GUARD_SECONDS in EXPORTABLE_INT_KEYS && RECOMMEND_GUARD_COUNT in EXPORTABLE_INT_KEYS)
    }
    @Test fun migrationDefaultsAreOffAndDoNotOverwriteSavedCriteria() {
        val defaults = migrateBotSettingsSnapshot(emptyMap())
        assertEquals(false, defaults[RECOMMEND_GUARD_ENABLED]); assertEquals(180, defaults[RECOMMEND_GUARD_SECONDS]); assertEquals(10, defaults[RECOMMEND_GUARD_COUNT])
        val migrated = migrateBotSettingsSnapshot(mapOf(RECOMMEND_GUARD_SECONDS to 61, RECOMMEND_GUARD_COUNT to 12))
        assertEquals(61, migrated[RECOMMEND_GUARD_SECONDS]); assertEquals(12, migrated[RECOMMEND_GUARD_COUNT])
    }
    @Test fun requestSpacingCoordinatesWorkersAndChecksCancellationWhileWaiting() {
        var now = 0L; var checks = 0
        val spacing = BotRequestSpacing({ true }, { now }, { now += it })
        spacing.awaitSlot { checks++ }; assertEquals(0, now)
        spacing.awaitSlot { checks++ }; assertEquals(1000, now); assertTrue(checks >= 10)
        val result = runCatching { spacing.awaitSlot { error("stopped") } }
        assertTrue(result.isFailure); assertEquals(1000, now)
    }
}
