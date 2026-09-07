package com.heyheyon.armbandbot

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class DatabaseMaintenanceLock {
    private val delegate = ReentrantLock(true)

    val isFair: Boolean
        get() = delegate.isFair

    fun <T> withLock(block: () -> T): T = delegate.withLock(block)
}
