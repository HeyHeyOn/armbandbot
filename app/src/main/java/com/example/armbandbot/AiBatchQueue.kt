package com.heyheyon.armbandbot

import java.security.MessageDigest

internal data class AiInputFingerprint(val value: String) {
    companion object {
        fun from(input: AiFilterPostInput): AiInputFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }

            add(input.postKey.gallType)
            add(input.postKey.gallId)
            add(input.postKey.postNo)
            add(input.title)
            add(input.authorIdOrIp)
            add(input.nickname)
            add(input.body)
            add(input.mediaSources.size.toString())
            input.mediaSources.forEach(::add)
            add(input.comments.size.toString())
            input.comments.forEach { comment ->
                add(comment.commentId)
                add(comment.authorIdOrIp)
                add(comment.nickname)
                add(comment.body)
            }
            return AiInputFingerprint(digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}

internal data class AiBatchResult(
    val postKey: PostKey,
    val inputFingerprint: AiInputFingerprint,
    val decision: AiFilterPostDecision,
) {
    fun matches(postKey: PostKey, input: AiFilterPostInput): Boolean =
        this.postKey == postKey && input.postKey == postKey && inputFingerprint == AiInputFingerprint.from(input)

    fun withoutComment(commentId: String): AiBatchResult = copy(
        decision = decision.copy(
            commentDecisions = decision.commentDecisions.filterNot { it.commentId == commentId }
        )
    )

    companion object {
        fun forInput(decision: AiFilterPostDecision, input: AiFilterPostInput): AiBatchResult {
            require(decision.postKey == input.postKey) { "AI result identity must match input identity" }
            return AiBatchResult(decision.postKey, AiInputFingerprint.from(input), decision)
        }
    }
}

internal data class AiPostDecisionApplication(
    val decision: AiFilterDecision,
    val reviewReason: String?,
    val blockReasonPrefix: String?,
    val notificationType: String?,
)

internal fun applyAiPostDecision(result: AiFilterPostDecision): AiPostDecisionApplication {
    val isReview = result.decision.type == AiFilterDecisionType.REVIEW
    return AiPostDecisionApplication(
        decision = result.decision,
        reviewReason = result.decision.reason.takeIf { isReview },
        blockReasonPrefix = "AI 필터 검토 필요".takeIf { isReview },
        notificationType = "ai".takeIf { isReview },
    )
}

internal object AiExecutionPolicy {
    fun shouldRetry(success: Boolean, response: String): Boolean {
        if (!success) return true
        return response.contains("\"reason\":\"recent_failure_throttle\"")
    }
}

internal object AiImmediateExecutionPolicy {
    fun shouldExecute(
        result: AiBatchResult,
        currentPostKey: PostKey,
        currentInput: AiFilterPostInput,
    ): Boolean = result.postKey == currentPostKey && result.matches(currentPostKey, currentInput)
}

internal data class AiBatchQueueItem(
    val postKey: PostKey,
    val postInput: AiFilterPostInput,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    init {
        require(postInput.postKey == postKey) { "Queue identity must match input identity" }
    }

    val postNo: String get() = postKey.postNo
    val estimatedWeight: Int
        get() {
            val mediaWeight = postInput.mediaSources.sumOf { it.length }
            val commentsWeight = postInput.comments.sumOf { it.body.length + it.nickname.length + it.authorIdOrIp.length }
            return postInput.title.length +
                postInput.authorIdOrIp.length +
                postInput.nickname.length +
                postInput.body.length +
                mediaWeight +
                commentsWeight
        }
}

internal data class AiPostExecutionPlan(
    val postKey: PostKey,
    val reason: String,
    val category: String,
    val confidence: Int,
    val inputFingerprint: AiInputFingerprint,
) {
    val postNo: String get() = postKey.postNo
    fun matches(input: AiFilterPostInput): Boolean =
        input.postKey == postKey && inputFingerprint == AiInputFingerprint.from(input)
}

internal data class AiCommentExecutionPlan(
    val postKey: PostKey,
    val commentNo: String,
    val reason: String,
    val category: String,
    val confidence: Int,
    val inputFingerprint: AiInputFingerprint,
) {
    val postNo: String get() = postKey.postNo
    fun matches(input: AiFilterPostInput): Boolean =
        input.postKey == postKey && inputFingerprint == AiInputFingerprint.from(input)
}

internal class AiBatchQueue(
    private val maxPosts: Int,
    private val maxWaitMs: Long,
    private val maxWeight: Int,
) {
    private val items = mutableListOf<AiBatchQueueItem>()

    @Synchronized
    fun addOrReplace(item: AiBatchQueueItem) {
        val existingIndex = items.indexOfFirst { it.postKey == item.postKey }
        if (existingIndex >= 0) {
            items[existingIndex] = item
        } else {
            items += item
        }
    }

    @Synchronized
    fun remove(postKey: PostKey): AiBatchQueueItem? {
        val index = items.indexOfFirst { it.postKey == postKey }
        return if (index >= 0) items.removeAt(index) else null
    }

    @Synchronized
    fun isEmpty(): Boolean = items.isEmpty()

    @Synchronized
    fun shouldFlush(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (items.isEmpty()) return false
        val totalWeight = items.sumOf { it.estimatedWeight }
        val firstWaitMs = nowMs - (items.minOfOrNull { it.createdAtMs } ?: nowMs)
        return totalWeight >= maxWeight || items.size >= maxPosts || firstWaitMs >= maxWaitMs
    }

    @Synchronized
    fun drainFlushable(): List<AiBatchQueueItem> {
        if (items.isEmpty()) return emptyList()
        val drained = items.toList()
        items.clear()
        return drained
    }
}
