package com.heyheyon.armbandbot

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in, fresh emulator only. Real foreground service, HTTP, and Room claims; no runner/claim mocks. */
class PostAutomationServiceLiveTest {
    @Test fun serviceMovesOwnedPostAndExecutesNaturalScheduleWithoutDuplicateClaims() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(InstrumentationRegistry.getArguments().getString("automation_live") == "authorized_fixture")
        val fixtureFile = File(context.filesDir, "automation_live_fixture.json")
        val cookieFile = File(context.filesDir, "automation_live_cookie.txt")
        val fixture = JSONObject(fixtureFile.readText())
        val cookie = cookieFile.readText()
        check(fixture.getString("scope") == "laboratory1")
        val marker = fixture.getString("marker")
        check(marker.startsWith("AB154-AUTO-"))
        val key = strictAutomationPost(fixture.getString("url")).key
        check(key.gallType == "M" && key.gallId == "laboratory1" && key.postNo == fixture.getString("post_no"))
        val master = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
        check(master.getString("bot_ids_list", "").orEmpty().isBlank()) { "Fresh isolated guest required" }
        val stamp = System.currentTimeMillis()
        val primaryId = "owned154_a_$stamp"
        val secondId = "owned154_b_$stamp"
        val ids = listOf(primaryId, secondId)
        val preferences = ids.associateWith { context.getSharedPreferences("bot_prefs_$it", Context.MODE_PRIVATE) }
        val primary = preferences.getValue(primaryId)
        val second = preferences.getValue(secondId)
        val cycles = ConcurrentHashMap<String, AtomicInteger>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getStringExtra("BOT_ID") ?: return
                if (intent.getStringExtra("LOG_MSG").orEmpty().contains("사이클 완료!")) {
                    cycles.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                }
            }
        }
        fun await(description: String, timeout: Long = 45000L, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeout
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(500)
            }
            error("$description; phase=${primary.getString("last_startup_phase", "")}; automation=${primary.getString("automation_last_status", "")}")
        }
        fun document() = Jsoup.connect(DcinsidePostUrls.canonicalDetailUrl(key))
            .header("Cookie", cookie).userAgent("Mozilla/5.0").timeout(15000).get().also {
                check(it.select(".title_subject").text().contains(marker))
                check(it.select("#no").attr("value") == key.postNo)
                check(it.select("#gallery_id").attr("value") == "laboratory1")
            }
        fun tab() = document().select(".title_headtext").text()
        fun restoreOwnedTab() {
            if (tab() == "[일반]") return
            val response = Jsoup.connect(nativeActionUrl(key, "chg_headtext"))
                .header("Cookie", cookie).header("Referer", DcinsidePostUrls.canonicalDetailUrl(key))
                .header("X-Requested-With", "XMLHttpRequest").userAgent("Mozilla/5.0")
                .data(nativeMovePayload(key, dcCookieToken(cookie), 0)).ignoreContentType(true)
                .timeout(15000).post().text()
            check(JSONObject(response).getString("result") == "success")
            check(tab() == "[일반]")
        }
        fun command(id: String, action: String) {
            val intent = Intent(context, BotService::class.java).setAction(action).putExtra("BOT_ID", id)
            if (action == "START") intent.putExtra("COOKIE", cookie)
            instrumentation.runOnMainSync {
                if (action == "START") ContextCompat.startForegroundService(context, intent) else context.startService(intent)
            }
        }
        var activity: Activity? = null
        val report = JSONObject().put("scope", "laboratory1").put("post_no", key.postNo)
            .put("real_bot_service", true).put("mock_transport", false).put("mock_claims", false)
        val output = File(context.getExternalFilesDir(null), "automation-service-live.json")
        try {
            await("isolated guest can resolve and reach the owned fixture", 60000L) {
                try {
                    document()
                    true
                } catch (_: UnknownHostException) {
                    false
                } catch (_: ConnectException) {
                    false
                }
            }
            val control = document()
            check(automationHasBumpControl(control))
            check(automationTabs(control).contains(GalleryCategory(10, "테스트")))
            restoreOwnedTab()
            GlobalBotState.initDb(context)
            val database = checkNotNull(GlobalBotState.getDb())
            fun claims(): List<List<String>> = database.openHelper.readableDatabase.query(
                "SELECT postNum,actionKind,status,actorBotId,claimedAt,finishedAt FROM moderation_action_claims WHERE actorBotId IN (?,?) ORDER BY actionKind",
                arrayOf(primaryId, secondId)
            ).use { cursor -> buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it).orEmpty() }) } }
            val rule = TabMoveRule("owned_live_move", "M", "laboratory1", marker, 10, "테스트", false)
            val opposite = TabMoveRule("owned_live_opposite", "M", "laboratory1", marker, 0, "일반", false)
            check(master.edit().putString("bot_ids_list", ids.joinToString(",")).commit())
            preferences.forEach { (id, pref) ->
                check(pref.edit().clear().putString("bot_name", "실동작 시험")
                    .putString("target_urls", automationGalleryUrl("M" to "laboratory1"))
                    .putString("saved_cookie", cookie).putBoolean("is_running", false)
                    .putBoolean("auto_login_enabled", false).putBoolean("is_search_mode", true)
                    .putString(orderedMultilineTextKey("search_keywords"), marker)
                    .putStringSet("search_keywords", setOf(marker))
                    .putBoolean("is_user_filter_mode", false).putBoolean("is_nickname_filter_mode", false)
                    .putBoolean("is_ai_filter_mode", false).putBoolean("gallery_setting_refresh_enabled", false)
                    .putBoolean("is_snapshot_all", false).putBoolean("is_snapshot_blocked", false)
                    .putBoolean("is_debug_mode", false).putBoolean("delete_post_on_block", false)
                    .putBoolean("noti_master", false).putInt("scan_page_count", 1)
                    .putFloat("delay_post_min_sec", 1f).putFloat("delay_post_max_sec", 1.1f)
                    .putFloat("delay_page_min_sec", 1f).putFloat("delay_page_max_sec", 1.1f)
                    .putFloat("delay_cycle_min_sec", 5f).putFloat("delay_cycle_max_sec", 5.1f)
                    .putBoolean(BUMP_ENABLED_KEY, false).putBoolean(REMOTE_ENABLED_KEY, false)
                    .putBoolean(MOVE_ENABLED_KEY, true)
                    .putString(MOVE_RULES_KEY, encodeTabMoveRules(listOf(if (id == primaryId) rule else opposite))).commit())
            }
            persistOrderedMultilineText(primary, "block_exempt_post_numbers", key.postNo)
            ContextCompat.registerReceiver(context, receiver, IntentFilter("BOT_LOG_EVENT"), ContextCompat.RECEIVER_NOT_EXPORTED)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            command(primaryId, "START")
            await("exempt post first real service cycle") { (cycles[primaryId]?.get() ?: 0) >= 1 }
            val visited = database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM checked_posts WHERE gallType='M' AND gallId='laboratory1' AND postNum=?", arrayOf(key.postNo)
            ).use { it.moveToFirst(); it.getInt(0) }
            assertTrue("Real service must have visited the owned fixture", visited > 0)
            assertEquals("[일반]", tab())
            assertTrue(claims().isEmpty())
            report.put("exempt_post_unchanged_after_real_cycle", true)
            persistOrderedMultilineText(primary, "block_exempt_post_numbers", "")
            check(primary.edit()
                .putString(MOVE_RULES_KEY, encodeTabMoveRules(listOf(rule.copy(id = "owned_live_move_after_exempt")))).commit())
            await("real service category move persisted") { claims().any { it[1].startsWith("MOVE:") && it[2] == "SUCCEEDED" } }
            assertEquals("[테스트]", tab())
            report.put("native_move_readback", true)
            val moveBefore = claims().filter { it[1].startsWith("MOVE:") }
            check(primary.edit().putBoolean(MOVE_ENABLED_KEY, false).commit())
            command(secondId, "START")
            await("second bot completes two real scan cycles") { (cycles[secondId]?.get() ?: 0) >= 2 }
            assertEquals("Disabling first bot must not let competing bot undo its move", "[테스트]", tab())
            assertEquals(moveBefore, claims().filter { it[1].startsWith("MOVE:") })
            report.put("disabled_first_bot_no_conflicting_move", true)
            command(secondId, "STOP")
            await("second bot stopped") { !second.getBoolean("is_running", true) }
            val now = System.currentTimeMillis()
            val scheduledAt = (now / 60000L + 2L) * 60000L
            val scheduledLocal = Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault())
            val minute = scheduledLocal.hour * 60 + scheduledLocal.minute
            val occurrence = "${scheduledLocal.toLocalDate()}T${formatMinuteOfDay(minute)}"
            check(primary.edit().putBoolean(MOVE_ENABLED_KEY, true).putBoolean(BUMP_ENABLED_KEY, true)
                .putString(BUMP_RULES_KEY, encodeBumpRules(listOf(BumpRule("owned_natural_clock", fixture.getString("url"), listOf(minute))))).commit())
            report.put("scheduled_epoch_ms", scheduledAt).put("scheduled_local", scheduledLocal.toString())
            val cycleBaseline = cycles[primaryId]?.get() ?: 0
            await("schedule active before due clock") { (cycles[primaryId]?.get() ?: 0) >= cycleBaseline + 2 }
            check(System.currentTimeMillis() < scheduledAt) { "Missed pre-schedule observation window" }
            assertFalse(claims().any { it[1].startsWith("BUMP:") })
            report.put("not_executed_before_scheduled_time", true)
            await("natural-clock bump via foreground service", scheduledAt - System.currentTimeMillis() + 90000L) {
                claims().any { it[1] == "BUMP:$occurrence" && it[2] == "SUCCEEDED" }
            }
            val bump = claims().single { it[1] == "BUMP:$occurrence" }
            assertTrue(bump[4].toLong() >= scheduledAt)
            val list = Jsoup.connect(automationGalleryUrl("M" to "laboratory1"))
                .header("Cookie", cookie).userAgent("Mozilla/5.0").timeout(15000).get()
            val first = list.select("tr.ub-content").firstOrNull {
                it.attr("data-type") !in setOf("icon_notice", "icon_slow") && it.attr("data-no").matches(Regex("[1-9][0-9]*"))
            }
            assertEquals(key.postNo, first?.attr("data-no"))
            assertEquals("[테스트]", tab())
            report.put("scheduled_bump_success", true).put("actual_claimed_epoch_ms", bump[4].toLong())
                .put("server_top_post_verified", true)
            val beforeRestart = claims()
            command(primaryId, "STOP")
            await("primary stopped") { !primary.getBoolean("is_running", true) }
            Thread.sleep(1000)
            val restartBaseline = cycles[primaryId]?.get() ?: 0
            command(primaryId, "START")
            await("service restart completes two real cycles") { (cycles[primaryId]?.get() ?: 0) >= restartBaseline + 2 }
            assertEquals(beforeRestart, claims())
            assertEquals(1, claims().count { it[1].startsWith("BUMP:") })
            assertEquals(1, claims().count { it[1].startsWith("MOVE:") })
            assertTrue(claims().all { it[0] == key.postNo && it[1].let { kind -> kind.startsWith("MOVE:") || kind.startsWith("BUMP:") } })
            report.put("service_restart_no_duplicate_claims", true)
                .put("only_owned_post_actions", true).put("claims", JSONArray(claims())).put("passed", true)
            output.writeText(report.toString(2))
        } finally {
            ids.forEach { id -> runCatching { command(id, "STOP") } }
            Thread.sleep(1000)
            instrumentation.runOnMainSync { context.stopService(Intent(context, BotService::class.java)); activity?.finish() }
            instrumentation.waitForIdleSync()
            runCatching { context.unregisterReceiver(receiver) }
            preferences.values.forEach { it.edit().clear().commit() }
            master.edit().clear().commit()
            try {
                restoreOwnedTab()
                report.put("owned_tab_restored", true)
            } finally {
                cookieFile.delete(); fixtureFile.delete()
                report.put("guest_session_files_removed", !cookieFile.exists() && !fixtureFile.exists())
                output.writeText(report.toString(2))
            }
        }
    }
}
