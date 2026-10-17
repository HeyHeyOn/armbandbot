package com.heyheyon.armbandbot

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RecommendationGuardAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = PostKey("MI", "fixture", "1")

    @Test fun sideMonitorDetectsWhileParentWorkWaitsAndStopsOnDisable() = runBlocking {
        val p = context.getSharedPreferences("recommend_android", Context.MODE_PRIVATE)
        p.edit().clear().putBoolean(RECOMMEND_GUARD_ENABLED, true).putInt(RECOMMEND_GUARD_SECONDS, 30).putInt(RECOMMEND_GUARD_COUNT, 10).commit()
        var now = 0L
        val scans = AtomicInteger(); val releases = AtomicInteger()
        val logs = mutableListOf<String>()
        val complete = CompletableDeferred<Unit>()
        val monitor = RecommendationMonitor(p, { true }, scan = { observer ->
            val count = scans.getAndIncrement() * 6L
            observer.consume(listOf(RecommendationRow(key, count, "", "1.2", "닉")), now) { false }
            now += 6_000
        }, release = { _, check ->
            check(); releases.incrementAndGet()
            p.edit().putBoolean(RECOMMEND_GUARD_ENABLED, false).commit()
            complete.complete(Unit)
            "개념글 해제 성공"
        }, log = { logs.add(it) }, clock = { now })
        val parentWork = launch { delay(120_000) }
        val child = launch {
            val gate = RuntimeRequestGate(currentCoroutineContext().job) { true }
            withContext(RuntimeRequestGate.context.asContextElement(gate)) { monitor.run() }
        }
        try {
            withTimeout(15_000) { complete.await() }
            assertTrue(parentWork.isActive)
            assertEquals(1, releases.get())
            val atDisable = scans.get(); delay(1500)
            assertEquals(atDisable, scans.get()); assertEquals(1, releases.get())
            assertTrue(logs.single().contains("12초 동안 0→12"))
        } finally { child.cancelAndJoin(); parentWork.cancelAndJoin(); p.edit().clear().commit() }
    }

    @Test fun changedPolicyClearsOldBaselineAndProtectedRowsNeverRelease() = runBlocking {
        val p = context.getSharedPreferences("recommend_android_policy", Context.MODE_PRIVATE)
        p.edit().clear().putBoolean(RECOMMEND_GUARD_ENABLED, true).putInt(RECOMMEND_GUARD_SECONDS, 30).commit()
        var now = 0L; var scans = 0; var releaseCount = 0
        val complete = CompletableDeferred<Unit>()
        val monitor = RecommendationMonitor(p, { true }, scan = { observer ->
            val current = scans++
            observer.consume(listOf(RecommendationRow(key, if(current == 0) 0 else 100, "", "1.2", "닉")), now) { current >= 2 }
            if (current == 0) p.edit().putInt(RECOMMEND_GUARD_COUNT, 20).commit()
            if (current >= 2) { p.edit().putBoolean(RECOMMEND_GUARD_ENABLED, false).commit(); complete.complete(Unit) }
            now += 6_000
        }, release = { _, _ -> releaseCount++; "unexpected" }, log = {}, clock = { now })
        val child = launch {
            withContext(RuntimeRequestGate.context.asContextElement(RuntimeRequestGate(currentCoroutineContext().job) { true })) { monitor.run() }
        }
        try { withTimeout(15_000) { complete.await() }; assertEquals(0, releaseCount) }
        finally { child.cancelAndJoin(); p.edit().clear().commit() }
    }

    @Test fun actualPreferenceBackupPreservesCriteriaWithoutActivatingNewBot() {
        val p = context.getSharedPreferences("recommend_android_backup", Context.MODE_PRIVATE)
        try {
            p.edit().clear().putBoolean(RECOMMEND_GUARD_ENABLED, true).putInt(RECOMMEND_GUARD_SECONDS, 75).putInt(RECOMMEND_GUARD_COUNT, 24).commit()
            val backup = BotSettingsExport(botName = "기준 유지", strings = emptyMap(),
                booleans = mapOf(RECOMMEND_GUARD_ENABLED to p.getBoolean(RECOMMEND_GUARD_ENABLED, false)),
                ints = mapOf(RECOMMEND_GUARD_SECONDS to p.getInt(RECOMMEND_GUARD_SECONDS, 0), RECOMMEND_GUARD_COUNT to p.getInt(RECOMMEND_GUARD_COUNT, 0)),
                floats = emptyMap(), stringSets = emptyMap())
            val restored = prepareImportedSettingsForNewBot(parseAndMigrateBotSettingsExport(backup.toJson()))
            assertEquals(75, restored[RECOMMEND_GUARD_SECONDS]); assertEquals(24, restored[RECOMMEND_GUARD_COUNT])
            assertEquals(false, restored[RECOMMEND_GUARD_ENABLED])
            assertEquals(RecommendationPolicy(75, 24), recommendationPolicy(snapshotScanPreferences(p)))
        } finally { p.edit().clear().commit() }
    }
}
