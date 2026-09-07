package com.heyheyon.armbandbot

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AiRuntimeRequestGateTest {
    @Test fun `AI transport does not automatically redirect outside request gate`() {
        val source = java.io.File("src/main/java/com/example/armbandbot/AiFilter.kt").readText()
        assertTrue(source.contains("instanceFollowRedirects = false"))
    }
    @Test fun `AI batch dispatch rechecks fresh schedule and propagates pause`() = runBlocking {
        verifyBlocked(false)
    }
    @Test fun `AI batch dispatch observes cancelled generation and propagates cancellation`() = runBlocking {
        verifyBlocked(true)
    }
    private suspend fun verifyBlocked(cancel: Boolean) {
        AiFilterClient.clearCacheForTest()
        val job = Job()
        var active = true
        var calls = 0
        val gate = RuntimeRequestGate(job) { active }
        val client = AiFilterClient(
            AiFilterConfig(enabled = true, provider = AiFilterProvider.LM_STUDIO,
                endpoint = "http://localhost", apiKey = "", model = "test", userPrompt = "", reviewMode = false,
                debugLoggingEnabled = true),
            logger = { if (cancel) job.cancel() else active = false },
            apiCaller = { _, _ -> calls++; "{}" },
            beforeRequest = gate::check,
        )
        val request = AiFilterBatchRequest(listOf(AiFilterPostInput(
            PostKey("M", "test", "1"), "title", "author", "nick", "body", emptyList(), emptyList())))
        assertThrows(if (cancel) CancellationException::class.java else SchedulePausedException::class.java) {
            client.evaluateBatch(request)
        }
        assertEquals(0, calls)
    }
}
