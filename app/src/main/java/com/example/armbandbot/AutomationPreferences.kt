package com.heyheyon.armbandbot

import android.content.SharedPreferences
import java.security.MessageDigest

internal fun ensureRemoteListSettings(p:SharedPreferences) {
    if(p.getInt(REMOTE_CHANNEL_VERSION_KEY,0)==1)return
    val before=p.all.filterValues { it!=null }.mapValues { it.value!! }
    val migrated=migrateRemoteListSnapshot(before)
    val editor=p.edit()
    migrated.filterKeys { it.startsWith("remote_channel_") || it==REMOTE_CHANNEL_VERSION_KEY }.forEach { (key,value) -> when(value) {
        is Boolean -> editor.putBoolean(key,value);is Int -> editor.putInt(key,value)
        is Long -> editor.putLong(key,value);is String -> editor.putString(key,value)
    } }
    check(editor.commit()) { "원격 목록 설정을 이전하지 못했습니다." }
}
internal fun effectiveRemoteLists(p:SharedPreferences):RemoteLists? {
    ensureRemoteListSettings(p)
    val values=linkedMapOf<String,List<String>>()
    REMOTE_CHANNEL_KEYS.forEach { channel -> runCatching {
        fun k(field:String)=remoteListPrefKey(channel,field)
        if(!p.getBoolean(k("enabled"),false))return@runCatching
        val kind=RemoteSourceKind.valueOf(p.getString(k("kind"),"GITHUB")!!)
        val source=remoteListSourceId(resolveRemoteUrl(p.getString(k("url"),"").orEmpty(),kind),kind,p.getString(k("format"),"SIMPLE")!!)
        if(p.getString(k("cache_source"),"")!=source)return@runCatching
        val cache=p.getString(k("cache"),null) ?: return@runCatching
        values[channel]=parseSimpleRemoteList(cache,RemoteSourceKind.JSON)
    } }
    return if(values.isEmpty())null else RemoteLists(values)
}
internal fun automationPolicyRevision(p:SharedPreferences):String {
    val move=if(p.getBoolean(MOVE_ENABLED_KEY,false))p.getString(MOVE_RULES_KEY,"[]").orEmpty() else ""
    val remote=effectiveRemoteLists(p)?.revision.orEmpty()
    val masters=p.getString(FILTER_MASTER_REVISION_KEY, "").orEmpty()
    val user = userFilterPolicyRevision(p)
    return policyHash("$move|$remote|$masters|$user")
}
internal fun policyHash(text:String):String=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
internal fun configuredAutomationGalleries(p:SharedPreferences):Set<Pair<String,String>> = p.getString("target_urls","").orEmpty().lineSequence().mapNotNull { line->parseManagedGalleryUrl(line) }.toSet()
internal fun automationGallery(url:String):Pair<String,String> = parseManagedGalleryUrl(url)
    ?: throw IllegalArgumentException("관리할 갤러리 주소를 확인하세요. PC·모바일 주소를 사용할 수 있습니다.")
internal fun automationGalleryUrl(pair:Pair<String,String>):String="https://gall.dcinside.com/${if(pair.first=="M")"mgallery" else "mini"}/board/lists/?id=${pair.second}"
