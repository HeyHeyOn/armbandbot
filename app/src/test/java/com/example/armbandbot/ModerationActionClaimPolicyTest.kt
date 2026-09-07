package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationActionClaimPolicyTest {
    private val leaseMs = 60_000L
    private val retryCooldownMs = 10_000L

    @Test
    fun productionLeaseIsConservativeAndLongerThanTheModerationRequestTimeout() {
        assertEquals(30 * 60 * 1_000L, MODERATION_CLAIM_LEASE_MS)
        assertTrue(MODERATION_CLAIM_LEASE_MS > MODERATION_REQUEST_TIMEOUT_MS)
        assertTrue(MODERATION_CLAIM_LEASE_MS >= MODERATION_REQUEST_TIMEOUT_MS * 10L)
    }

    @Test
    fun missingClaimCanBeAcquired() {
        assertEquals(
            ClaimDecision.ACQUIRE,
            decideClaim(existing = null, now = 100_000L, leaseMs = leaseMs, failureCooldownMs = retryCooldownMs),
        )
    }

    @Test
    fun recentPendingClaimIsSkipped() {
        assertEquals(
            ClaimDecision.SKIP,
            decideClaim(claim(ClaimStatus.PENDING, 90_000L), 100_000L, leaseMs, retryCooldownMs),
        )
    }

    @Test
    fun expiredPendingClaimCanBeTakenOver() {
        assertEquals(
            ClaimDecision.ACQUIRE,
            decideClaim(claim(ClaimStatus.PENDING, 39_999L), 100_000L, leaseMs, retryCooldownMs),
        )
    }

    @Test
    fun successfulClaimIsNeverRepeated() {
        assertEquals(
            ClaimDecision.SKIP,
            decideClaim(claim(ClaimStatus.SUCCEEDED, 0L), Long.MAX_VALUE, leaseMs, retryCooldownMs),
        )
    }

    @Test
    fun localHoldDoesNotUsePersistentRemoteActionClaim() {
        assertFalse(requiresModerationClaim("HOLD"))
        assertTrue(requiresModerationClaim("BLOCK"))
        assertTrue(requiresModerationClaim("DELETE_POST"))
    }

    @Test
    fun responseClassificationOnlyRetriesExplicitServerFailures() {
        assertEquals(ClaimStatus.SUCCEEDED, classifyModerationResultValue("success"))
        assertEquals(ClaimStatus.FAILED, classifyModerationResultValue("fail"))
        assertEquals(ClaimStatus.FAILED, classifyModerationResultValue("failed"))
        assertEquals(ClaimStatus.FAILED, classifyModerationResultValue("error"))
        assertEquals(ClaimStatus.UNKNOWN, classifyModerationResultValue(null))
        assertEquals(ClaimStatus.UNKNOWN, classifyModerationResultValue("skipped"))
    }

    @Test
    fun equivalentPostDeletePathsShareOneClaimKind() {
        assertEquals("DELETE_POST", moderationClaimActionKind("DELETE_ONLY", "POST"))
        assertEquals("DELETE_POST", spamBurstDeleteClaimActionKind("POST"))
        assertEquals("DELETE_COMMENT", moderationClaimActionKind("DELETE_ONLY", "COMMENT"))
        assertEquals("BLOCK", moderationClaimActionKind("BLOCK", "POST", deleteOnBlock = false))
        assertEquals("BLOCK_DELETE_POST", moderationClaimActionKind("BLOCK", "POST", deleteOnBlock = true))
        assertEquals("HOLD", moderationClaimActionKind("HOLD", "COMMENT"))
    }

    @Test
    fun unknownOutcomeIsNeverAutomaticallyRetried() {
        val unknown = claim(ClaimStatus.UNKNOWN, claimedAt = 80_000L, finishedAt = 95_000L)
        assertEquals(ClaimDecision.SKIP, decideClaim(unknown, Long.MAX_VALUE, leaseMs, retryCooldownMs))
    }

    @Test
    fun failedClaimWaitsForCooldownThenRetries() {
        val failed = claim(ClaimStatus.FAILED, claimedAt = 80_000L, finishedAt = 95_000L)
        assertEquals(ClaimDecision.SKIP, decideClaim(failed, 104_999L, leaseMs, retryCooldownMs))
        assertEquals(ClaimDecision.ACQUIRE, decideClaim(failed, 105_000L, leaseMs, retryCooldownMs))
    }

    private fun claim(
        status: ClaimStatus,
        claimedAt: Long,
        finishedAt: Long? = null,
    ) = ModerationActionClaim(
        gallType = "M",
        gallId = "test",
        postNum = "123",
        targetType = "post",
        targetNo = "",
        actionKind = "BLOCK",
        actorBotId = "bot-a",
        ownerToken = "owner-a",
        status = status.name,
        claimedAt = claimedAt,
        finishedAt = finishedAt,
    )
}
