package com.heyheyon.armbandbot

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.json.JSONObject

internal enum class ClaimStatus { PENDING, SUCCEEDED, FAILED, UNKNOWN }
internal enum class ClaimDecision { ACQUIRE, SKIP }

internal const val MODERATION_REQUEST_TIMEOUT_MS = 60_000
internal const val MODERATION_CLAIM_LEASE_MS = 30 * 60 * 1_000L

@Entity(
    tableName = "moderation_action_claims",
    primaryKeys = ["gallType", "gallId", "postNum", "targetType", "targetNo", "actionKind"],
)
internal data class ModerationActionClaim(
    val gallType: String,
    val gallId: String,
    val postNum: String,
    val targetType: String,
    val targetNo: String,
    val actionKind: String,
    val actorBotId: String,
    @ColumnInfo(defaultValue = "''")
    val ownerToken: String,
    val status: String,
    val claimedAt: Long,
    val finishedAt: Long? = null,
)

@Dao
internal interface ModerationClaimDao {
    @Query("""SELECT * FROM moderation_action_claims WHERE gallType=:gallType AND gallId=:gallId AND postNum=:postNum AND targetType=:targetType AND targetNo=:targetNo AND actionKind=:actionKind LIMIT 1""")
    fun find(gallType: String, gallId: String, postNum: String, targetType: String, targetNo: String, actionKind: String): ModerationActionClaim?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(claim: ModerationActionClaim): Long

    @Query("""UPDATE moderation_action_claims SET actorBotId=:newActorBotId, ownerToken=:newOwnerToken, status='PENDING', claimedAt=:now, finishedAt=NULL WHERE gallType=:gallType AND gallId=:gallId AND postNum=:postNum AND targetType=:targetType AND targetNo=:targetNo AND actionKind=:actionKind AND ownerToken=:expectedOwnerToken AND status=:expectedStatus AND claimedAt=:expectedClaimedAt""")
    fun takeover(gallType: String, gallId: String, postNum: String, targetType: String, targetNo: String, actionKind: String, expectedOwnerToken: String, expectedStatus: String, expectedClaimedAt: Long, newActorBotId: String, newOwnerToken: String, now: Long): Int

    @Query("""UPDATE moderation_action_claims SET status=:status, finishedAt=:finishedAt WHERE gallType=:gallType AND gallId=:gallId AND postNum=:postNum AND targetType=:targetType AND targetNo=:targetNo AND actionKind=:actionKind AND actorBotId=:actorBotId AND ownerToken=:ownerToken AND status='PENDING'""")
    fun finalizeOwned(gallType: String, gallId: String, postNum: String, targetType: String, targetNo: String, actionKind: String, actorBotId: String, ownerToken: String, status: String, finishedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun replaceForRestore(claim: ModerationActionClaim): Long

    @Query("DELETE FROM moderation_action_claims")
    fun clearAll()

    @Transaction
    fun acquire(request: ModerationActionClaim, now: Long, leaseMs: Long, failureCooldownMs: Long): Boolean {
        require(request.actorBotId.isNotBlank()) { "actorBotId가 필요합니다." }
        require(request.ownerToken.isNotBlank()) { "ownerToken이 필요합니다." }
        val existing = find(request.gallType, request.gallId, request.postNum, request.targetType, request.targetNo, request.actionKind)
        if (decideClaim(existing, now, leaseMs, failureCooldownMs) == ClaimDecision.SKIP) return false
        val pending = request.copy(status = ClaimStatus.PENDING.name, claimedAt = now, finishedAt = null)
        return if (existing == null) {
            insertIfAbsent(pending) != -1L
        } else {
            takeover(
                request.gallType, request.gallId, request.postNum, request.targetType, request.targetNo,
                request.actionKind, existing.ownerToken, existing.status, existing.claimedAt,
                request.actorBotId, request.ownerToken, now,
            ) == 1
        }
    }

    @Transaction
    fun finalize(request: ModerationActionClaim, status: ClaimStatus, finishedAt: Long): Boolean {
        require(status != ClaimStatus.PENDING) { "완료 상태는 PENDING일 수 없습니다." }
        require(request.ownerToken.isNotBlank()) { "ownerToken이 필요합니다." }
        return finalizeOwned(
            request.gallType, request.gallId, request.postNum, request.targetType, request.targetNo,
            request.actionKind, request.actorBotId, request.ownerToken, status.name, finishedAt,
        ) == 1
    }
}

internal fun classifyModerationClaimStatus(response: String): ClaimStatus {
    val result = runCatching {
        val json = JSONObject(response)
        if (!json.has("result") || json.isNull("result")) null else json.getString("result")
    }.getOrNull()
    return classifyModerationResultValue(result)
}

internal fun classifyModerationResultValue(result: String?): ClaimStatus {
    return when (result?.trim()?.lowercase()) {
        "success" -> ClaimStatus.SUCCEEDED
        "fail", "failed", "error" -> ClaimStatus.FAILED
        else -> ClaimStatus.UNKNOWN
    }
}

internal fun moderationClaimActionKind(
    modeName: String,
    targetType: String,
    deleteOnBlock: Boolean = false,
): String {
    val normalizedMode = modeName.trim().uppercase()
    val normalizedTarget = targetType.trim().uppercase()
    return when {
        normalizedMode == "DELETE_ONLY" -> "DELETE_$normalizedTarget"
        normalizedMode == "BLOCK" && deleteOnBlock -> "BLOCK_DELETE_$normalizedTarget"
        else -> normalizedMode
    }
}

internal fun spamBurstDeleteClaimActionKind(targetType: String): String =
    "DELETE_${targetType.trim().uppercase()}"

internal fun requiresModerationClaim(actionKind: String): Boolean =
    actionKind.trim().uppercase() != "HOLD"

internal fun decideClaim(
    existing: ModerationActionClaim?,
    now: Long,
    leaseMs: Long,
    failureCooldownMs: Long,
): ClaimDecision {
    require(leaseMs >= 0L) { "claim lease는 음수일 수 없습니다." }
    require(failureCooldownMs >= 0L) { "실패 재시도 대기시간은 음수일 수 없습니다." }
    if (existing == null) return ClaimDecision.ACQUIRE

    return when (existing.status) {
        ClaimStatus.SUCCEEDED.name -> ClaimDecision.SKIP
        ClaimStatus.PENDING.name -> if (elapsedAtLeast(now, existing.claimedAt, leaseMs)) {
            ClaimDecision.ACQUIRE
        } else {
            ClaimDecision.SKIP
        }
        ClaimStatus.FAILED.name -> {
            val retryFrom = existing.finishedAt ?: existing.claimedAt
            if (elapsedAtLeast(now, retryFrom, failureCooldownMs)) ClaimDecision.ACQUIRE else ClaimDecision.SKIP
        }
        else -> ClaimDecision.SKIP
    }
}

private fun elapsedAtLeast(now: Long, since: Long, threshold: Long): Boolean =
    now >= since && now - since >= threshold
