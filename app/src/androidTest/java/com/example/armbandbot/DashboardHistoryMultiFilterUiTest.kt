package com.heyheyon.armbandbot

import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardHistoryMultiFilterUiTest {
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createComposeRule(kotlinx.coroutines.Dispatchers.Main)

    private fun fixture(body: (ContextWrapper, AppDatabase) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "history_multi_${UUID.randomUUID()}_"
        val names = mutableSetOf<String>()
        val root = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode).also { names.add(prefix + name) }
            override fun getCacheDir() = root
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try { body(context, db) } finally { compose.waitForIdle(); db.close(); root.deleteRecursively(); names.forEach { base.deleteSharedPreferences(it) } }
    }
    private fun launch(context: ContextWrapper, db: AppDatabase, dependencies: DashboardLoaderDependencies = DefaultDashboardLoaderDependencies) {
        val registry = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { error("Fixture cannot launch external activity") }
            }
        }
        compose.setContent { CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registry) {
            MaterialTheme { DbDashboardScreen(GLOBAL_SCAN_SCOPE, {}, db, dependencies) }
        } }
    }
    private fun action(scope: String, gallery: String, no: String, time: Long) = BlockHistory(gallType = "M", gallId = gallery, postNum = no, targetType = "POST", targetNo = no,
        targetAuthor = "same actor", targetContent = "action-$no", blockReason = "fixture", blockTime = time, actorBotId = "same-bot", scopeId = scope)
    private fun hold(scope: String, gallery: String, no: String, time: Long) = HoldHistory(gallType = "M", gallId = gallery, postNum = no, targetType = "POST", targetNo = no,
        targetAuthor = "same actor", targetContent = "hold-$no", holdReason = "fixture", holdTime = time, actorBotId = "same-bot", scopeId = scope)

    @Test fun actionOnlyInventoryTwoDbTwoGalleryFiltersBeforeLimitAndSelectedResetIsolated() = fixture { context, db ->
        val dao = db.postDao()
        dao.insertOrUpdate(CheckedPost("M", "g3", "seed", 0, title = "seed", scopeId = "outside"))
        repeat(105) { i -> dao.insertBlockHistory(action("outside", "g3", "excluded-$i", 999L + i)); dao.insertHoldHistory(hold("outside", "g3", "excluded-$i", 999L + i)) }
        dao.insertBlockHistory(action("a", "g1", "keep-a", 2)); dao.insertBlockHistory(action("b", "g2", "keep-b", 1))
        dao.insertHoldHistory(hold("a", "g1", "keep-a", 2)); dao.insertHoldHistory(hold("b", "g2", "keep-b", 1))
        dao.insertBlockHistory(action("a", "g3", "wrong-gallery", 900)); dao.insertHoldHistory(hold("a", "g3", "wrong-gallery", 900))
        launch(context, db)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("제목: seed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("DB").assertExists()
        compose.onNodeWithTag("record-filter-button").performClick()
        compose.selectOnlyRecordFilter("scope", "a")
        compose.onNodeWithTag("record-filter-scope-b").performScrollTo().performClick().assertIsOn()
        compose.selectOnlyRecordFilter("gallery", "g1")
        compose.onNodeWithTag("record-filter-gallery-g2").performScrollTo().performClick().assertIsOn()
        saveBeta4UiEvidence("db-gallery-multiselection")
        compose.onNodeWithTag("record-filter-apply").performClick()
        for ((tab, prefix) in listOf("차단 상세 기록" to "action", "보류 기록" to "hold")) {
            compose.onNodeWithText(tab).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("$prefix-keep-a", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("$prefix-keep-b", substring = true).assertExists()
            compose.onNodeWithText("공용 조치 이력", substring = true).assertDoesNotExist()
            compose.onNodeWithText("검사 범위 선택과 무관", substring = true).assertDoesNotExist()
            compose.onNodeWithText("$prefix-wrong-gallery", substring = true).assertDoesNotExist()
            compose.onNodeWithText("$prefix-excluded-104", substring = true).assertDoesNotExist()
            compose.onNodeWithTag("record-filter-button").assertIsDisplayed()
            saveBeta4UiEvidence("filtered-$prefix-history")
        }
        compose.onNodeWithText("초기화").performClick()
        compose.onNodeWithTag("db-reset-next").assertIsNotEnabled()
        compose.onNodeWithTag("db-reset-selected").performClick()
        compose.onNodeWithTag("db-reset-next").assertIsNotEnabled()
        compose.onNodeWithTag("db-reset-scope-a").performScrollTo().performClick()
        compose.onNodeWithTag("db-reset-scope-b").performScrollTo().performClick()
        compose.onNodeWithTag("db-reset-next").performClick()
        compose.onNodeWithTag("db-reset-frozen-targets").assertExists()
        saveBeta4UiEvidence("frozen-selected-reset-confirmation")
        compose.onNode(hasText("초기화") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { dao.getAllBlockHistoryForBackupMerge().size == 105 && dao.getAllHoldHistoryForBackupMerge().size == 105 }
        assertEquals(setOf("outside"), dao.getHistoryScopeIds().toSet())
        assertEquals(1, dao.getPostCount())
    }

    @Test fun legacyHistoryShowsUnknownDbRatherThanPretendingCurrentScope() = fixture { context, db ->
        db.postDao().insertOrUpdate(CheckedPost("M", "g1", "seed", 0, title = "legacy-seed", scopeId = GLOBAL_SCAN_SCOPE))
        db.postDao().insertBlockHistory(action(LEGACY_ACTOR_BOT_ID, "g1", "legacy", 1))
        db.postDao().insertHoldHistory(hold(LEGACY_ACTOR_BOT_ID, "g1", "legacy", 1))
        launch(context, db)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("제목: legacy-seed").fetchSemanticsNodes().isNotEmpty() }
        for (tab in listOf("차단 상세 기록", "보류 기록")) {
            compose.onNodeWithText(tab).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("same actor", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("DB · 이전 기록 (DB 범위 미상)").assertExists()
            saveBeta4UiEvidence("legacy-$tab")
        }
    }

    @Test fun earlyResetAllIncludesHistoryBeforeGeneralInventoryLoads() = fixture { context, db ->
        val general = kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        db.postDao().insertBlockHistory(action(LEGACY_ACTOR_BOT_ID, "g1", "legacy", 1))
        db.postDao().insertHoldHistory(hold("deleted-bot", "g1", "deleted", 1))
        launch(context, db, object : DashboardLoaderDependencies {
            override suspend fun <T> generalWork(request: DashboardScopeRequest, version: Int, query: String, work: suspend () -> T): T {
                entered.set(true)
                general.await()
                return work()
            }
        })
        try {
            compose.waitUntil(10_000) { entered.get() }
            compose.onNodeWithText("초기화").performClick()
            compose.onNodeWithTag("db-reset-all").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("db-reset-next") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("db-reset-next").performClick()
            compose.onNodeWithTag("db-reset-frozen-targets").assertTextContains("이전 기록 (DB 범위 미상)", substring = true)
            compose.onNodeWithTag("db-reset-frozen-targets").assertTextContains("삭제된 봇", substring = true)
        } finally { general.complete(Unit) }
    }

    @Test fun delayedResetInventoryFailureBlocksNextRetryReadsAllAndConfirmationStaysFrozen() = fixture { context, db ->
        val general = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        val retryRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val generalFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val dao = db.postDao()
        context.getSharedPreferences("bot_master", 0).edit().putString("bot_ids_list", "known").commit()
        context.getSharedPreferences("bot_prefs_known", 0).edit().putString("bot_name", "Original known").putBoolean("independent_scan_state_initialized", true).commit()
        dao.insertBlockHistory(action(LEGACY_ACTOR_BOT_ID, "g1", "legacy", 1))
        dao.insertHoldHistory(hold("deleted-bot", "g1", "deleted", 1))
        launch(context, db, object : DashboardLoaderDependencies {
            override suspend fun <T> generalWork(request: DashboardScopeRequest, version: Int, query: String, work: suspend () -> T): T {
                general.await()
                return work().also { generalFinished.set(true) }
            }
            override suspend fun resetHistoryScopeIds(dao: PostDao): Set<String> {
                if (reads.incrementAndGet() == 1) {
                    firstRead.await()
                    error("fixture inventory read failure")
                }
                retryRead.await()
                return dao.getHistoryScopeIds().toSet()
            }
        })
        try {
            compose.onNodeWithText("초기화").performClick()
            compose.waitUntil(10_000) { reads.get() == 1 }
            compose.onNodeWithTag("db-reset-all").performClick()
            compose.onNodeWithTag("db-reset-inventory-loading").assertExists()
            compose.onNodeWithTag("db-reset-next").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("db-reset-confirmation").assertDoesNotExist()
            firstRead.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("db-reset-inventory-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("db-reset-next").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("db-reset-confirmation").assertDoesNotExist()
            compose.onNodeWithTag("db-reset-inventory-retry").performClick()
            compose.waitUntil(10_000) { reads.get() == 2 }
            compose.onNodeWithTag("db-reset-next").assertIsNotEnabled()
            dao.insertOrUpdate(CheckedPost("M", "g1", "retry", 0, title = "retry", scopeId = "retry-scope"))
            retryRead.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("db-reset-next") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("db-reset-selected").performClick()
            listOf(GLOBAL_SCAN_SCOPE, "known", LEGACY_ACTOR_BOT_ID, "deleted-bot", "retry-scope").forEach {
                compose.onNodeWithTag("db-reset-scope-$it").performScrollTo().assertExists()
            }
            compose.onNodeWithTag("db-reset-all").performScrollTo().performClick()
            compose.onNodeWithTag("db-reset-next").performClick()
            compose.onNodeWithTag("db-reset-frozen-targets").assertTextContains("Original known", substring = true)
            val frozenText = compose.onNodeWithTag("db-reset-frozen-targets").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
            dao.insertBlockHistory(action("later-db", "g1", "later", 2))
            context.getSharedPreferences("bot_prefs_known", 0).edit().putString("bot_name", "Renamed known").commit()
            general.complete(Unit)
            compose.waitUntil(10_000) { generalFinished.get() }
            compose.waitForIdle()
            assertEquals(frozenText, compose.onNodeWithTag("db-reset-frozen-targets").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString())
            compose.onNode(hasText("초기화") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) { dao.getHistoryScopeIds().toSet() == setOf("later-db") }
            assertEquals(2, reads.get())
        } finally { firstRead.complete(Unit); retryRead.complete(Unit); general.complete(Unit) }
    }

    @Test fun frozenAllPreservesLaterDbAndClaimsAndRejectsInFlightWork() = fixture { context, db ->
        val dao = db.postDao()
        dao.insertBlockHistory(action("a", "g1", "old", 1))
        val target = freezeDashboardResetTarget(true, emptySet(), dao.getHistoryScopeIds().toSet())
        dao.insertBlockHistory(action("later-db", "g2", "later", 2))
        val claim = ModerationActionClaim("M", "g1", "old", "POST", "old", "BLOCK", "same-bot", "fixture-owner", ClaimStatus.PENDING.name, 1)
        db.moderationClaimDao().insertIfAbsent(claim)
        try { resetDashboardRecords(db, context.cacheDir, target) { false }; fail("in-flight work must reject reset") } catch (_: IllegalStateException) { }
        assertEquals(2, dao.getAllBlockHistoryForBackupMerge().size)
        resetDashboardRecords(db, context.cacheDir, target) { true }
        assertEquals("later-db", dao.getAllBlockHistoryForBackupMerge().single().scopeId)
        assertEquals(claim, db.moderationClaimDao().find("M", "g1", "old", "POST", "old", "BLOCK"))
    }
}
