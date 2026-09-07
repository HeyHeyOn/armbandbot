package com.heyheyon.armbandbot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ClaimRuntimeGateTest {
    @Test fun `claim rechecks gate after maintenance lock wait`() {
        for (cancel in listOf(false, true)) {
            val lock = DatabaseMaintenanceLock()
            val owner = Job()
            var active = true
            val gate = RuntimeRequestGate(owner) { active }
            val waiting = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            var acquired = 0
            lateinit var worker: Thread
            lock.withLock {
                worker = thread {
                    try {
                        gate.check()
                        waiting.countDown()
                        lock.withLock { gatedClaimNow(gate::check, { 10L }) { acquired++ } }
                    } catch (error: Throwable) { failure.set(error) }
                }
                assertTrue(waiting.await(2, TimeUnit.SECONDS))
                if (cancel) owner.cancel() else active = false
            }
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(0, acquired)
            assertTrue(if (cancel) failure.get() is CancellationException else failure.get() is SchedulePausedException)
        }
    }
    @Test fun `claim samples timestamp only after lock and gate`() {
        var now = 1L
        val result = DatabaseMaintenanceLock().withLock {
            now = 10L
            gatedClaimNow({ assertEquals(10L, now) }, { now }) { it }
        }
        assertEquals(10L, result)
    }
}
