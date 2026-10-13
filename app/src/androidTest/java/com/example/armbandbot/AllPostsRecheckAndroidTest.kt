package com.heyheyon.armbandbot

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual service configuration and comment analysis on Android; no requests or moderation actions. */
class AllPostsRecheckAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)

    @Test fun nextCycleUsesNewFlagAndKeywordsEvenForUnchangedSavedPosts() {
        val p = context.getSharedPreferences("bot_prefs_all_recheck_runtime", Context.MODE_PRIVATE)
        val service = BotService()
        val load = BotService::class.java.getDeclaredMethod("loadBotConfig", String::class.java, SharedPreferences::class.java).apply { isAccessible = true }
        val analyze = BotService::class.java.declaredMethods.single { it.name == "analyzeComment" }.apply { isAccessible = true }
        fun config(prefs: SharedPreferences = snapshotScanPreferences(p)) = load.invoke(service, "all_recheck_runtime", prefs)!!
        fun bad(config: Any, memo: String): Boolean {
            val result = analyze.invoke(service, config, "", "fixture-user", "fixture", "fixture-user", "", memo, mutableMapOf<String, Pair<Int, Int>>(), "", "")!!
            return field(result, "isBadComment") as Boolean
        }
        try {
            p.edit().clear().commit()
            val previousSnapshot = snapshotScanPreferences(p)
            val previous = config(previousSnapshot)
            assertEquals(false, field(previous, "allPostsRecheckEveryCycle"))
            assertFalse(bad(previous, "토-^스 생수 광고"))
            val state = AutomationRecheckState().apply { update(automationPolicyRevision(previousSnapshot)) }
            val key = PostKey("MI", "fixture", "1")
            state.mark(key)
            persistOrderedMultilineText(p, "normal", "6g6")
            persistOrderedMultilineText(p, "bypass", "토스")
            p.edit().putBoolean(ALL_POSTS_RECHECK_KEY, true).commit()
            assertEquals(false, field(config(previousSnapshot), "allPostsRecheckEveryCycle"))
            repeat(3) {
                val snapshot = snapshotScanPreferences(p)
                val current = config(snapshot)
                state.update(automationPolicyRevision(snapshot))
                assertFalse(state.needs(key)) // Reinspection does not rely on a changed policy revision.
                assertTrue(shouldRecheckPost(1, 1, "동일 제목", "동일 제목",
                    allPostsRecheckEveryCycle = field(current, "allPostsRecheckEveryCycle") as Boolean))
                assertTrue(bad(current, "토-^스 생수 광고"))
                assertTrue(bad(current, "https://6g6.eu/fixture"))
                assertFalse(bad(current, "평범한 댓글"))
            }
            p.edit().putBoolean("is_user_filter_mode", true).commit()
            persistOrderedMultilineText(p, "user_whitelist", "fixture-user")
            assertFalse(bad(config(), "토-^스 생수 광고"))
            p.edit().putBoolean(ALL_POSTS_RECHECK_KEY, false).commit()
            assertFalse(shouldRecheckPost(1, 1, "동일 제목", "동일 제목",
                allPostsRecheckEveryCycle = field(config(), "allPostsRecheckEveryCycle") as Boolean))
        } finally { p.edit().clear().commit() }
    }

    @Test fun actualSettingsFileRoundTripPreservesNewFlagAndPumChoice() {
        val sourceId = "all_recheck_backup"
        val p = context.getSharedPreferences("bot_prefs_$sourceId", Context.MODE_PRIVATE)
        val master = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
        val originalIds = master.getString("bot_ids_list", null)
        val file = File(context.cacheDir, "all-recheck-backup.json")
        val restoredIds = mutableListOf<String>()
        try {
            for (enabled in listOf(false, true)) {
                p.edit().clear().putBoolean(ALL_POSTS_RECHECK_KEY, enabled)
                    .putBoolean("pum_recheck_every_cycle", !enabled).putInt("scan_page_count", 2).commit()
                writeBotSettingsJson(context, Uri.fromFile(file).toString(), exportBotSettings(context, sourceId))
                val id = importBotSettingsAsNewBot(context, Uri.fromFile(file).toString()).also { restoredIds.add(it) }
                val restored = context.getSharedPreferences("bot_prefs_$id", Context.MODE_PRIVATE)
                assertEquals(enabled, restored.getBoolean(ALL_POSTS_RECHECK_KEY, !enabled))
                assertEquals(!enabled, restored.getBoolean("pum_recheck_every_cycle", enabled))
                assertEquals(2, restored.getInt("scan_page_count", 0))
                assertFalse(restored.getBoolean("is_running", true))
            }
        } finally {
            p.edit().clear().commit()
            restoredIds.forEach { context.getSharedPreferences("bot_prefs_$it", Context.MODE_PRIVATE).edit().clear().commit() }
            master.edit().apply { if (originalIds == null) remove("bot_ids_list") else putString("bot_ids_list", originalIds) }.commit()
            file.delete()
        }
    }
}
