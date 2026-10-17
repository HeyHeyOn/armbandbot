package com.heyheyon.armbandbot

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** Bound with asContextElement for the lifetime of one bot generation. */
internal class RuntimeRequestGate(val owner: Job, val spacing: BotRequestSpacing? = null, private val scheduleActive: () -> Boolean) {
    fun check() {
        owner.ensureActive()
        if (!scheduleActive()) throw SchedulePausedException()
        owner.ensureActive()
    }
    fun <T> dispatch(request: () -> T): T { check(); return request() }
    fun beforeRequest() { check(); spacing?.awaitSlot(::check); check() }
    companion object {
        val context = ThreadLocal<RuntimeRequestGate?>()
        fun requireCurrent(): RuntimeRequestGate = checkNotNull(context.get()) { "Missing bot request gate" }
    }
}

/** Shared by the parent and its recommendation worker; no lock is held during network I/O. */
internal class BotRequestSpacing(private val enabled: () -> Boolean,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = Thread::sleep) {
    private var lastStart: Long? = null
    fun awaitSlot(check: () -> Unit) {
        while (true) {
            check()
            val remaining = synchronized(this) {
                val now = clock()
                val remaining = if (enabled()) lastStart?.let { 1000 - (now - it) } ?: 0 else 0
                if (remaining <= 0) lastStart = now
                remaining
            }
            if (remaining <= 0) return
            sleep(remaining.coerceAtMost(100))
        }
    }
}

/** Invoke inside the maintenance lock, never before waiting for it. */
internal fun <T> gatedClaimNow(beforeAcquire: () -> Unit, clock: () -> Long, acquire: (Long) -> T): T {
    beforeAcquire()
    return acquire(clock())
}
