package com.heyheyon.armbandbot

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No MainActivity, services, credentials, singleton DB replacement, or user preferences. */
class DbDashboardScopeUiTest {
    // Reset resumes after Dispatchers.IO and shows a Toast; use Android's main
    // dispatcher rather than the test rule's unconfined effect dispatcher.
    @OptIn(ExperimentalTestApi::class)
    @get:Rule val compose = createComposeRule(kotlinx.coroutines.Dispatchers.Main)

    @Test fun realDashboardTabsFilterAndExactResetPreservesOtherRecordsAndSharedSnapshots() = exerciseDashboard(false)

    @Test fun injectedDashboardDisablesSingletonBackupAndPreservesCacheOnAllReset() = exerciseDashboard(true)

    private fun exerciseDashboard(checkIsolation: Boolean) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "dashboard_fixture_${UUID.randomUUID()}_"
        val prefs = mutableSetOf<String>()
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(prefix + name, mode).also { prefs.add(prefix + name) }
            override fun getCacheDir() = root
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.postDao()
        val snapshots = File(root, "snapshots_fixture").apply { mkdirs() }
        val shared = File(snapshots, "same_initial.html").apply { writeText("fixture") }
        val latest = File(snapshots, "same_latest.html").apply { writeText("fixture latest") }
        val exclusive = File(snapshots, "exclusive_initial.html").apply { writeText("fixture exclusive") }
        fun row(scope: String, title: String, path: String) = CheckedPost(gallType = "M", gallId = "fixture-gallery", postNum = "7", commentCount = 0, scopeId = scope, title = title, snapshotPath = path)
        val global = row(GLOBAL_SCAN_SCOPE, "global fixture title", shared.path)
        val privateRow = row("fixture-private", "private fixture title", latest.path)
        dao.insertOrUpdate(global); dao.insertOrUpdate(privateRow)
        dao.insertOrUpdate(global.copy(postNum = "8", title = "exclusive fixture title", snapshotPath = exclusive.path))
        dao.insertBlockHistory(BlockHistory(gallType = "M", gallId = "fixture-gallery", postNum = "7", targetType = "POST", targetNo = "", targetAuthor = "fixture", targetContent = "fixture", blockReason = "fixture", actorBotId = "fixture-private", snapshotPath = shared.path))
        dao.insertHoldHistory(HoldHistory(gallType = "M", gallId = "fixture-gallery", postNum = "7", targetType = "POST", targetNo = "", targetAuthor = "fixture", targetContent = "fixture", holdReason = "fixture", actorBotId = "fixture-private", snapshotPath = shared.path))
        val claim = ModerationActionClaim(gallType = "M", gallId = "fixture-gallery", postNum = "7", targetType = "POST", targetNo = "", actionKind = "BLOCK", actorBotId = "fixture-private", ownerToken = "fixture", status = ClaimStatus.PENDING.name, claimedAt = 1)
        db.moderationClaimDao().insertIfAbsent(claim)
        context.getSharedPreferences("bot_master", 0).edit().putString("bot_ids_list", "fixture-private,fixture-empty").commit()
        for (id in listOf("fixture-private", "fixture-empty")) context.getSharedPreferences("bot_prefs_$id", 0).edit().putString("bot_name", if (id == "fixture-private") "P" else "E").putBoolean("independent_scan_state_enabled", true).commit()
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    error("Injected dashboard must not launch singleton backup activities")
                }
            }
        }
        try {
            compose.setContent { CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registryOwner) { MaterialTheme { DbDashboardScreen(GLOBAL_SCAN_SCOPE, {}, database = db) } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("제목: global fixture title").fetchSemanticsNodes().isNotEmpty() }
            if (checkIsolation) {
                compose.onNodeWithText("백업").assertIsNotEnabled()
                GlobalBotState.lastCheckedNumbers[prefix] = 731
                try {
                    compose.onNodeWithText("초기화").performClick()
                    compose.onNode(hasText("초기화") and hasAnyAncestor(isDialog())).performClick()
                    compose.waitUntil(10_000) {
                        dao.getAllPostsForBackupMerge().isEmpty() &&
                            compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
                    }
                    assertEquals(731, GlobalBotState.lastCheckedNumbers[prefix])
                } finally {
                    GlobalBotState.lastCheckedNumbers.remove(prefix)
                }
                return
            }
            val all = compose.onNodeWithText("전체").fetchSemanticsNode().boundsInRoot
            val common = compose.onNodeWithText("공용").fetchSemanticsNode().boundsInRoot
            val gallery = compose.onNodeWithText("전체 갤러리").fetchSemanticsNode().boundsInRoot
            assertTrue(all.left < common.left)
            assertTrue(gallery.bottom <= all.top)
            val privateLabel = dashboardScopeOptions(listOf(DashboardScopeBot("fixture-private", "P", true)), emptySet())[2].label
            val privateTab = compose.onNodeWithText(privateLabel)
            assertTrue(common.left < privateTab.fetchSemanticsNode().boundsInRoot.left)
            privateTab.performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("제목: private fixture title").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("제목: global fixture title").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("공용").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("제목: private fixture title").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithText("제목: global fixture title").fetchSemanticsNodes().isNotEmpty() }
            // Exercise the real scope callback while a confirmation is pending; a modal
            // blocks pointer input, so retain its semantics action before opening it.
            val switchToPrivate = privateTab.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.onNodeWithText("초기화").performClick()
            compose.onNodeWithText("조치 기록과 다른 검사 범위는 보존됩니다.", substring = true).assertExists()
            compose.runOnIdle { switchToPrivate() }
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            assertEquals(2, dao.getPostsForScope(GLOBAL_SCAN_SCOPE).size)
            compose.onNodeWithText("전체").performClick()
            compose.onNodeWithText("초기화").performClick()
            compose.onNodeWithText("전체 DB를 초기화할까요?", substring = true).assertExists()
            compose.onNodeWithText("취소").performClick()
            compose.onNodeWithText("fixture-gallery").performClick()
            compose.onNodeWithText("공용").performClick()
            compose.onNodeWithText("초기화").performClick()
            compose.onNode(hasText("초기화") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(10_000) {
                dao.getPostsForScope(GLOBAL_SCAN_SCOPE).isEmpty() && !exclusive.exists() &&
                    compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("fixture-gallery").assertExists()
            privateTab.performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("제목: private fixture title").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("fixture-gallery").assertIsSelected()
            compose.onNodeWithText("백업").assertIsNotEnabled()
            assertEquals(listOf(privateRow), dao.getPostsForScope("fixture-private"))
            assertEquals(1, dao.getBlockHistoryForActor("fixture-private").size)
            assertEquals(1, dao.getHoldHistoryForActor("fixture-private").size)
            assertEquals(claim, db.moderationClaimDao().find("M", "fixture-gallery", "7", "POST", "", "BLOCK"))
            assertTrue(shared.exists()); assertTrue(latest.exists()); assertFalse(exclusive.exists())
            compose.onNodeWithText("차단 상세 기록").performClick()
            compose.onNodeWithText("공용").assertDoesNotExist()
        } finally {
            compose.waitForIdle()
            db.close(); root.deleteRecursively(); prefs.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
