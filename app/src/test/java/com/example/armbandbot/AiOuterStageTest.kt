package com.heyheyon.armbandbot

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiOuterStageTest {
    private fun item(no: String) = AiBatchQueueItem(
        PostKey("M", "gallery", no),
        AiFilterPostInput(PostKey("M", "gallery", no), "title", "author", "nick", "body", emptyList(), emptyList()),
        createdAtMs = 1L,
    )
    private fun queue() = AiBatchQueue(2, 1000, 100000)

    @Test fun pauseRetainsPreviouslyCheckedBatchAndResumesInSameJob() = runBlocking {
        val queue = queue()
        val earlier = item("1")
        val current = item("2")
        val checked = mutableSetOf(earlier.postKey)
        val resume = CompletableDeferred<Unit>()
        var requests = 0
        var waiting = false
        val inspected = mutableListOf<PostKey>()
        val job = async {
            var closed = true
            while (true) {
                // The current candidate must not acquire checked DB state on pause.
                if (current.postKey in checked) break
                try {
                    queue.addOrReplace(current)
                    val attempt = AiOuterStageAttempt()
                    runAiOuterStage(attempt) {
                        val batch = attempt.drain(queue)
                        if (closed) throw SchedulePausedException()
                        requests++
                        inspected += batch.map { it.postKey }
                    }.onFailure { fail("control flow reached generic logging") }
                    checked += current.postKey
                    break
                } catch (_: SchedulePausedException) {
                    waiting = true
                    resume.await()
                    closed = false
                }
            }
        }
        queue.addOrReplace(earlier)
        yield()
        assertTrue(waiting)
        assertTrue(job.isActive)
        assertEquals(0, requests)
        assertFalse(current.postKey in checked)
        assertTrue(queue.shouldFlush())
        resume.complete(Unit)
        job.await()
        assertEquals(1, requests)
        assertEquals(setOf(earlier.postKey, current.postKey), inspected.toSet())
        assertTrue(current.postKey in checked)
    }

    @Test fun cancellationBeforeDispatchRestoresOversizeWithoutTouchingNewGeneration() {
        val oldQueue = queue()
        val newerQueue = queue()
        val old = item("1")
        val newer = item("9")
        oldQueue.addOrReplace(old)
        newerQueue.addOrReplace(newer)
        val cancelled = CancellationException("old job stopped")
        var requests = 0
        var checked = false
        val attempt = AiOuterStageAttempt()
        try {
            runAiOuterStage(attempt) {
                attempt.drain(oldQueue, old.postKey)
                throw cancelled
                @Suppress("UNREACHABLE_CODE")
                requests++
            }
            checked = true
            fail("cancellation swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertEquals(0, requests)
        assertFalse(checked)
        assertEquals(listOf(old), oldQueue.drainFlushable())
        assertEquals(listOf(newer), newerQueue.drainFlushable())
    }

    @Test fun interruptedCheckedCandidateBypassesUnchangedDbSkipUntilDrained() {
        val queue = queue()
        val candidate = item("1")
        queue.addOrReplace(candidate)
        assertFalse(queue.needsInterruptedRetry(candidate.postKey))
        val attempt = AiOuterStageAttempt()
        try {
            runAiOuterStage(attempt) {
                attempt.drain(queue)
                throw SchedulePausedException()
            }
        } catch (_: SchedulePausedException) { }
        assertTrue("checked unchanged candidate still needs AI", queue.needsInterruptedRetry(candidate.postKey))
        assertFalse(shouldRecheckPost(0, 0, "title", "title"))
        assertTrue(queue.needsInterruptedRetry(candidate.postKey) || shouldRecheckPost(0, 0, "title", "title"))
        assertTrue(queue.shouldFlush(nowMs = 1L))
        assertFalse(queue().needsInterruptedRetry(candidate.postKey))
        assertEquals(listOf(candidate), queue.drainFlushable())
        assertFalse(queue.needsInterruptedRetry(candidate.postKey))
    }

    @Test fun ordinaryProviderFailureKeepsExistingPolicy() {
        val queue = queue()
        queue.addOrReplace(item("1"))
        val failure = IllegalStateException("provider failure")
        val attempt = AiOuterStageAttempt()
        val result = runAiOuterStage(attempt) {
            attempt.drain(queue)
            throw failure
        }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(queue.isEmpty())
    }

    @Test fun serviceUsesBoundaryAroundDrainAndBeforeCheckedCompletion() {
        val source = java.io.File("src/main/java/com/example/armbandbot/BotService.kt").readText().replace("\r\n", "\n")
        assertTrue(source.contains("val interruptedAiRetry = aiBatchQueues[botId]?.needsInterruptedRetry("))
        assertTrue(source.contains("if (!interruptedAiRetry && !shouldRecheckPost("))
        val stage = source.substringAfter("if (shouldRunAiStage) {\n            if (config.isDebugMode")
            .substringBefore("var dbBlockReason:")
        assertTrue(stage.contains("runAiOuterStage(aiStageAttempt) {"))
        assertTrue(stage.contains("aiStageAttempt.drain(queue,"))
        assertTrue(stage.indexOf("aiStageAttempt.drain(queue,") < stage.indexOf("requireActiveScheduleForRequest(botId)"))
        assertFalse(stage.contains("queue.drainFlushable()"))
    }
}
