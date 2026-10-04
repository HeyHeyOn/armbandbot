package com.heyheyon.armbandbot

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId

internal const val BUMP_RULES_KEY="automation_bump_rules"
internal const val MOVE_RULES_KEY="automation_move_rules"
internal const val BUMP_ENABLED_KEY="automation_bump_enabled"
internal const val MOVE_ENABLED_KEY="automation_move_enabled"
internal const val REMOTE_ENABLED_KEY="remote_lists_enabled"

internal fun strictAutomationPost(raw:String): SafeDcPostUrl {
    val parsed=DcinsidePostUrls.parseSafeCanonicalPostUrl(raw)
    require(parsed!=null && parsed.key.gallType in setOf("M","MI") && parsed.key.postNo.toLongOrNull()?.let{it>0}==true) { "마이너·미니갤의 PC 게시글 주소를 입력하세요." }
    return parsed
}
internal data class BumpRule(val id:String,val url:String,val minutes:List<Int>) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")));strictAutomationPost(url);require(minutes.isNotEmpty() && minutes.size<=24 && minutes.all{it in 0..1439}) }
}
internal data class DueBump(val rule:BumpRule,val key:PostKey,val occurrence:String)
internal fun dueBumps(rules:List<BumpRule>,now:Long,zone:ZoneId):List<DueBump> {
    val local=Instant.ofEpochMilli(now).atZone(zone)
    return rules.flatMap { r -> r.minutes.distinct().mapNotNull { minute ->
        val scheduled=local.toLocalDate().atTime(minute/60,minute%60).atZone(zone).toInstant().toEpochMilli()
        if(now>=scheduled && now-scheduled<15*60_000L) DueBump(r,strictAutomationPost(r.url).key,"${local.toLocalDate()}T${formatMinuteOfDay(minute)}") else null
    }}.distinctBy{it.key to it.occurrence}
}
internal fun parseBumpTimes(text:String):List<Int> {
    val tokens=text.split(',').map{it.trim()};require(tokens.isNotEmpty() && tokens.size<=24)
    return tokens.map { require(it.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))) { "시각은 09:00, 18:30 형식으로 입력하세요." };it.take(2).toInt()*60+it.takeLast(2).toInt() }.distinct().sorted()
}
internal fun parseBumpRules(text:String):List<BumpRule> = try { parseBumpRulesUnchecked(text) } catch(e:Exception) { throw IllegalArgumentException("저장된 끌올 예약 형식을 확인하세요.",e) }
private fun parseBumpRulesUnchecked(text:String):List<BumpRule> {
    require(text.length<=131072);val a=JSONArray(text);require(a.length()<=64)
    return (0 until a.length()).map { val j=a.getJSONObject(it);val times=j.getJSONArray("minutes");BumpRule(j.getString("id"),j.getString("url"),(0 until times.length()).map{n->times.getInt(n)}) }.also { require(it.map{r->r.id}.distinct().size==it.size) }
}
internal fun encodeBumpRules(rules:List<BumpRule>):String=JSONArray().apply { rules.forEach { r -> put(JSONObject().put("id",r.id).put("url",r.url).put("minutes",JSONArray(r.minutes))) } }.toString()
internal data class TabMoveRule(val id:String,val gallType:String,val gallId:String,val keyword:String,val headtext:Int,val label:String,val obstruct:Boolean) {
    init { require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")));require(gallType in setOf("M","MI"));require(gallId.matches(Regex("[A-Za-z0-9_-]+")));require(keyword.isNotBlank() && keyword.length<=300);require(headtext>=0);require(label.isNotBlank() && label.length<=100) }
}
internal fun parseTabMoveRules(text:String):List<TabMoveRule> = try { parseTabMoveRulesUnchecked(text) } catch(e:Exception) { throw IllegalArgumentException("저장된 탭 이동 규칙 형식을 확인하세요.",e) }
private fun parseTabMoveRulesUnchecked(text:String):List<TabMoveRule> {
    require(text.length<=131072);val a=JSONArray(text);require(a.length()<=64)
    return (0 until a.length()).map { val j=a.getJSONObject(it);TabMoveRule(j.getString("id"),j.getString("gallType"),j.getString("gallId"),j.getString("keyword"),j.getInt("headtext"),j.getString("label"),j.optBoolean("obstruct",false)) }.also { require(it.map{r->r.id}.distinct().size==it.size) }
}
internal fun encodeTabMoveRules(rules:List<TabMoveRule>):String=JSONArray().apply { rules.forEach { r -> put(JSONObject().put("id",r.id).put("gallType",r.gallType).put("gallId",r.gallId).put("keyword",r.keyword).put("headtext",r.headtext).put("label",r.label).put("obstruct",r.obstruct)) } }.toString()
internal fun matchingMove(rules:List<TabMoveRule>,key:PostKey,text:String,whitelisted:Boolean,exempt:Boolean,alreadyHandled:Boolean):TabMoveRule? {
    if(whitelisted || exempt || alreadyHandled)return null
    return rules.firstOrNull { it.gallType==key.gallType && it.gallId==key.gallId && text.contains(it.keyword,ignoreCase=true) }
}
internal data class GalleryCategory(val id:Int,val label:String,val obstruct:Boolean=false)
internal fun parseGalleryCategories(html:String):List<GalleryCategory> {
    val doc=Jsoup.parse(html);val categories=linkedMapOf<Int,GalleryCategory>()
    automationManagerFragments(doc).flatMap { it.select(".head_text [data-value], .mng_subject_sel [data-value], #listHeadTxtLyr [data-value], #headtext_list [data-value]") }.forEach { e ->
        val id=e.attr("data-value").toIntOrNull();val label=e.text().trim()
        if(id!=null && id>=0 && label.isNotEmpty() && e.attr("data-type") !in setOf("notice","recommend"))categories[id]=GalleryCategory(id,label,e.attr("data-type")=="obstruct")
    }
    doc.select("a[href]").forEach { e ->
        val href=e.attr("href");val id=Regex("(?:[?&])(?:headid|search_head)=([0-9]+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
        val label=e.text().trim()
        if(id!=null && id>=0 && label.isNotEmpty() && label.length<=100 && !categories.containsKey(id))categories[id]=GalleryCategory(id,label)
    }
    return categories.values.sortedBy{it.id}
}
internal fun nativeActionUrl(key:PostKey,action:String):String {
    require(key.gallType in setOf("M","MI"));require(action in setOf("update_bump","chg_headtext"))
    return "https://gall.dcinside.com/ajax/${if(key.gallType=="M")"minor" else "mini"}_manager_board_ajax/$action"
}
internal fun nativeBumpPayload(key:PostKey,token:String):Map<String,String> = linkedMapOf("ci_t" to token,"id" to key.gallId,"_GALLTYPE_" to key.gallType,"nos[]" to key.postNo)
internal fun nativeMovePayload(key:PostKey,token:String,destination:Int):Map<String,String> = linkedMapOf("ci_t" to token,"id" to key.gallId,"_GALLTYPE_" to key.gallType,"no" to key.postNo,"headtext" to destination.toString())
/** Use the same claim across elected bots; stopping one must not let another undo its move. */
internal fun movePolicyClaimIdentity(policies:Map<String,String>):String = policyHash(
    JSONArray().apply { policies.toSortedMap().forEach { (id,rules) -> put(JSONArray().put(id).put(rules)) } }.toString()
).take(24)
internal class AutomationRecheckState {
    private var revision="";private val seen=hashSetOf<PostKey>()
    @Synchronized fun update(value:String) { if(value!=revision){revision=value;seen.clear()} }
    @Synchronized fun needs(key:PostKey)=revision.isNotEmpty() && seen.size<10000 && key !in seen
    @Synchronized fun mark(key:PostKey) { if(seen.size<10000)seen.add(key) }
}
