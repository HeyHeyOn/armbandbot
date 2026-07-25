package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PumModerationContentTest {
    private val repostKey = PostKey("M", "reposts", "900")
    private val sourceKey = PostKey("G", "originals", "42")
    private val resolved = PumResolution(
        status = PumSourceStatus.RESOLVED,
        sourceKey = sourceKey,
        sourceUrl = "https://gall.dcinside.com/board/view/?id=originals&no=42",
        title = "source title",
        bodyText = "source body",
        imageAlts = listOf("source image text"),
        contentHash = "hash",
    )

    @Test fun `disabled mode is byte-for-byte legacy and never resolves`() {
        var calls = 0
        val result = PumModerationContent.resolve(
            enabled = false,
            detection = PumDetection(PumDetectionStatus.PUM_CONFIRMED),
            originalPostKey = repostKey,
            originalContent = "repost title repost body",
        ) { calls++; resolved }

        assertEquals(0, calls)
        assertEquals("repost title repost body", result.moderationContent)
        assertNull(result.sourceResolution)
        assertEquals(repostKey, result.targetPostKey)
    }

    @Test fun `no structural detail does not resolve even with a marker`() {
        var calls = 0
        val result = PumModerationContent.resolve(
            enabled = true,
            detection = PumDetection(PumDetectionStatus.PUM_MARKER_ONLY),
            originalPostKey = repostKey,
            originalContent = "ordinary repost",
        ) { calls++; resolved }

        assertEquals(0, calls)
        assertEquals("ordinary repost", result.moderationContent)
        assertNull(result.sourceResolution)
    }

    @Test fun `resolved source text is clearly delimited and enriched`() {
        val result = PumModerationContent.resolve(
            enabled = true,
            detection = PumDetection(PumDetectionStatus.PUM_CONFIRMED),
            originalPostKey = repostKey,
            originalContent = "repost title repost body",
        ) { resolved }

        assertTrue(result.moderationContent.startsWith("repost title repost body"))
        assertTrue(result.moderationContent.contains(PumModerationContent.SOURCE_BEGIN))
        assertTrue(result.moderationContent.contains("source title"))
        assertTrue(result.moderationContent.contains("source body"))
        assertTrue(result.moderationContent.contains("source image text"))
        assertTrue(result.moderationContent.endsWith(PumModerationContent.SOURCE_END))
        assertSame(resolved, result.sourceResolution)
    }

    @Test fun `resolution failure falls back to ordinary moderation`() {
        val failure = PumResolution(PumSourceStatus.TEMPORARY_FAILURE, sourceKey = sourceKey)
        val result = PumModerationContent.resolve(
            enabled = true,
            detection = PumDetection(PumDetectionStatus.PUM_LOADER_ONLY),
            originalPostKey = repostKey,
            originalContent = "still check this repost",
        ) { failure }

        assertEquals("still check this repost", result.moderationContent)
        assertSame(failure, result.sourceResolution)
        assertFalse(result.hasResolvedSource)
    }

    @Test fun `source metadata never replaces moderation target identity`() {
        val result = PumModerationContent.resolve(
            enabled = true,
            detection = PumDetection(PumDetectionStatus.PUM_CONFIRMED),
            originalPostKey = repostKey,
            originalContent = "repost",
        ) { resolved }

        assertEquals(repostKey, result.targetPostKey)
        assertNotEquals(sourceKey, result.targetPostKey)
        assertEquals(sourceKey, result.sourceResolution?.sourceKey)
    }

    @Test fun `resolved source changes AI fingerprint through enriched content`() {
        fun input(body: String) = AiFilterPostInput(
            postKey = repostKey,
            title = "repost title",
            authorIdOrIp = "author",
            nickname = "nick",
            body = body,
            mediaSources = emptyList(),
            comments = emptyList(),
        )
        val ordinary = PumModerationContent.resolve(
            enabled = false,
            detection = PumDetection(PumDetectionStatus.PUM_CONFIRMED),
            originalPostKey = repostKey,
            originalContent = "repost title repost body",
        ) { resolved }
        val enriched = PumModerationContent.resolve(
            enabled = true,
            detection = PumDetection(PumDetectionStatus.PUM_CONFIRMED),
            originalPostKey = repostKey,
            originalContent = "repost title repost body",
        ) { resolved }

        assertNotEquals(
            AiInputFingerprint.from(input(ordinary.moderationContent)),
            AiInputFingerprint.from(input(enriched.moderationContent)),
        )
    }
}
