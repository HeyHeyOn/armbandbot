package com.heyheyon.armbandbot

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** Bound with asContextElement for the lifetime of one bot generation. */
internal class RuntimeRequestGate(val owner: Job, private val scheduleActive: () -> Boolean) {
    fun check() {
        owner.ensureActive()
        if (!scheduleActive()) throw SchedulePausedException()
        owner.ensureActive()
    }
    fun <T> dispatch(request: () -> T): T { check(); return request() }
    companion object {
        val context = ThreadLocal<RuntimeRequestGate?>()
        fun requireCurrent(): RuntimeRequestGate = checkNotNull(context.get()) { "Missing bot request gate" }
    }
}

/** Invoke inside the maintenance lock, never before waiting for it. */
internal fun <T> gatedClaimNow(beforeAcquire: () -> Unit, clock: () -> Long, acquire: (Long) -> T): T {
    beforeAcquire()
    return acquire(clock())
}
