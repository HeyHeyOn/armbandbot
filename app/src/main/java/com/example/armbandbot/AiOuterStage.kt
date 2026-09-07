package com.heyheyon.armbandbot

import kotlinx.coroutines.CancellationException

/** Own the exact queue drained by this attempt, never a bot-id lookup after suspension. */
internal class AiOuterStageAttempt {
    private var drainedQueue: AiBatchQueue? = null
    private var drainedItems: List<AiBatchQueueItem> = emptyList()

    fun drain(queue: AiBatchQueue, only: PostKey? = null): List<AiBatchQueueItem> {
        check(drainedQueue == null) { "An AI stage may drain only once" }
        drainedQueue = queue
        return (if (only != null) listOfNotNull(queue.remove(only)) else queue.drainFlushable())
            .also { drainedItems = it }
    }

    fun restoreInterruptedBatch() {
        // A stopped generation may already have been replaced in aiBatchQueues.
        // Restore only its captured queue; never seed the replacement generation.
        val queue = drainedQueue ?: return
        queue.restoreInterrupted(drainedItems)
        drainedItems = emptyList()
    }
}

/** Keep ordinary provider-failure handling, but never turn control flow into completion. */
internal inline fun <T> runAiOuterStage(attempt: AiOuterStageAttempt, block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (failure: Throwable) {
        if (failure is SchedulePausedException || failure is CancellationException) {
            attempt.restoreInterruptedBatch()
            throw failure
        }
        Result.failure(failure)
    }
