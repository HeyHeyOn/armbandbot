package com.heyheyon.armbandbot

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
) {
    val postNo: String get() = postKey.postNo
}

internal data class AiCommentExecutionPlan(
    val postKey: PostKey,
    val commentNo: String,
    val reason: String,
    val category: String,
    val confidence: Int,
) {
    val postNo: String get() = postKey.postNo
}

internal class AiBatchQueue(
    private val maxPosts: Int,
    private val maxWaitMs: Long,
    private val maxWeight: Int,
) {
    private val items = mutableListOf<AiBatchQueueItem>()

    fun addOrReplace(item: AiBatchQueueItem) {
        val existingIndex = items.indexOfFirst { it.postKey == item.postKey }
        if (existingIndex >= 0) {
            items[existingIndex] = item
        } else {
            items += item
        }
    }

    fun isEmpty(): Boolean = items.isEmpty()

    fun shouldFlush(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (items.isEmpty()) return false
        val totalWeight = items.sumOf { it.estimatedWeight }
        val firstWaitMs = nowMs - (items.minOfOrNull { it.createdAtMs } ?: nowMs)
        return totalWeight >= maxWeight || items.size >= maxPosts || firstWaitMs >= maxWaitMs
    }

    fun drainFlushable(): List<AiBatchQueueItem> {
        if (items.isEmpty()) return emptyList()
        val drained = items.toList()
        items.clear()
        return drained
    }
}
