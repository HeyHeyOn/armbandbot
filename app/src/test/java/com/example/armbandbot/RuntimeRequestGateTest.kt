package com.heyheyon.armbandbot

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

class RuntimeRequestGateTest {
    @Test fun `fresh boundary blocks next request but preserves in flight result`() {
        var now = 9
        val gate = RuntimeRequestGate(Job()) { now < 10 }
        val calls = mutableListOf<String>()
        val result = gate.dispatch { calls += "snapshot"; now = 10; "recorded" }
        assertEquals("recorded", result)
        assertThrows(SchedulePausedException::class.java) { gate.dispatch { calls += "comments" } }
        assertEquals(listOf("snapshot"), calls)
    }

    @Test fun `cancelled old generation cannot claim or dispatch when replacement is active`() {
        val old = Job()
        val replacement = Job()
        val gate = RuntimeRequestGate(old) { true }
        var calls = 0
        old.cancel()
        assertTrue(replacement.isActive)
        for (operation in listOf("claim", "block", "deleteComment", "deletePost", "AI", "Gallog")) {
            assertThrows(CancellationException::class.java) { gate.dispatch { calls++ } }
        }
        assertEquals(0, calls)
    }

    @Test fun `cancellation during claim blocks destructive dispatch`() {
        val job = Job()
        val gate = RuntimeRequestGate(job) { true }
        var requests = 0
        gate.dispatch { job.cancel() }
        assertThrows(CancellationException::class.java) { gate.dispatch { requests++ } }
        assertEquals(0, requests)
    }

    @Test fun `context binding survives suspension and restores previous binding`() = runBlocking {
        val gate = RuntimeRequestGate(Job()) { false }
        withContext(RuntimeRequestGate.context.asContextElement(gate)) {
            yield()
            assertThrows(SchedulePausedException::class.java) { RuntimeRequestGate.requireCurrent().dispatch { fail("sent") } }
        }
        assertNull(RuntimeRequestGate.context.get())
    }

    private fun fixture(name: String) = javaClass.getResource("/pum/$name")!!.readText()
    private fun checkPumBoundary(stopAfter: Int, cancelled: Boolean, redirect: Boolean) {
        val job = Job()
        var active = stopAfter != 0
        if (cancelled && stopAfter == 0) job.cancel()
        val gate = RuntimeRequestGate(job) { active }
        var calls = 0
        val resolver = PumSourceResolver(
            http = PumHttpClient {
                calls++
                if (calls == stopAfter) { if (cancelled) job.cancel() else active = false }
                val isRedirect = redirect && calls == stopAfter
                val text = if (calls == 1) fixture("pum_card_resolved.html") else fixture("source_detail.html")
                PumHttpResponse(if (isRedirect) 307 else 200,
                    if (isRedirect) mapOf("Location" to it.url) else emptyMap(),
                    text.toByteArray().size.toLong(), ByteArrayInputStream(text.toByteArray()))
            },
            beforeRequest = gate::check,
        )
        val url = "https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=900"
        assertThrows(if (cancelled) CancellationException::class.java else SchedulePausedException::class.java) {
            resolver.resolve(Jsoup.parse(fixture("pum_detail.html"), url), url, true)
        }
        assertEquals(stopAfter, calls)
    }

    @Test fun `PUM card observes schedule and cancellation`() {
        checkPumBoundary(0, false, false); checkPumBoundary(0, true, false)
    }
    @Test fun `PUM source observes schedule and cancellation after card`() {
        checkPumBoundary(1, false, false); checkPumBoundary(1, true, false)
    }
    @Test fun `PUM card redirects observe schedule and cancellation`() {
        checkPumBoundary(1, false, true); checkPumBoundary(1, true, true)
    }
    @Test fun `PUM source redirects observe schedule and cancellation`() {
        checkPumBoundary(2, false, true); checkPumBoundary(2, true, true)
    }
}
