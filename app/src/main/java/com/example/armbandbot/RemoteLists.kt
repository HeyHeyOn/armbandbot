package com.heyheyon.armbandbot

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

internal enum class RemoteSourceKind { GITHUB,SHEETS,JSON }
internal val REMOTE_LIST_KEYS=setOf("normal","bypass","user_blacklist","nickname_blacklist","nickname_bypass_blacklist")
internal const val REMOTE_MAX_BYTES=262144
internal data class RemoteLists(val values:Map<String,List<String>>) {
    val revision:String get()=MessageDigest.getInstance("SHA-256").digest(toJson().toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    fun toJson():String=JSONObject().put("version",1).put("lists",JSONObject().apply { values.toSortedMap().forEach { (k,v)->put(k,org.json.JSONArray(v)) } }).toString()
}
internal fun parseRemoteLists(raw:String):RemoteLists = try { parseRemoteListsUnchecked(raw) } catch(e:Exception) { throw IllegalArgumentException(e.message ?: "원격 목록 형식을 확인하세요.",e) }
private fun parseRemoteListsUnchecked(raw:String):RemoteLists {
    require(raw.toByteArray(Charsets.UTF_8).size<=REMOTE_MAX_BYTES) { "원격 목록은 256KB 이하만 지원합니다." }
    val text=raw.trim().removePrefix("\uFEFF");require(text.isNotEmpty() && !text.startsWith('<')) { "목록 대신 빈 응답 또는 로그인 페이지가 반환되었습니다." }
    val values=linkedMapOf<String,List<String>>()
    if(text.startsWith('{')) {
        val j=JSONObject(text);require(j.getInt("version")==1);val lists=j.getJSONObject("lists");require(lists.length()>0)
        for(k in lists.keys()) {
            require(k in REMOTE_LIST_KEYS){"지원하지 않는 목록 종류입니다."}
            val a=lists.getJSONArray(k);require(a.length()<=10000)
            values[k]=(0 until a.length()).map { n -> require(a.get(n) is String);a.getString(n).trim().also{require(it.isNotEmpty() && it.length<=1000)} }.distinct()
        }
    } else {
        val rows=parseRemoteCsv(text);require(rows.isNotEmpty() && rows.first()==listOf("type","value")) { "시트 첫 행은 type,value 두 열이어야 합니다." }
        val collected=linkedMapOf<String,MutableList<String>>()
        for(row in rows.drop(1)) {
            if(row.all{it.isBlank()})continue
            require(row.size==2);val k=row[0].trim();val v=row[1].trim()
            require(k in REMOTE_LIST_KEYS && v.isNotEmpty() && v.length<=1000){"시트의 종류 또는 값이 잘못되었습니다."}
            collected.getOrPut(k){mutableListOf()}.add(v)
        }
        require(collected.isNotEmpty()){ "빈 시트는 적용하지 않습니다. 비우려면 JSON의 빈 배열을 사용하세요." }
        collected.forEach { (k,v)->require(v.size<=10000);values[k]=v.distinct() }
    }
    require(values.values.sumOf{it.size}<=10000)
    return RemoteLists(values)
}
internal fun parseRemoteCsv(text:String):List<List<String>> {
    val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val field=StringBuilder();var quoted=false;var closed=false;var i=0
    fun endField(){row.add(field.toString());field.setLength(0);closed=false}
    fun endRow(){endField();rows.add(row.toList());row.clear()}
    while(i<text.length) {
        val c=text[i]
        if(quoted) { if(c=='"'){if(i+1<text.length && text[i+1]=='"'){field.append('"');i++}else{quoted=false;closed=true}}else field.append(c) }
        else when(c) {
            '"'->{require(field.isEmpty() && !closed);quoted=true}
            ','->endField()
            '\n'->endRow()
            '\r'->{if(i+1<text.length && text[i+1]=='\n')i++;endRow()}
            else->{require(!closed);field.append(c)}
        }
        i++
    }
    require(!quoted){"시트의 따옴표가 닫히지 않았습니다."};if(field.isNotEmpty() || row.isNotEmpty() || closed)endRow()
    return rows
}
internal fun mergeRemoteList(local:List<String>,remote:RemoteLists?,key:String):List<String> {
    val remoteKey = when (key) { "banned_normal" -> "normal"; "banned_bypass" -> "bypass"; else -> key }
    return (local + (remote?.values?.get(remoteKey) ?: emptyList())).distinct()
}
internal fun resolveRemoteUrl(raw:String,kind:RemoteSourceKind):String {
    val uri=try{URI(raw.trim())}catch(_:Exception){throw IllegalArgumentException("원격 주소를 확인하세요.")}
    require(uri.scheme=="https" && uri.userInfo==null && uri.port==-1){"인증정보 없는 HTTPS 주소만 지원합니다."}
    val host=uri.host?.lowercase().orEmpty()
    return when(kind) {
        RemoteSourceKind.GITHUB->{require(host=="raw.githubusercontent.com" && uri.fragment==null && uri.path.split('/').filter{it.isNotEmpty()}.size>=4);uri.toASCIIString()}
        RemoteSourceKind.JSON->{require(host in setOf("raw.githubusercontent.com","script.google.com") && uri.fragment==null);if(host=="script.google.com")require(uri.path.matches(Regex("/macros/s/[A-Za-z0-9_-]+/exec")));uri.toASCIIString()}
        RemoteSourceKind.SHEETS->{
            require(host=="docs.google.com")
            if(uri.path.matches(Regex("/spreadsheets/d/e/[A-Za-z0-9_-]+/pub"))) { require(uri.rawQuery?.split('&')?.contains("output=csv")==true);require(uri.fragment==null);uri.toASCIIString() }
            else {
                val match=Regex("/spreadsheets/d/([A-Za-z0-9_-]+)(?:/(?:edit|export))?/?").matchEntire(uri.path) ?: throw IllegalArgumentException("구글 시트 주소를 입력하세요.")
                val gidTokens=listOfNotNull(uri.rawQuery,uri.rawFragment).flatMap{it.split('&')}.filter{it=="gid" || it.startsWith("gid=")}
                require(gidTokens.all{it.matches(Regex("gid=[0-9]+"))}){"시트 gid를 확인하세요."}
                val gids=gidTokens.map{it.removePrefix("gid=")}
                require(gids.distinct().size<=1){"서로 다른 시트 gid가 들어 있습니다."}
                val gid=gids.firstOrNull() ?: "0"
                "https://docs.google.com/spreadsheets/d/${match.groupValues[1]}/export?format=csv&gid=$gid"
            }
        }
    }
}
internal fun allowedRemoteRedirect(url:String):Boolean {
    val u=runCatching{URI(url)}.getOrNull() ?: return false
    if(u.scheme!="https" || u.userInfo!=null || u.port!=-1)return false
    val h=u.host?.lowercase().orEmpty()
    return h in setOf("raw.githubusercontent.com","docs.google.com","script.google.com","script.googleusercontent.com") || h.matches(Regex("doc-[a-z0-9-]+-sheets\\.googleusercontent\\.com"))
}
