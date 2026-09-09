package com.heyheyon.armbandbot

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** Cancelled is not completed: blocking IO and non-cancellable finalizers still own DB work. */
internal class DatabaseWorkRegistry {
    private val jobs = ConcurrentHashMap<Job, String>()
    fun register(job: Job, botId: String = "") {
        jobs[job] = botId
        job.invokeOnCompletion { jobs.remove(job) }
    }
    fun hasUnfinishedWork(botId: String? = null): Boolean =
        jobs.any { (job, owner) -> !job.isCompleted && (botId == null || botId == owner) }
}
