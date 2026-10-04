package com.heyheyon.armbandbot

import android.content.Context
import android.content.SharedPreferences
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/** All side effects stay first-party and pass the existing runtime gate and shared DB claim. */
internal class PostAutomationRunner(private val context:Context,private val botId:String,private val p:SharedPreferences,private val cookie:String,private val claim:(PostKey,String,()->String)->String,private val log:(String)->Unit) {
    private fun gate(){RuntimeRequestGate.requireCurrent().check()}
    private fun targetAllowed(key:PostKey)=key.gallType to key.gallId in configuredAutomationGalleries(p)
    private fun fetch(url:String):Document { gate();return Jsoup.connect(url).userAgent("Mozilla/5.0").header("Cookie",cookie).followRedirects(false).timeout(15000).maxBodySize(524288).also { gate() }.get() }
    private fun detail(key:PostKey):Document {
        val d=fetch(DcinsidePostUrls.canonicalDetailUrl(key))
        require(d.select(".write_div").isNotEmpty() && automationTabs(d).isNotEmpty()){ "관리 권한 또는 게시글을 확인할 수 없습니다." }
        require(d.select("#no").attr("value")==key.postNo && d.select("#gallery_id").attr("value")==key.gallId){"게시글 식별 불일치"}
        return d
    }
    private fun post(key:PostKey,action:String,payload:Map<String,String>):String {
        gate();require(targetAllowed(key))
        val r=Jsoup.connect(nativeActionUrl(key,action)).userAgent("Mozilla/5.0").header("Cookie",cookie).header("Referer",DcinsidePostUrls.canonicalDetailUrl(key)).header("X-Requested-With","XMLHttpRequest").followRedirects(false).timeout(MODERATION_REQUEST_TIMEOUT_MS).ignoreContentType(true).data(payload).method(org.jsoup.Connection.Method.POST).also { gate() }.execute()
        return r.body()
    }
    fun runDueBumps(now:Long=System.currentTimeMillis()) {
        if(!p.getBoolean(BUMP_ENABLED_KEY,false))return
        try {
            val due=dueBumps(parseBumpRules(p.getString(BUMP_RULES_KEY,"[]").orEmpty()),now,ZoneId.systemDefault())
            for(task in due) {
                gate();if(!p.getBoolean(BUMP_ENABLED_KEY,false) || !targetAllowed(task.key))continue
                val d=detail(task.key)
                if(!automationHasBumpControl(d) || d.select(".title_headtext, .gallview_head .gall_subject").text().contains(Regex("공지|고정")))continue
                val response=claim(task.key,"BUMP:${task.occurrence}") {
                    gate();require(p.getBoolean(BUMP_ENABLED_KEY,false));require(parseBumpRules(p.getString(BUMP_RULES_KEY,"[]").orEmpty()).any{it==task.rule})
                    val token=dcCookieToken(cookie);require(token.isNotEmpty())
                    val body=post(task.key,"update_bump",nativeBumpPayload(task.key,token))
                    val json=runCatching{JSONObject(body)}.getOrNull()
                    // Native UI reloads even on a failure; explicit success + actual list readback is required.
                    if(json?.optString("result")!="success")return@claim body
                    val list=fetch(automationGalleryUrl(task.key.gallType to task.key.gallId))
                    val first=list.select("tr.ub-content").firstOrNull{it.attr("data-type") !in setOf("icon_notice","icon_slow") && it.attr("data-no").matches(Regex("[1-9][0-9]*"))}
                    if(first?.attr("data-no")!=task.key.postNo) "{\"result\":\"unknown\"}" else body
                }
                val status=runCatching{JSONObject(response).optString("result")}.getOrDefault("unknown")
                if(status!="skipped") { p.edit().putString("automation_last_status","끌올 ${task.key.postNo}: $status").apply();log("[예약 끌올] 글 ${task.key.postNo} · $status") }
            }
        }catch(e:CancellationException){throw e}catch(e:SchedulePausedException){throw e}catch(_:Exception){p.edit().putString("automation_last_status","끌올 확인 실패 · 권한/예약/일반 글 여부를 확인하세요.").apply();log("[예약 끌올] 확인 실패 · 자동 중복 재요청 없이 다음 검사에서 상태 확인")}
    }
    fun maybeMove(key:PostKey,text:String,whitelisted:Boolean,exempt:Boolean,alreadyHandled:Boolean):Boolean {
        if(!p.getBoolean(MOVE_ENABLED_KEY,false) || !targetAllowed(key))return true
        try {
            val rule=matchingMove(parseTabMoveRules(p.getString(MOVE_RULES_KEY,"[]").orEmpty()),key,text,whitelisted,exempt,alreadyHandled) ?: return true
            if(!isElectedMover(key,text))return true
            val d=detail(key)
            val selected=d.select(".title_headtext").text().removeSurrounding("[","]")
            if(selected==rule.label)return true
            val currentCategories=automationTabs(d)
            val target=currentCategories.firstOrNull{it.id==rule.headtext} ?: return false
            require(target.label==rule.label && target.obstruct==rule.obstruct){"탭 설정이 변경되었습니다. 규칙을 다시 저장하세요."}
            val identity=movePolicyClaimIdentity(configuredMoverPolicies(key))
            val response=claim(key,"MOVE:$identity") {
                gate();require(p.getBoolean(MOVE_ENABLED_KEY,false));require(isElectedMover(key,text));require(movePolicyClaimIdentity(configuredMoverPolicies(key))==identity);require(parseTabMoveRules(p.getString(MOVE_RULES_KEY,"[]").orEmpty()).any{it==rule})
                val body=post(key,"chg_headtext",nativeMovePayload(key,dcCookieToken(cookie),rule.headtext))
                if(runCatching{JSONObject(body).optString("result")}.getOrNull()!="success")return@claim body
                val verify=detail(key)
                val selectedLabel=verify.select(".title_headtext").text().removeSurrounding("[","]")
                if(selectedLabel==rule.label)body else "{\"result\":\"unknown\"}"
            }
            val status=runCatching{JSONObject(response).optString("result")}.getOrDefault("unknown")
            if(status!="skipped"){p.edit().putString("automation_last_status","탭 이동 ${key.postNo} → ${rule.label}: $status").apply();log("[키워드 탭 이동] 글 ${key.postNo} → ${rule.label} · $status")}
            return status in setOf("success","skipped","unknown")
        }catch(e:CancellationException){throw e}catch(e:SchedulePausedException){throw e}catch(_:Exception){p.edit().putString("automation_last_status","탭 이동 확인 실패 · 대상 탭과 권한을 확인하세요.").apply();log("[키워드 탭 이동] 확인 실패");return false}
    }
    private fun configuredMoverPolicies(key:PostKey):Map<String,String> {
        val ids=context.getSharedPreferences("bot_master",Context.MODE_PRIVATE).getString("bot_ids_list","").orEmpty().split(',').filter{it.isNotBlank()}.sorted()
        return ids.mapNotNull { id ->
            val pref=context.getSharedPreferences("bot_prefs_$id",Context.MODE_PRIVATE)
            // Keep saved policies in the shared claim while disabled; only election depends on enabled/running.
            if(key.gallType to key.gallId !in configuredAutomationGalleries(pref))return@mapNotNull null
            val rules=runCatching{parseTabMoveRules(pref.getString(MOVE_RULES_KEY,"[]").orEmpty()).filter{it.gallType==key.gallType && it.gallId==key.gallId}}.getOrNull() ?: return@mapNotNull null
            if(rules.isEmpty())null else id to encodeTabMoveRules(rules)
        }.toMap()
    }
    private fun isElectedMover(key:PostKey,text:String):Boolean {
        val ids=context.getSharedPreferences("bot_master",Context.MODE_PRIVATE).getString("bot_ids_list","").orEmpty().split(',').filter{it.isNotBlank()}.sorted()
        val winner=ids.firstOrNull { id->
            val pref=context.getSharedPreferences("bot_prefs_$id",Context.MODE_PRIVATE)
            pref.getBoolean("is_running",false) && pref.getBoolean(MOVE_ENABLED_KEY,false) && key.gallType to key.gallId in configuredAutomationGalleries(pref) && runCatching{matchingMove(parseTabMoveRules(pref.getString(MOVE_RULES_KEY,"[]").orEmpty()),key,text,false,false,false)!=null}.getOrDefault(false)
        }
        return winner==botId
    }
}
internal fun dcCookieToken(cookie:String):String=cookie.split(';').map{it.trim()}.firstOrNull{it.startsWith("ci_c=")}?.removePrefix("ci_c=").orEmpty()
