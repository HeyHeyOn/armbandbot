package com.heyheyon.armbandbot

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.ZonedDateTime
import java.util.UUID

/** Real service lifecycle regression. Empty credentials + WAITING keep all cases offline. */
class BotRestartServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val rows = JSONArray()
    private fun record(name: String, value: Any) { rows.put(JSONObject().put("case", name).put("result", value)) }
    private fun pref(id: String) = context.getSharedPreferences("bot_prefs_$id", Context.MODE_PRIVATE)
    private fun current(): BotService? = BotService::class.java.getDeclaredField("currentInstance").apply { isAccessible = true }.get(null) as? BotService
    @Suppress("UNCHECKED_CAST") private fun jobs(s: BotService): Map<String, Job> = BotService::class.java.getDeclaredField("activeBots").apply { isAccessible = true }.get(s) as Map<String, Job>
    private fun job(id: String): Job? = current()?.let { jobs(it)[id] }
    private fun await(timeout: Long = 6000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        return condition()
    }
    private fun running(id: String): Boolean = job(id)?.isActive == true && pref(id).getString("last_startup_phase", "") == "run_loop_entered"
    private fun command(id: String, action: String) = Intent(context, BotService::class.java).setAction(action).putExtra("BOT_ID", id).putExtra("COOKIE", "")
    private fun prepare(id: String, gallery: String) {
        val now=ZonedDateTime.now();val minute=now.hour*60+now.minute
        pref(id).edit().clear().putString("bot_name",id).putString("target_urls",gallery)
            .putString("saved_cookie", "").putBoolean("auto_login_enabled",false)
            .putString("auto_login_id", "").putString("auto_login_pw", "")
            .putBoolean("run_schedule_enabled",true)
            .putString(RUN_SCHEDULE_WINDOWS_JSON_KEY,encodeBotRunWindows(listOf(BotRunWindow((minute+60)%1440,(minute+120)%1440))))
            .putBoolean("is_debug_mode",true).commit()
    }
    @Test fun serviceStopRestartMatrix() {
        assertFalse("Use only fresh isolated emulator",BotService.isServiceCreated())
        val a="post297_a_${UUID.randomUUID()}";val b="post297_b_${UUID.randomUUID()}"
        val gallery="https://gall.dcinside.com/mini/board/lists/?id=armbandbot"
        prepare(a,gallery);prepare(b,gallery)
        val scenario=ActivityScenario.launch(ComponentActivity::class.java)
        fun start(id:String) { scenario.onActivity { ContextCompat.startForegroundService(it,command(id,"START")) } }
        fun stop(id:String) { scenario.onActivity { it.startService(command(id,"STOP")) } }
        try {
            start(a);assertTrue(await { running(a) });start(b);assertTrue(await { running(b) })
            for(i in 1..3) {
                val previous=job(a)!!;stop(a);assertTrue(await { previous.isCompleted && job(a)==null })
                assertTrue(running(b));start(a)
                record("same_gallery_restart_a_$i",await { running(a) });assertTrue(running(a))
            }
            stop(a);stop(b);assertTrue(await { current()==null })
            start(b);record("restart_after_full_stop",await { running(b) });assertTrue(running(b))
            stop(b);assertTrue(await { current()==null })
            for(sameGallery in listOf(true,false)) for(sameBot in listOf(false,true)) for(gap in listOf(0L,1L,50L,150L,500L)) {
                pref(b).edit().putString("target_urls", if(sameGallery) gallery else "https://gall.dcinside.com/mini/board/lists/?id=offline_other_fixture").commit()
                start(a);assertTrue(await { running(a) })
                val to=if(sameBot)a else b
                if(gap==0L) {
                    scenario.onActivity { it.startService(command(a,"STOP"));ContextCompat.startForegroundService(it,command(to,"START")) }
                } else {
                    stop(a);SystemClock.sleep(gap);start(to)
                }
                // Observe a stable state after queued onDestroy, not a transient run-loop entry.
                SystemClock.sleep(750)
                val success=running(to)
                record("handoff_gallery_${sameGallery}_same_${sameBot}_gap_${gap}ms",JSONObject().put("running",success).put("phase",pref(to).getString("last_startup_phase", "")).put("pref_running",pref(to).getBoolean("is_running",false)).put("service_exists",current()!=null))
                assertTrue("START survives preceding STOP (sameGallery=$sameGallery, sameBot=$sameBot, gap=$gap): $rows",success)
                assertTrue(pref(to).getBoolean("is_running", false))
                assertTrue(pref(to).getBoolean("should_restore_after_restart", false))
                val active=job(to)
                start(to);SystemClock.sleep(100)
                assertSame("Repeated START keeps current generation",active,job(to))
                stop(to);assertTrue(await { current()==null })
            }

        } finally {
            for(id in listOf(a,b)) if(current()!=null) stop(id)
            assertTrue("Service cleans up after final STOP",await { current()==null })
            File(context.getExternalFilesDir(null),"restart-service.json").writeText(rows.toString(2))
            scenario.close()
            for(id in listOf(a,b)) context.deleteSharedPreferences("bot_prefs_$id")
        }
    }
}
