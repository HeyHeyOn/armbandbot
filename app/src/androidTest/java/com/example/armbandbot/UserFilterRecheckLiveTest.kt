package com.heyheyon.armbandbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in live regression; requires a private owned fixture manifest. Deletes only its Test post. */
class UserFilterRecheckLiveTest {
    @Test fun rechecksIdWhitelistAndHoldToDeleteOnOwnedMiniFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixtureFile = File(context.filesDir, "release162_fixture.json")
        val credentialsFile = File(context.filesDir, "release162_credentials.json")
        org.junit.Assume.assumeTrue("Explicit live fixture required", fixtureFile.isFile && credentialsFile.isFile)
        val fixture = JSONObject(fixtureFile.readText())
        val credentials = JSONObject(credentialsFile.readText())
        check(fixture.getString("scope") == "armbandbot" && fixture.getInt("headtext") == 60)
        val post = fixture.getString("post_id")
        val marker = fixture.getString("marker")
        check(post == "301" && marker == "AB162-20261010")
        val target = fixture.getJSONArray("comments").let { comments ->
            (0 until comments.length()).map { comments.getJSONObject(it) }.single { it.getString("text").contains("TARGET") }
        }
        val targetId = target.getString("uid")
        val targetNo = target.getString("id")
        check(targetId.isNotBlank() && targetId == credentials.getString("username"))
        val master = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
        check(master.getString("bot_ids_list", "").orEmpty().isBlank())
        val bot = "release162_owned_probe"
        val p = context.getSharedPreferences("bot_prefs_$bot", Context.MODE_PRIVATE)
        val cycles = AtomicInteger()
        val logs = CopyOnWriteArrayList<String>()
        val report = JSONObject().put("post_id", post).put("scope", "armbandbot/Test/60")
            .put("version", BuildConfig.VERSION_NAME).put("real_service", true)
        val out = File(context.getExternalFilesDir(null), "release162-live.json")
        fun save() { out.writeText(report.toString(2)) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getStringExtra("BOT_ID") != bot) return
                val text = intent.getStringExtra("LOG_MSG").orEmpty()
                if (!text.contains("cookie", true) && !text.contains("token", true)) logs.add(text)
                if (text.contains("사이클 완료!")) cycles.incrementAndGet()
            }
        }
        fun await(description: String, count: Int, timeout: Long = 90000) {
            val deadline = System.currentTimeMillis() + timeout
            while (cycles.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(250)
            check(cycles.get() >= count) { "$description; cycles=${cycles.get()}; loginRequired=${p.getBoolean("session_login_required", false)}" }
        }
        val url = fixture.getString("url")
        fun cookie() = p.getString("saved_cookie", "").orEmpty().also { check(it.isNotBlank()) }
        fun document() = Jsoup.connect(url).userAgent("Mozilla/5.0").header("Cookie", cookie())
            .timeout(15000).get().also {
                check(it.select(".title_subject").text().contains(marker))
                check(it.select(".title_headtext").text().contains("테스트"))
            }
        fun comments(): JSONArray {
            val doc = document()
            val response = Jsoup.connect("https://gall.dcinside.com/board/comment/")
                .userAgent("Mozilla/5.0").header("Cookie", cookie()).header("Referer", url)
                .header("X-Requested-With", "XMLHttpRequest")
                .data("id", "armbandbot").data("no", post).data("cmt_id", "armbandbot").data("cmt_no", post)
                .data("e_s_n_o", doc.select("input[id=e_s_n_o]").attr("value"))
                .data("comment_page", "1").data("sort", "D").data("_GALLTYPE_", "MI")
                .ignoreContentType(true).timeout(15000).post().text()
            return JSONObject(response).optJSONArray("comments") ?: JSONArray()
        }
        fun summarize(comments: JSONArray) = JSONArray().apply {
            for (i in 0 until comments.length()) {
                val c = comments.getJSONObject(i)
                put(JSONObject().put("no", c.optString("no")).put("memo", c.optString("memo"))
                    .put("uid_matches_target", c.optString("user_id") == targetId)
                    .put("uid_blank", c.optString("user_id").isBlank()).put("ip_blank", c.optString("ip").isBlank())
                    .put("del_yn", c.optString("del_yn")).put("is_delete", c.optString("is_delete"))
                    .put("nicktype", c.optString("nicktype")))
            }
        }
        fun targetPresent(comments: JSONArray) = (0 until comments.length()).any {
            val c = comments.getJSONObject(it)
            c.optString("no") == targetNo && c.optString("memo").contains("TARGET")
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter("BOT_LOG_EVENT"), ContextCompat.RECEIVER_NOT_EXPORTED)
        var activity: android.app.Activity? = null
        try {
            p.edit().clear().putString("bot_name", "댓글 필터 조사")
                .putString("target_urls", "https://gall.dcinside.com/mini/board/lists/?id=armbandbot&search_head=60")
                .putBoolean("independent_scan_state_enabled", true)
                .putBoolean("is_search_mode", true).putString("search_keywords_text", marker).putStringSet("search_keywords", setOf(marker))
                .putBoolean("auto_login_enabled", true).putString("auto_login_id", credentials.getString("username"))
                .putString("auto_login_pw", credentials.getString("password"))
                .putBoolean("is_debug_mode", true).putBoolean("is_user_filter_mode", false)
                .putBoolean("is_nickname_filter_mode", false).putBoolean("is_ai_filter_mode", false)
                .putBoolean("is_snapshot_all", false).putBoolean("is_snapshot_blocked", false)
                .putBoolean("noti_master", false).putBoolean("gallery_setting_refresh_enabled", false)
                .putString("block_process_mode", "DELETE").putBoolean("delete_only_mode", true)
                .putBoolean("delete_post_on_block", false).putInt("scan_page_count", 1)
                .putFloat("delay_post_min_sec", 1f).putFloat("delay_post_max_sec", 1.1f)
                .putFloat("delay_page_min_sec", 1f).putFloat("delay_page_max_sec", 1.1f)
                .putFloat("delay_cycle_min_sec", 4f).putFloat("delay_cycle_max_sec", 4.1f).commit()
            master.edit().putString("bot_ids_list", bot).commit()
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync {
                ContextCompat.startForegroundService(context, Intent(context, BotService::class.java).setAction("START").putExtra("BOT_ID", bot))
            }
            await("initial filter-off scan", 1)
            val db = checkNotNull(GlobalBotState.getDb())
            val inspected = db.openHelper.readableDatabase.query("SELECT gallType,gallId,postNum FROM checked_posts").use { c ->
                buildList { while(c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2))) }
            }
            report.put("initial_checked_posts", JSONArray(inspected))
            check(inspected.isNotEmpty() && inspected.all { it == listOf("MI", "armbandbot", post) }) { "Refusing actions outside exact fixture" }
            check(automationManagerConfirmed(document())) { "Management authority unconfirmed" }
            val before = comments()
            report.put("initial_api_comments", summarize(before)); save()
            check(targetPresent(before)) { "App-equivalent API did not return the target comment" }
            val beforeRevision = automationPolicyRevision(p)
            persistOrderedMultilineText(p, "user_blacklist", targetId)
            persistOrderedMultilineText(p, "user_whitelist", targetId)
            p.edit().putBoolean("is_user_filter_mode", true).putString("block_process_mode", "HOLD").commit()
            check(beforeRevision != automationPolicyRevision(p))
            await("whitelist recheck", cycles.get() + 2)
            check(targetPresent(comments()))
            report.put("whitelist_preserved_target", true); save()
            persistOrderedMultilineText(p, "user_whitelist", "")
            await("whitelist removal recheck in hold mode", cycles.get() + 2)
            check(targetPresent(comments()))
            check(GlobalBotState.hasHoldHistory("MI", "armbandbot", post, "COMMENT", targetNo))
            report.put("hold_record_after_whitelist_removal", true); save()
            p.edit().putBoolean("user_use_custom_action_config", true)
                .putString("user_block_process_mode", "DELETE").putBoolean("user_delete_only_mode", true)
                .putBoolean("user_delete_post_on_block", false).commit()
            await("ID override HOLD to DELETE recheck", cycles.get() + 2)
            val after = comments()
            check(!targetPresent(after))
            check((0 until after.length()).any { after.getJSONObject(it).optString("memo").contains("CONTROL") })
            report.put("target_deleted_without_manual_recheck", true).put("control_preserved", true)
                .put("comments_after_delete", summarize(after)); save()
            val finalActions = db.openHelper.readableDatabase.query("SELECT gallId,postNum,targetType,targetNo FROM block_history").use { c ->
                buildList { while(c.moveToNext()) add(listOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3))) }
            }
            check(finalActions.isNotEmpty() && finalActions.all { it == listOf("armbandbot",post,"COMMENT",targetNo) })
            report.put("actions_before_cleanup", JSONArray(finalActions)); save()
            val detailCount = logs.count { it.contains("게시글 상세 접근 시작") }
            await("unchanged scan remains bounded", cycles.get() + 2)
            check(logs.count { it.contains("게시글 상세 접근 시작") } == detailCount)
            report.put("unchanged_cycles_skip_detail", true); save()
            // Cleanup owned anonymous post through the same real service, never ban its IP.
            val writer = checkNotNull(document().selectFirst(".gall_writer"))
            check(writer.attr("data-uid").isBlank())
            val ip = writer.attr("data-ip"); check(ip.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}")))
            persistOrderedMultilineText(p, "user_blacklist", ip)
            p.edit().putString("block_process_mode", "DELETE").putBoolean("user_use_custom_action_config", false).commit()
            await("owned fixture cleanup", cycles.get() + 2)
            val response = Jsoup.connect(url).userAgent("Mozilla/5.0").header("Cookie", cookie())
                .ignoreHttpErrors(true).timeout(15000).execute()
            check(response.statusCode() == 404 || !response.parse().select(".title_subject").text().contains(marker))
            report.put("cleanup_complete", true).put("service_cycles", cycles.get()); save()
        } catch (t: Throwable) {
            report.put("error_type", t.javaClass.simpleName).put("error", t.message.orEmpty())
            throw t
        } finally {
            instrumentation.runOnMainSync {
                context.startService(Intent(context, BotService::class.java).setAction("STOP").putExtra("BOT_ID", bot))
                activity?.finish()
            }
            Thread.sleep(700)
            context.unregisterReceiver(receiver)
            report.put("logs", JSONArray(logs)); save()
            credentialsFile.delete(); fixtureFile.delete()
            p.edit().clear().commit(); master.edit().clear().commit()
        }
    }
}
