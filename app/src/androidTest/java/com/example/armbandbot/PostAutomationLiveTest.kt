package com.heyheyon.armbandbot
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Opt-in only: caller must stage the exact newly-owned laboratory1 fixture and its dedicated session. */
class PostAutomationLiveTest {
 @Test fun ownedGithubJsonAndCsvUseNativeClientAndLocalKeys()=runBlocking {
  val url=InstrumentationRegistry.getArguments().getString("remote_fixture")
  org.junit.Assume.assumeTrue(url != null)
  require(url!!.startsWith("https://raw.githubusercontent.com/HeyHeyOn/armbandbot/"))
  require(url.endsWith("/docs/examples/remote-lists.json"))
  val p=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("github_owned_remote_fixture",Context.MODE_PRIVATE)
  try {
   for(source in listOf(url,url.removeSuffix(".json")+".csv")) {
    p.edit().clear().putBoolean(REMOTE_ENABLED_KEY,true).putString("remote_lists_kind","GITHUB").putString("remote_lists_url",source).putString("banned_normal","local-owned-fixture").commit()
    assertTrue(RemoteListClient().sync(p,force=true))
    assertEquals(listOf("local-owned-fixture","AB154-remote-example"),mergeRemoteList(listOf("local-owned-fixture"),effectiveRemoteLists(p),"banned_normal"))
    assertEquals("local-owned-fixture",p.getString("banned_normal",null))
   }
  }finally{p.edit().clear().commit()}
 }

 @Test fun ownedFixtureNativeMoveAndBumpReadbackAreNotRepeated()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val ctx=i.targetContext
  org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("automation_live")=="authorized_fixture")
  val fixture=JSONObject(File(ctx.filesDir,"automation_live_fixture.json").readText())
  val cookie=File(ctx.filesDir,"automation_live_cookie.txt").readText()
  check(fixture.getString("scope")=="laboratory1")
  val marker=fixture.getString("marker");check(marker.startsWith("AB154-AUTO-"))
  val key=strictAutomationPost(fixture.getString("url")).key
  check(key.gallId=="laboratory1" && key.postNo==fixture.getString("post_no"))
  val id="automation_owned_fixture"
  val p=ctx.getSharedPreferences("bot_prefs_$id",Context.MODE_PRIVATE)
  val otherId="automation_owned_fixture_second"
  val other=ctx.getSharedPreferences("bot_prefs_$otherId",Context.MODE_PRIVATE)
  val master=ctx.getSharedPreferences("bot_master",Context.MODE_PRIVATE)
  check(master.getString("bot_ids_list","").orEmpty().isBlank())
  val verify={Jsoup.connect(DcinsidePostUrls.canonicalDetailUrl(key)).header("Cookie",cookie).userAgent("Mozilla/5.0").get()}
  val before=verify();assertTrue(before.select(".title_subject").text().contains(marker))
  assertTrue(automationHasBumpControl(before));assertTrue(automationTabs(before).contains(GalleryCategory(10,"테스트")))
  assertEquals("[일반]",before.select(".title_headtext").text())
  var posts=0;val claims=hashSetOf<String>()
  val claim:(PostKey,String,()->String)->String={k,kind,action->
   check(k==key);if(!claims.add(kind)) "{\"result\":\"skipped\"}" else {posts++;action()}
  }
  val gate=RuntimeRequestGate(coroutineContext.job){true}
  try {
   master.edit().putString("bot_ids_list","$id,$otherId").commit()
   val move=TabMoveRule("live_move","M","laboratory1",marker,10,"테스트",false)
   val opposing=TabMoveRule("live_opposing","M","laboratory1",marker,0,"일반",false)
   val now=System.currentTimeMillis();val local=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
   val bump=BumpRule("live_bump",fixture.getString("url"),listOf(local.hour*60+local.minute))
   p.edit().clear().putString("target_urls","https://gall.dcinside.com/mgallery/board/lists/?id=laboratory1").putBoolean("is_running",true).putBoolean(MOVE_ENABLED_KEY,true).putString(MOVE_RULES_KEY,encodeTabMoveRules(listOf(move))).putBoolean(BUMP_ENABLED_KEY,true).putString(BUMP_RULES_KEY,encodeBumpRules(listOf(bump))).commit()
   other.edit().clear().putString("target_urls","https://gall.dcinside.com/mgallery/board/lists/?id=laboratory1").putBoolean("is_running",true).putBoolean(MOVE_ENABLED_KEY,true).putString(MOVE_RULES_KEY,encodeTabMoveRules(listOf(opposing))).commit()
   withContext(RuntimeRequestGate.context.asContextElement(gate)) {
    val runner=PostAutomationRunner(ctx,id,p,cookie,claim,{})
    assertTrue(runner.maybeMove(key,marker,true,false,false));assertEquals(0,posts)
    assertTrue(runner.maybeMove(key,marker,false,true,false));assertEquals(0,posts)
    assertTrue(runner.maybeMove(key,marker,false,false,false));assertEquals(1,posts)
    assertEquals("[테스트]",verify().select(".title_headtext").text())
    assertTrue(runner.maybeMove(key,marker,false,false,false));assertEquals(1,posts)
    p.edit().putBoolean("is_running",false).commit()
    assertTrue(PostAutomationRunner(ctx,otherId,other,cookie,claim,{}).maybeMove(key,marker,false,false,false))
    assertEquals(1,posts)
    assertEquals("[테스트]",verify().select(".title_headtext").text())
    p.edit().putBoolean("is_running",true).commit()
    runner.runDueBumps(now);assertEquals(2,posts)
    assertTrue(p.getString("automation_last_status","").orEmpty().endsWith("success"))
    PostAutomationRunner(ctx,id,p,cookie,claim,{}).runDueBumps(now);assertEquals(2,posts)
   }
   val after=verify();assertTrue(after.select(".title_subject").text().contains(marker))
   assertEquals("[테스트]",after.select(".title_headtext").text())
  }finally {p.edit().clear().commit();other.edit().clear().commit();master.edit().clear().commit();File(ctx.filesDir,"automation_live_cookie.txt").delete();File(ctx.filesDir,"automation_live_fixture.json").delete()}
 }
}
