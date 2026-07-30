package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersistenceServiceContractTest {
    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/armbandbot/$name"),
            File("app/src/main/java/com/example/armbandbot/$name"),
        )
        return (candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Source not found: $name; cwd=${File(".").absolutePath}"))
            .replace("\r\n", "\n")
    }

    @Test
    fun foregroundPromotionUsesDeclaredSpecialUseTypeOnAndroid14() {
        val service = source("BotService.kt")

        assertTrue(service.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
        assertTrue(service.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
    }

    @Test
    fun serviceLifecyclePublishesWhetherItsInstanceExists() {
        val service = source("BotService.kt")

        assertTrue(service.contains("fun isServiceCreated(): Boolean"))
        assertTrue(service.contains("serviceCreated = true"))
        assertTrue(service.contains("serviceCreated = false"))
    }

    @Test
    fun watchdogRestoresOnlyWhenRestorableServiceIsMissing() {
        val receiver = source("AutoRestartReceiver.kt")

        assertTrue(receiver.contains("decideRecoveryAction("))
        assertTrue(receiver.contains("BotService.hasAllRestorableBotsEnteredRunLoop(context)"))
        assertTrue(receiver.contains("RecoveryDecision.DEFER_AND_REARM"))
        assertTrue(receiver.contains("allowImmediateStart = false"))
        assertTrue(receiver.contains("showRecoveryNotification(context)"))
    }

    @Test
    fun serviceCapturesAndReportsPreviousProcessExit() {
        val service = source("BotService.kt")

        assertTrue(service.contains("ProcessExitDiagnostics.captureLatestHistoricalExit(this)"))
        assertTrue(service.contains("ProcessExitDiagnostics.latestSummary(this)"))
        assertTrue(service.contains("[종료 진단] 이전 프로세스 종료:"))
        assertTrue(service.contains("last_reported_exit_timestamp"))
    }

    @Test
    fun taskRemovalAndDestructionLeaveLifecycleEvidence() {
        val service = source("BotService.kt")

        assertTrue(service.contains("override fun onTaskRemoved(rootIntent: Intent?)"))
        assertTrue(service.contains("recordLifecycleEvent(this, \"task_removed\")"))
        assertTrue(service.contains("recordLifecycleEvent(this, \"service_destroyed\")"))
    }

    @Test
    fun bootReceiverArmsFallbackBeforeImmediateRestore() {
        val receiver = source("BootReceiver.kt")

        assertTrue(receiver.contains("allowImmediateStart = true"))
        assertFalse(receiver.contains("allowImmediateStart = false"))
        val fallbackIndex = receiver.indexOf("val fallback = AutoRestartReceiver.scheduleWatchdog(context)")
        val failureIndex = receiver.indexOf("if (!fallback.scheduled)", fallbackIndex)
        val notificationIndex = receiver.indexOf("AutoRestartReceiver.showRecoveryNotification(context)", failureIndex)
        val restoreIndex = receiver.indexOf("requestRestoreRunningBots(")
        assertTrue(fallbackIndex >= 0)
        assertTrue(failureIndex > fallbackIndex)
        assertTrue(notificationIndex > failureIndex)
        assertTrue(restoreIndex > notificationIndex)
        assertFalse(receiver.substring(notificationIndex, restoreIndex).contains("return"))
    }

    @Test
    fun pendingRestoreIsClearedOnlyAfterServiceJobAcknowledgement() {
        val activity = source("MainActivity.kt")
        val service = source("BotService.kt")

        assertFalse(activity.contains("masterPref.edit().putBoolean(\"pending_restore_after_boot\", false)"))
        assertTrue(service.contains("acknowledgeRestoreSuccess("))
        assertTrue(service.contains("shouldAcknowledgeRestore("))
        assertTrue(service.contains("putBoolean(\"pending_restore_after_boot\", false)"))
    }

    @Test
    fun botJobIsRegisteredBeforeLazyCoroutineStarts() {
        val service = source("BotService.kt")

        assertTrue(service.contains("launch(start = CoroutineStart.LAZY)"))
        val registerIndex = service.indexOf("activeBots[botId] = job")
        val startIndex = service.indexOf("job.start()", registerIndex)
        val runLoopFunctionIndex = service.indexOf("private suspend fun CoroutineScope.runBotLoop(")
        val initializationIndex = service.indexOf("GlobalBotState.startSnapshotWorker(this)", runLoopFunctionIndex)
        val enteredIndex = service.indexOf("runLoopEnteredJobs[botId] = currentJob", initializationIndex)
        val acknowledgeIndex = service.indexOf("acknowledgeRestoreSuccess(botId)", enteredIndex)
        val loopIndex = service.indexOf("while (isActive)", acknowledgeIndex)
        assertTrue(registerIndex >= 0)
        assertTrue(startIndex > registerIndex)
        assertTrue(runLoopFunctionIndex > startIndex)
        assertTrue(initializationIndex > runLoopFunctionIndex)
        assertTrue(enteredIndex > initializationIndex)
        assertTrue(acknowledgeIndex > enteredIndex)
        assertTrue(loopIndex > acknowledgeIndex)
    }

    @Test
    fun staleJobFinalizerCannotRemoveReplacementGeneration() {
        val service = source("BotService.kt")

        assertTrue(service.contains("completedJob: Job"))
        assertTrue(service.contains("removeIfCurrentGeneration(activeBots, botId, completedJob)"))
        assertTrue(service.contains("removeIfCurrentGeneration(runLoopEnteredJobs, botId, completedJob)"))
    }

    @Test
    fun finalizationIsSerializedWithServiceStartStopLifecycle() {
        val service = source("BotService.kt")

        assertTrue(service.contains("withContext(NonCancellable + Dispatchers.Main.immediate)"))
        assertTrue(service.contains("stopServiceWhenNoActiveBots("))
    }

    @Test
    fun stoppingFinalBotExplicitlyStopsForegroundService() {
        val service = source("BotService.kt")
        val stopBranchIndex = service.indexOf("if (action == \"STOP\")")
        val activeEmptyIndex = service.indexOf("if (activeBots.isEmpty())", stopBranchIndex)
        val stopHelperIndex = service.indexOf("stopServiceWhenNoActiveBots(", activeEmptyIndex)

        assertTrue(stopBranchIndex >= 0)
        assertTrue(activeEmptyIndex > stopBranchIndex)
        assertTrue(stopHelperIndex > activeEmptyIndex)
    }

    @Test
    fun definitiveCancellationClearsPendingRestoreState() {
        val receiver = source("AutoRestartReceiver.kt")
        val service = source("BotService.kt")

        assertTrue(receiver.contains("clearPendingRestoreState(context)"))
        assertTrue(service.contains("clearPendingRestoreState(this)"))
    }

    @Test
    fun restoreRequestsPersistSuccessAndFailureDiagnostics() {
        val activity = source("MainActivity.kt")

        assertTrue(activity.contains("recordLifecycleEvent(context, \"restore_requested\""))
        assertTrue(activity.contains("ProcessExitDiagnostics.recordLifecycleEvent("))
        assertTrue(activity.contains("\"restore_failed\""))
        assertTrue(activity.contains("recordLifecycleEvent(context, \"restore_deferred\""))
    }

    @Test
    fun watchdogNoLongerUsesPerMinuteExactAlarms() {
        val receiver = source("AutoRestartReceiver.kt")

        assertTrue(receiver.contains("PERSISTENCE_WATCHDOG_INTERVAL_MS"))
        assertFalse(receiver.contains("WATCHDOG_INTERVAL_MS = 60_000L"))
        assertFalse(receiver.contains("setExactAndAllowWhileIdle("))
        assertFalse(receiver.contains("setExact("))
    }
}
