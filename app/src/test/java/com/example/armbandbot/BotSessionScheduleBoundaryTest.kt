package com.heyheyon.armbandbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class BotSessionScheduleBoundaryTest {
    @Test fun initialValidationPauseWaitsAndRetriesWithoutLoginRequired() = verifyPause(false)
    @Test fun recoveryPauseWaitsAndRetriesWithoutLoginRequired() = verifyPause(true)

    private fun verifyPause(duringRecovery: Boolean) = runBlocking {
        val resume = CompletableDeferred<Unit>()
        var active = true
        var waiting = false
        var requests = 0
        var loginRequired = false
        var running = true
        var restore = true
        var attempts = 0
        val job = async {
            val cookie = retrySessionAfterSchedulePause<String?>(
                awaitSchedule = {
                    if (!active) {
                        waiting = true
                        resume.await()
                    }
                },
                action = {
                    attempts++
                    // Model the same gate exception at validation or auto-login ingress.
                    if (duringRecovery) requests++ // validation returned invalid
                    if (attempts == 1) {
                        active = false
                        throw SchedulePausedException()
                    }
                    requests++
                    "recovered-cookie"
                },
            )
            if (cookie == null) {
                loginRequired = true
                running = false
                restore = false
            }
            cookie
        }
        yield()
        assertTrue("schedule boundary must suspend rather than finish the bot job", job.isActive)
        assertTrue(waiting)
        assertFalse(loginRequired)
        assertTrue(running)
        assertTrue(restore)
        assertEquals(if (duringRecovery) 1 else 0, requests)
        assertEquals(1, attempts)
        active = true
        resume.complete(Unit)
        assertEquals("recovered-cookie", job.await())
        assertEquals(2, attempts)
        assertFalse(loginRequired)
    }

    @Test fun sessionHelpersDoNotConvertCancellationIntoAuthResults() {
        val source = java.io.File("src/main/java/com/example/armbandbot/BotService.kt").readText()
        for ((start, end) in listOf(
            "private suspend fun isSessionValid(" to "private fun mergeCookieStrings(",
            "private suspend fun tryRecoverSession(" to "private fun getSuspiciousUrl(",
        )) {
            val body = source.substringAfter(start).substringBefore(end)
            assertTrue("$start must propagate cancellation before its generic exception handler",
                Regex("catch \\(cancelled: CancellationException\\)\\s*\\{\\s*throw cancelled\\s*}\\s*catch \\(e: Exception\\)").containsMatchIn(body))
        }
    }

    @Test fun loopUsesTheTestedBoundaryForValidationAndBothRecoveryPaths() {
        val source = java.io.File("src/main/java/com/example/armbandbot/BotService.kt").readText()
        val loop = source.substringAfter("private suspend fun CoroutineScope.runBotLoop(")
            .substringBefore("private suspend fun processTargetUrl(")
        assertEquals(3, Regex("retrySessionAfterSchedulePause\\(").findAll(loop).count())
        assertEquals(3, Regex("awaitSchedule = \\{ awaitActiveSchedule\\(botId, botPref\\) }").findAll(loop).count())
    }

    @Test fun genuineAuthenticationFailureRemainsNull() = runBlocking {
        assertNull(retrySessionAfterSchedulePause<String?>(awaitSchedule = {}, action = { null }))
    }

    @Test fun cancellationPropagatesWithoutWaitingOrRetrying() = runBlocking {
        val cancellation = CancellationException("stop bot")
        var gates = 0
        var attempts = 0
        try {
            retrySessionAfterSchedulePause<String?>(
                awaitSchedule = { gates++ },
                action = { attempts++; throw cancellation },
            )
            fail("cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(1, gates)
        assertEquals(1, attempts)
    }

    @Test fun cancellationWhileWaitingDoesNotRetryAuth() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        var attempts = 0
        val job = async {
            retrySessionAfterSchedulePause<String?>(
                awaitSchedule = { if (attempts > 0) { waiting.complete(Unit); CompletableDeferred<Unit>().await() } },
                action = { attempts++; throw SchedulePausedException() },
            )
        }
        waiting.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
    }
}
