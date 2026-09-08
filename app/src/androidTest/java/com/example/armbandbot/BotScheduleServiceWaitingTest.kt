package com.heyheyon.armbandbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real started-FGS smoke for API 24 and 35; run with the debug test manifest.
 * Its empty ComponentActivity supplies foreground eligibility without MainActivity's
 * migrations, restoration or notification-permission UI. Never adds a real bot-list entry.
 * Empty targets AND empty credentials keep this fixture network-free even if the gate breaks.
 * Auth assertions use existing debug/broadcast markers, not a mocked HTTP client or auth spy.
 */
@RunWith(AndroidJUnit4::class)
class BotScheduleServiceWaitingTest {
    @Test
    fun scheduledWaitingRetainsLiveWatchdogJobUntilExplicitStop() = verifyWaiting(false)

    @Test fun corruptCanonicalRetainsLiveJobUntilExplicitStop() = verifyWaiting(true)

    private fun verifyWaiting(corrupt: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Do not stop or interfere with a user's already-running service.
        assumeFalse("Requires an idle service on the test device", BotService.isServiceCreated())
        val botId = "instrumentation_waiting_${UUID.randomUUID()}"
        val prefName = "bot_prefs_$botId"
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val messages = CopyOnWriteArrayList<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra("BOT_ID") == botId) {
                    messages.add(intent.getStringExtra("LOG_MSG").orEmpty())
                }
            }
        }
        var scenario: ActivityScenario<ComponentActivity>? = null
        var receiverRegistered = false
        var startRequested = false
        var stopAcknowledged = false
        var waitingJob: Job? = null
        var service: BotService? = null
        fun command(action: String) = Intent(context, BotService::class.java)
            .setAction(action)
            .putExtra("BOT_ID", botId)
            .putExtra("COOKIE", "")

        try {
            val now = ZonedDateTime.now()
            val minute = now.hour * 60 + now.minute
            // Unsorted windows bracket the current minute with a real union gap.
            val schedule = BotRunSchedule(true, listOf(
                BotRunWindow((minute + 60) % 1440, (minute + 120) % 1440),
                BotRunWindow((minute + 1320) % 1440, (minute + 1380) % 1440),
            ))
            assertEquals(ScheduleState.WAITING,
                evaluateSchedule(System.currentTimeMillis(), now.zone, schedule).state)
            assertTrue(prefs.edit()
                .putString("bot_name", "Instrumentation WAITING (no network)")
                .putString("target_urls", "")
                .putString("saved_cookie", "")
                .putString("auto_login_id", "")
                .putString("auto_login_pw", "")
                .putBoolean("auto_login_enabled", false)
                .putBoolean("is_debug_mode", true)
                .putBoolean("independent_scan_state_enabled", true)
                .putBoolean("run_schedule_enabled", true)
                // Legacy mirror deliberately covers NOW: only canonical loading may gate this fixture.
                .putInt("run_schedule_start_minute", (minute + 1380) % 1440)
                .putInt("run_schedule_end_minute", (minute + 60) % 1440)
                .putString(RUN_SCHEDULE_WINDOWS_JSON_KEY, if (corrupt) "broken" else encodeBotRunWindows(schedule.windows))
                .commit())
            ContextCompat.registerReceiver(context, receiver, IntentFilter("BOT_LOG_EVENT"),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario.onActivity { activity ->
                startRequested = true
                ContextCompat.startForegroundService(activity, command("START"))
            }
            await("actual service run loop and WAITING marker; logs=$messages") {
                prefs.getString("last_startup_phase", "") == "run_loop_entered" &&
                    messages.any { it.contains("[예약 대기]") }
            }
            if (corrupt) assertTrue(messages.any { it.contains("시간대 설정 오류") })
            service = currentService()
            assertNotNull("Started Android service must still exist", service)
            val instance = requireNotNull(service)
            waitingJob = jobs(instance, "activeBots")[botId]
            val job = requireNotNull(waitingJob) { "WAITING must retain its actual Job" }
            // Same identity check as hasAllRestorableBotsEnteredRunLoop, without changing
            // the real bot list merely to make the test bot eligible for global restoration.
            assertSame(job, jobs(instance, "runLoopEnteredJobs")[botId])
            assertTrue(job.isActive)
            assertTrue(prefs.getBoolean("is_running", false))
            assertTrue(prefs.getBoolean("should_restore_after_restart", false))

            val observationDeadline = SystemClock.elapsedRealtime() + 2_000L
            while (SystemClock.elapsedRealtime() < observationDeadline) {
                assertTrue("Scheduled wait must not finish/cancel the Job", job.isActive)
                assertSame(job, jobs(instance, "activeBots")[botId])
                assertSame(job, jobs(instance, "runLoopEnteredJobs")[botId])
                assertTrue(prefs.getBoolean("is_running", false))
                assertNoAuthOrCycle(messages)
                SystemClock.sleep(50L)
            }

            scenario.onActivity { it.startService(command("STOP")) }
            await("manual STOP must cancel and complete the suspended waiting Job") {
                job.isCancelled && job.isCompleted &&
                    !prefs.getBoolean("is_running", true) &&
                    !prefs.getBoolean("should_restore_after_restart", true) &&
                    jobs(instance, "activeBots")[botId] == null &&
                    jobs(instance, "runLoopEnteredJobs")[botId] == null
            }
            stopAcknowledged = true
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertNoAuthOrCycle(messages)
            assertFalse(prefs.contains("auto_login_last_failure_at"))
        } finally {
            try {
                if (startRequested && !stopAcknowledged) {
                    // Targeted STOP only: never stopService(), clear a database, or clear bot_master.
                    context.startService(command("STOP"))
                    await("fixture STOP cleanup") {
                        val instance = service ?: currentService()
                        val job = waitingJob ?: instance?.let { jobs(it, "activeBots")[botId] }
                        !prefs.getBoolean("is_running", false) &&
                            (job == null || job.isCompleted) &&
                            (instance == null || jobs(instance, "activeBots")[botId] == null)
                    }
                }
            } finally {
                if (receiverRegistered) context.unregisterReceiver(receiver)
                scenario?.close()
                // This UUID preference file belongs exclusively to this invocation.
                assertTrue("Remove only fixture preferences", context.deleteSharedPreferences(prefName))
            }
        }
    }

    private fun assertNoAuthOrCycle(messages: List<String>) {
        assertEquals("No session/auth-entry markers are allowed: $messages", 0,
            messages.count {
                it.contains("[세션 진단] runBotLoop 시작") ||
                    it.contains("[자동 로그인]") || it.contains("[자동 로그인 진단]")
            })
        assertFalse("WAITING must not pass the gate into even an empty-target cycle: $messages",
            messages.any { it.contains("대상 URL이 없어") || it.contains("사이클 시작!") ||
                it.contains("[예약 재개]") })
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!predicate()) {
            assertTrue("Timed out: $description", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50L)
        }
    }

    // Read-only observation of existing lifecycle state; no production hook or service substitution.
    private fun currentService(): BotService? = BotService::class.java
        .getDeclaredField("currentInstance").apply { isAccessible = true }.get(null) as? BotService

    @Suppress("UNCHECKED_CAST")
    private fun jobs(service: BotService, field: String): Map<String, Job> = BotService::class.java
        .getDeclaredField(field).apply { isAccessible = true }.get(service) as Map<String, Job>
}
