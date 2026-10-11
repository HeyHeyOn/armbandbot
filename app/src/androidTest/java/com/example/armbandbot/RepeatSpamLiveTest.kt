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

/** Opt-in only: three owned, harmless Test60 posts, prepared slowly through the normal composer. */
class RepeatSpamLiveTest {
    @Test fun ownedRepeatsDeleteIncludingFirstWhileControlAndProtectionsSurvive() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val fixtureFile=File(context.filesDir,"release170_fixture.json")
        val credentialsFile=File(context.filesDir,"release170_credentials.json")
        org.junit.Assume.assumeTrue("Explicit live fixture required",fixtureFile.isFile && credentialsFile.isFile)
        val fixture=JSONObject(fixtureFile.readText()); val credentials=JSONObject(credentialsFile.readText())
        check(fixture.getString("scope")=="armbandbot" && fixture.getInt("headtext")==60)
        val marker=fixture.getString("marker"); check(marker.startsWith("AB170-"))
        val posts=fixture.getJSONArray("posts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        check(posts.size==3 && posts.map { it.getString("id") }.distinct().size==3)
        val repeats=posts.filter { it.getString("role")=="repeat" }; check(repeats.size==2)
        val first=repeats.minBy { it.getString("id").toLong() }.getString("id")
        val control=posts.single { it.getString("role")=="control" }.getString("id")
        val allIds=posts.map { it.getString("id") }.toSet()
        val master=context.getSharedPreferences("bot_master",Context.MODE_PRIVATE)
        check(master.getString("bot_ids_list","").orEmpty().isBlank())
        val bot="release170_owned_probe"
        val p=context.getSharedPreferences("bot_prefs_$bot",Context.MODE_PRIVATE)
        val cycles=AtomicInteger(); val logs=CopyOnWriteArrayList<String>()
        val report=JSONObject().put("version",BuildConfig.VERSION_NAME).put("real_service",true)
            .put("scope","armbandbot/Test/60").put("first_post",first).put("control_post",control)
            .put("repeat_posts",JSONArray(repeats.map { it.getString("id") }))
        val out=File(context.getExternalFilesDir(null),"release170-live.json")
        fun save() { out.writeText(report.toString(2)) }
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context?,intent:Intent?) {
                if(intent?.getStringExtra("BOT_ID")!=bot)return
                val text=intent.getStringExtra("LOG_MSG").orEmpty()
                if(!text.contains("cookie",true) && !text.contains("token",true))logs.add(text)
                if(text.contains("사이클 완료!"))cycles.incrementAndGet()
            }
        }
        fun awaitCycles(label:String,n:Int) {
            val until=System.currentTimeMillis()+180_000
            while(cycles.get()<n && System.currentTimeMillis()<until)Thread.sleep(500)
            check(cycles.get()>=n){"$label timed out; completed cycles=${cycles.get()}"}
        }
        fun doc(id:String)=Jsoup.connect("https://gall.dcinside.com/mini/board/view/?id=armbandbot&no=$id")
            .userAgent("Mozilla/5.0").header("Cookie",p.getString("saved_cookie","").orEmpty()).timeout(20000).get()
        fun assertOwnedPresent() {
            posts.forEach { post ->
                val d=doc(post.getString("id"))
                check(d.select(".title_subject").text()==post.getString("title"))
                check(d.select(".write_div").text().contains(post.getString("body")))
                check(d.select(".title_headtext").text().contains("테스트"))
                Thread.sleep(1500)
            }
        }
        fun holds(id:String)=GlobalBotState.hasHoldHistory("MI","armbandbot",id,"POST",id)
        fun checkedIds():Set<String> = checkNotNull(GlobalBotState.getDb()).openHelper.readableDatabase
            .query("SELECT gallType,gallId,postNum FROM checked_posts").use { c -> buildSet {
                while(c.moveToNext()) { check(c.getString(0)=="MI" && c.getString(1)=="armbandbot"); add(c.getString(2)) }
            } }
        ContextCompat.registerReceiver(context,receiver,IntentFilter("BOT_LOG_EVENT"),ContextCompat.RECEIVER_NOT_EXPORTED)
        var activity:android.app.Activity?=null
        try {
            p.edit().clear().putString("bot_name","반복 글 베타 검증")
                .putString("target_urls","https://gall.dcinside.com/mini/board/lists/?id=armbandbot&search_head=60")
                .putBoolean("independent_scan_state_enabled",true)
                .putBoolean("is_search_mode",true).putString("search_keywords_text",marker).putStringSet("search_keywords",setOf(marker))
                .putBoolean("auto_login_enabled",true).putString("auto_login_id",credentials.getString("username"))
                .putString("auto_login_pw",credentials.getString("password"))
                .putBoolean("is_debug_mode",true).putBoolean("is_user_filter_mode",true)
                .putBoolean("is_nickname_filter_mode",false).putBoolean("is_ai_filter_mode",false)
                .putBoolean("is_expert_mode",true).putBoolean("is_snapshot_all",false).putBoolean("is_snapshot_blocked",true)
                .putBoolean("noti_master",false).putBoolean("gallery_setting_refresh_enabled",false)
                .putBoolean(REPEAT_ENABLED_KEY,false).putBoolean(REPEAT_OBSERVE_KEY,true)
                .putInt(REPEAT_WINDOW_KEY,60).putInt(REPEAT_COUNT_KEY,2).putInt(REPEAT_SIMILARITY_KEY,100)
                .putBoolean(REPEAT_YUDONG_KEY,true).putBoolean(REPEAT_KKANG_KEY,false)
                .putInt("scan_page_count",1).putFloat("delay_post_min_sec",3f).putFloat("delay_post_max_sec",4f)
                .putFloat("delay_page_min_sec",3f).putFloat("delay_page_max_sec",4f)
                .putFloat("delay_cycle_min_sec",20f).putFloat("delay_cycle_max_sec",22f).commit()
            master.edit().putString("bot_ids_list",bot).commit()
            activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync { ContextCompat.startForegroundService(context,Intent(context,BotService::class.java).setAction("START").putExtra("BOT_ID",bot)) }
            awaitCycles("initial no-action scan",1)
            check(checkedIds()==allIds){"Refusing actions outside exact owned fixture"}
            val d=doc(first); check(automationManagerConfirmed(d)){"Gallery authority not confirmed"}
            assertOwnedPresent()
            val prefix=fixture.getString("author_ip"); check(prefix.isNotBlank())
            persistOrderedMultilineText(p,"user_whitelist",prefix)
            p.edit().putBoolean(REPEAT_ENABLED_KEY,true).repeatSpamChanged().commit()
            awaitCycles("whitelist",cycles.get()+2)
            check(repeats.none { holds(it.getString("id")) }); assertOwnedPresent()
            report.put("whitelist_protected_all",true);save()
            persistOrderedMultilineText(p,"user_whitelist","")
            persistOrderedMultilineText(p,"block_exempt_post_numbers",first)
            awaitCycles("first-post exemption",cycles.get()+2)
            check(repeats.none { holds(it.getString("id")) }); assertOwnedPresent()
            report.put("exempt_first_excluded_from_count",true);save()
            persistOrderedMultilineText(p,"block_exempt_post_numbers","")
            awaitCycles("observe only",cycles.get()+3)
            check(repeats.all { holds(it.getString("id")) }); check(!holds(control));assertOwnedPresent()
            report.put("observe_only_includes_first_and_preserves_control",true);save()
            // Only after scope, permission, exact content and protective modes are proven do we enable deletion.
            check(checkedIds()==allIds)
            p.edit().putBoolean(REPEAT_OBSERVE_KEY,false).repeatSpamChanged().commit()
            awaitCycles("delete both repeats",cycles.get()+3)
            val normal=doc(control);check(normal.select(".title_subject").text()==posts.single { it.getString("id")==control }.getString("title"))
            repeats.forEach { post ->
                val r=Jsoup.connect("https://gall.dcinside.com/mini/board/view/?id=armbandbot&no=${post.getString("id")}")
                    .userAgent("Mozilla/5.0").ignoreHttpErrors(true).timeout(20000).execute()
                check(r.statusCode()==404 || (!r.parse().select(".title_subject").text().contains(marker) && (r.body().contains("삭제") || r.body().contains("존재하지"))))
                Thread.sleep(1500)
            }
            val records=checkNotNull(GlobalBotState.getDb()).openHelper.readableDatabase.query(
                "SELECT postNum,blockReason,snapshotPath FROM block_history WHERE gallId='armbandbot'"
            ).use { c -> buildList { while(c.moveToNext()) add(listOf(c.getString(0),c.getString(1),c.getString(2))) } }
            check(records.map { it[0] }.toSet()==repeats.map { it.getString("id") }.toSet())
            records.forEach { row -> check(row[1].contains("반복"));check(File(row[2]).readText().contains(marker)) }
            report.put("actual_delete_records",JSONArray(records)).put("first_deleted",true).put("control_preserved",true)
                .put("cycles",cycles.get()).put("passed",true);save()
        } finally {
            instrumentation.runOnMainSync {
                context.startService(Intent(context,BotService::class.java).setAction("STOP").putExtra("BOT_ID",bot)); activity?.finish()
            }
            Thread.sleep(1000);context.unregisterReceiver(receiver)
            report.put("logs",JSONArray(logs));save()
            credentialsFile.delete();fixtureFile.delete();p.edit().clear().commit();master.edit().clear().commit()
        }
    }
}
