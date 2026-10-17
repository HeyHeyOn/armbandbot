package com.heyheyon.armbandbot

import android.content.*
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in: one owned Test60 post. No voting; detector samples below are explicitly synthetic. */
class RecommendationGuardLiveTest {
    @Test fun realServiceObservesDuringMainWaitAndOrdinaryPostIsNotReleased() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureFile = File(context.filesDir, "recommend_fixture.json")
        org.junit.Assume.assumeTrue("Explicit owned live fixture required", fixtureFile.isFile)
        val fixture = JSONObject(fixtureFile.readText())
        check(fixture.getString("gallery") == "armbandbot" && fixture.getInt("headtext") == 60)
        val key = PostKey("MI", "armbandbot", fixture.getString("post"))
        val marker = fixture.getString("marker"); check(marker == "AB171B3")
        val cookie = fixture.getString("cookie"); check(cookie.isNotBlank())
        val url = DcinsidePostUrls.canonicalDetailUrl(key)
        fun doc() = Jsoup.connect(url).header("Cookie", cookie).userAgent("Mozilla/5.0").timeout(20000).get()
        fun requireOwned(d:org.jsoup.nodes.Document) {
            check(d.select(".title_subject").text() == fixture.getString("title"))
            check(d.select(".title_headtext").text().contains("테스트"))
            check(d.select(".write_div").text().contains("소유 시험 글"))
        }
        val initial = doc(); requireOwned(initial)
        check(recommendationState(initial, key) == RecommendationState.ORDINARY)
        val master = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
        check(master.getString("bot_ids_list", "").orEmpty().isBlank())
        val bot = "recommend_owned_probe"
        val p = context.getSharedPreferences("bot_prefs_$bot", Context.MODE_PRIVATE)
        val cycles = AtomicInteger(); val observations = AtomicInteger()
        val receiver = object:BroadcastReceiver() {
            override fun onReceive(c:Context?, intent:Intent?) {
                if (intent?.getStringExtra("BOT_ID") != bot) return
                val message = intent.getStringExtra("LOG_MSG").orEmpty()
                if (message.contains("사이클 완료!")) cycles.incrementAndGet()
                if (message.contains("[디버그][추천수] 독립 관측 완료")) observations.incrementAndGet()
            }
        }
        val report = JSONObject().put("post", key.postNo).put("scope", "armbandbot/Test60")
            .put("votes_submitted", 0).put("detector_counts_synthetic", true)
        val output = File(context.getExternalFilesDir(null), "recommend-live.json")
        fun save() { output.writeText(report.toString(2)) }
        var activity:android.app.Activity? = null
        ContextCompat.registerReceiver(context, receiver, IntentFilter("BOT_LOG_EVENT"), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            p.edit().clear().putString("bot_name", "추천수 독립 관측 시험").putString("saved_cookie", cookie)
                .putString("target_urls", "https://gall.dcinside.com/mini/board/lists/?id=armbandbot&search_head=60")
                .putBoolean("independent_scan_state_enabled", true).putBoolean("is_search_mode", true)
                .putString("search_keywords_text", marker).putStringSet("search_keywords", setOf(marker))
                .putBoolean("auto_login_enabled", false).putBoolean("is_debug_mode", true)
                .putBoolean("is_word_filter_mode", false).putBoolean("is_user_filter_mode", false)
                .putBoolean("is_nickname_filter_mode", false).putBoolean("is_ai_filter_mode", false)
                .putBoolean("noti_master", false).putBoolean("gallery_setting_refresh_enabled", false)
                .putBoolean(RECOMMEND_GUARD_ENABLED, true).putInt(RECOMMEND_GUARD_SECONDS, 180)
                .putInt(RECOMMEND_GUARD_COUNT, 1_000_000).putInt("scan_page_count", 1)
                .putFloat("delay_cycle_min_sec", 120f).putFloat("delay_cycle_max_sec", 120f).commit()
            master.edit().putString("bot_ids_list", bot).commit()
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { ContextCompat.startForegroundService(context,
                Intent(context, BotService::class.java).setAction("START").putExtra("BOT_ID", bot)) }
            withTimeout(115_000) { while (cycles.get() < 1 || observations.get() < 2) delay(500) }
            check(cycles.get() == 1) { "Independent observation did not precede next main cycle" }
            report.put("actual_service_main_cycles", cycles.get()).put("actual_service_independent_observations", observations.get()); save()
            instrumentation.runOnMainSync { context.startService(Intent(context, BotService::class.java).setAction("STOP").putExtra("BOT_ID", bot)) }
            withTimeout(15_000) { while (p.getBoolean("is_running", true)) delay(250) }
            delay(2000); val stoppedCount = observations.get(); delay(5000); check(observations.get() == stoppedCount)
            report.put("parent_stop_stops_observer", true); save()
            val tracker = RecommendationTracker()
            val row = RecommendationRow(key, 0, "", "", "")
            check(tracker.observe(row, 0, RecommendationPolicy()) == null)
            checkNotNull(tracker.observe(row.copy(count = 10), 120_000, RecommendationPolicy()))
            val gate = RuntimeRequestGate(currentCoroutineContext().job, BotRequestSpacing({ true })) { true }
            withContext(Dispatchers.IO + RuntimeRequestGate.context.asContextElement(gate)) {
                var sent = 0
                val client = RecommendationReleaseClient(cookie, beforeRequest = { gate.beforeRequest() }, claim = { target, kind, action ->
                    check(target == key && kind == "RECOMMEND_RELEASE")
                    val claim = ModerationActionClaim(target.gallType, target.gallId, target.postNo, "POST", target.postNo,
                        kind, bot, UUID.randomUUID().toString(), ClaimStatus.PENDING.name, System.currentTimeMillis())
                    check(GlobalBotState.acquireModerationClaim(claim, System.currentTimeMillis(), MODERATION_CLAIM_LEASE_MS, 600_000))
                    sent++
                    val result = action()
                    check(GlobalBotState.finalizeModerationClaim(claim, classifyModerationClaimStatus(result), System.currentTimeMillis()))
                    result
                })
                val result = client.release(key, verifyPolicy = { gate.check() }, protected = { d -> requireOwned(d); false })
                check(result == "ordinary") { result }
                check(sent == 0)
                val after = doc(); requireOwned(after); check(recommendationState(after, key) == RecommendationState.ORDINARY)
                report.put("actual_rel_requests", sent).put("actual_recommended_release_tested", false).put("result", result).put("post_preserved", true).put("passed", true); save()
            }
        } finally {
            instrumentation.runOnMainSync {
                context.startService(Intent(context, BotService::class.java).setAction("STOP").putExtra("BOT_ID", bot)); activity?.finish()
            }
            context.unregisterReceiver(receiver); fixtureFile.delete(); p.edit().clear().commit(); master.edit().clear().commit()
            save()
        }
    }
}
