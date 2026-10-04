package com.heyheyon.armbandbot

import android.content.SharedPreferences
import java.security.MessageDigest

internal fun effectiveRemoteLists(p:SharedPreferences):RemoteLists? {
    if(!p.getBoolean(REMOTE_ENABLED_KEY,false))return null
    val source=runCatching{resolveRemoteUrl(p.getString("remote_lists_url","").orEmpty(),RemoteSourceKind.valueOf(p.getString("remote_lists_kind","GITHUB").orEmpty()))}.getOrNull() ?: return null
    if(p.getString("remote_lists_cache_source","")!=source)return null
    return runCatching{parseRemoteLists(p.getString("remote_lists_cache","").orEmpty())}.getOrNull()
}
internal fun automationPolicyRevision(p:SharedPreferences):String {
    val move=if(p.getBoolean(MOVE_ENABLED_KEY,false))p.getString(MOVE_RULES_KEY,"[]").orEmpty() else ""
    val remote=effectiveRemoteLists(p)?.revision.orEmpty()
    if(move.isEmpty() && remote.isEmpty())return ""
    return policyHash("$move|$remote")
}
internal fun policyHash(text:String):String=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
internal fun configuredAutomationGalleries(p:SharedPreferences):Set<Pair<String,String>> = p.getString("target_urls","").orEmpty().lineSequence().mapNotNull { line->runCatching{automationGallery(line.trim())}.getOrNull() }.toSet()
internal fun automationGallery(url:String):Pair<String,String> {
    val u=java.net.URI(url);require(u.scheme=="https" && u.host=="gall.dcinside.com" && u.userInfo==null && u.port==-1)
    val type=when(u.path.trimEnd('/')){ "/mgallery/board/lists","/mgallery/board/view"->"M";"/mini/board/lists","/mini/board/view"->"MI";else->throw IllegalArgumentException("마이너·미니갤 PC 주소를 사용하세요.") }
    val ids=u.rawQuery.orEmpty().split('&').filter{it.startsWith("id=")};require(ids.size==1)
    val id=ids.single().removePrefix("id=");require(id.matches(Regex("[A-Za-z0-9_-]+")))
    return type to id
}
internal fun automationGalleryUrl(pair:Pair<String,String>):String="https://gall.dcinside.com/${if(pair.first=="M")"mgallery" else "mini"}/board/lists/?id=${pair.second}"
