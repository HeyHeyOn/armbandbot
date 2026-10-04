package com.heyheyon.armbandbot

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer

internal data class RemoteDownload(val status:Int,val body:ByteArray,val etag:String?)

/** Never lock Android SharedPreferences itself: pre-O disk writes use its monitor. */
private object RemoteListSyncLocks {
 private val monitors = java.util.WeakHashMap<SharedPreferences,Any>()
 fun forPreferences(p:SharedPreferences):Any = synchronized(monitors) { monitors.getOrPut(p) { Any() } }
}

internal class RemoteListClient(private val download:((String,String?)->RemoteDownload)? = null) {
 suspend fun sync(p:SharedPreferences,force:Boolean=false,now:Long=System.currentTimeMillis(),beforeRequest:()->Unit={}):Boolean = withContext(Dispatchers.IO) {
  synchronized(RemoteListSyncLocks.forPreferences(p)) {
   if(!p.getBoolean(REMOTE_ENABLED_KEY,false))return@synchronized false
   val kind=runCatching{RemoteSourceKind.valueOf(p.getString("remote_lists_kind","GITHUB")!!)}.getOrNull() ?: return@synchronized false
   val raw=p.getString("remote_lists_url","").orEmpty()
   val url=runCatching{resolveRemoteUrl(raw,kind)}.getOrElse { p.edit().putString("remote_lists_status","원격 주소를 확인하세요.").apply();return@synchronized false }
   val source=url
   val interval=p.getInt("remote_lists_interval_minutes",15).coerceIn(5,1440)*60000L
   val last=if(p.getString("remote_lists_attempt_source","")==source)p.getLong("remote_lists_last_attempt",0)else 0L
   if(!force && last>0 && now>=last && now-last<interval)return@synchronized false
   val same=p.getString("remote_lists_cache_source","")==source
   val etag=if(same)p.getString("remote_lists_etag",null)else null
   try {
    beforeRequest()
    p.edit().putLong("remote_lists_last_attempt",now).putString("remote_lists_attempt_source",source).apply()
    val received=download?.invoke(url,etag) ?: downloadRemoteLists(url,etag,beforeRequest)
    beforeRequest()
    if(!p.getBoolean(REMOTE_ENABLED_KEY,false) || p.getString("remote_lists_kind","")!=kind.name || p.getString("remote_lists_url","")!=raw)return@synchronized false
    if(received.status==304){require(same && p.contains("remote_lists_cache"));p.edit().putString("remote_lists_status","변경 없음 · 마지막 정상 목록 유지").apply();return@synchronized false}
    require(received.status==200 && received.body.size<=REMOTE_MAX_BYTES)
    val body=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(received.body)).toString()
    val parsed=parseRemoteLists(body)
    val previous=if(same)p.getString("remote_lists_cache",null)else null
    val encoded=parsed.toJson()
    p.edit().putString("remote_lists_cache",encoded).putString("remote_lists_cache_source",source).putString("remote_lists_etag",received.etag)
     .putLong("remote_lists_last_success",now).putString("remote_lists_status","수신 완료 · ${parsed.values.values.sumOf{it.size}}개 항목").apply()
    previous!=encoded
   } catch(e:kotlinx.coroutines.CancellationException){throw e}
   catch(e:SchedulePausedException){throw e}
   catch(_:Exception){p.edit().putString("remote_lists_status","수신 실패 · 같은 원본의 마지막 정상 목록 유지").apply();false}
  }
 }
}

private fun downloadRemoteLists(initial:String,etag:String?,beforeRequest:()->Unit):RemoteDownload {
 var url=initial
 repeat(2){
  beforeRequest()
  val c=URI(url).toURL().openConnection() as HttpURLConnection
  c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=15000
  c.setRequestProperty("Accept","application/json,text/csv,text/plain");c.setRequestProperty("User-Agent","ArmbandBot-RemoteLists")
  if(!etag.isNullOrBlank())c.setRequestProperty("If-None-Match",etag)
  try {
   val status=c.responseCode
   if(status in 300..399 && status!=304){
    val location=URI(url).resolve(c.getHeaderField("Location") ?: error("잘못된 리디렉션")).toString()
    require(it==0 && allowedRemoteRedirect(location));url=location
   } else {
    val data=if(status==200){require(c.contentLengthLong<=REMOTE_MAX_BYTES);c.inputStream.use { stream ->
      val buffer=ByteArray(8192);val out=java.io.ByteArrayOutputStream()
      while(true){val n=stream.read(buffer);if(n<0)break;out.write(buffer,0,n);require(out.size()<=REMOTE_MAX_BYTES)}
      out.toByteArray()
     }}else byteArrayOf()
    require(data.size<=REMOTE_MAX_BYTES)
    return RemoteDownload(status,data,c.getHeaderField("ETag"))
   }
  } finally {c.disconnect()}
 }
 error("원격 리디렉션 제한")
}
