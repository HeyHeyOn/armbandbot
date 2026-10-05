package com.heyheyon.armbandbot

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer

internal data class RemoteDownload(val status:Int,val body:ByteArray,val etag:String?)

/** Never lock Android SharedPreferences itself: pre-O disk writes use its monitor. */
private object RemoteListSyncLocks {
    private val monitors=java.util.WeakHashMap<SharedPreferences,Any>()
    fun forPreferences(p:SharedPreferences):Any=synchronized(monitors) { monitors.getOrPut(p) { Any() } }
}
private data class RemoteChannelConfig(val channel:String,val kind:RemoteSourceKind,val raw:String,val url:String,val format:String,val generation:Long,val interval:Long) {
    val source get()=remoteListSourceId(url,kind,format)
    fun k(field:String)=remoteListPrefKey(channel,field)
}
private fun remoteChannelConfig(p:SharedPreferences,channel:String):RemoteChannelConfig? = runCatching {
    fun k(field:String)=remoteListPrefKey(channel,field)
    if(!p.getBoolean(k("enabled"),false))return@runCatching null
    val kind=RemoteSourceKind.valueOf(p.getString(k("kind"),"GITHUB")!!)
    val raw=p.getString(k("url"),"").orEmpty()
    val format=p.getString(k("format"),"SIMPLE")!!;require(format in setOf("SIMPLE","LEGACY"))
    RemoteChannelConfig(channel,kind,raw,resolveRemoteUrl(raw,kind),format,p.getLong(k("generation"),0),p.getInt(k("interval_minutes"),15).coerceIn(5,1440)*60000L)
}.getOrNull()

internal class RemoteListClient(private val download:((String,String?)->RemoteDownload)?=null) {
    suspend fun sync(p:SharedPreferences,force:Boolean=false,now:Long=System.currentTimeMillis(),beforeRequest:()->Unit={}):Boolean = syncChannels(p,null,force,now,beforeRequest)
    suspend fun syncList(p:SharedPreferences,channel:String,force:Boolean=false,now:Long=System.currentTimeMillis(),beforeRequest:()->Unit={}):Boolean {
        require(channel in REMOTE_CHANNEL_KEYS)
        return syncChannels(p,channel,force,now,beforeRequest)
    }
    private suspend fun syncChannels(p:SharedPreferences,only:String?,force:Boolean,now:Long,beforeRequest:()->Unit):Boolean=withContext(Dispatchers.IO) {
        synchronized(RemoteListSyncLocks.forPreferences(p)) {
            ensureRemoteListSettings(p)
            val all=REMOTE_CHANNEL_KEYS.mapNotNull { channel ->
                remoteChannelConfig(p,channel).also { config ->
                    if(config==null && p.getBoolean(remoteListPrefKey(channel,"enabled"),false))p.edit().putString(remoteListPrefKey(channel,"status"),"목록 주소를 확인하세요.").apply()
                }
            }
            val selected=if(only==null)all else all.filter { it.channel==only || (it.format=="LEGACY" && all.any { target->target.channel==only && target.source==it.source }) }
            var changed=false
            for(group in selected.groupBy { it.source }.values) {
                val due=group.filter { config ->
                    val last=if(p.getString(config.k("attempt_source"),"")==config.source)p.getLong(config.k("last_attempt"),0)else 0L
                    force || last<=0 || now<last || now-last>=config.interval
                }
                if(due.isEmpty())continue
                val first=due.first()
                val same=due.all { p.getString(it.k("cache_source"),"")==it.source && p.contains(it.k("cache")) }
                val tags=due.map { p.getString(it.k("etag"),null) }.distinct()
                val etag=if(same && tags.size==1)tags.single() else null
                try {
                    beforeRequest()
                    val attempt=p.edit();due.forEach { attempt.putLong(it.k("last_attempt"),now).putString(it.k("attempt_source"),it.source) };attempt.apply()
                    val received=download?.invoke(first.url,etag) ?: downloadRemoteLists(first.url,etag,beforeRequest)
                    beforeRequest()
                    val current=due.filter { remoteChannelConfig(p,it.channel)==it }
                    if(current.isEmpty())continue
                    if(received.status==304) {
                        require(same)
                        val editor=p.edit();current.forEach { editor.putString(it.k("status"),"변경 없음 · 마지막 정상 목록 유지") };editor.apply();continue
                    }
                    require(received.status==200 && received.body.size<=REMOTE_MAX_BYTES)
                    val body=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(received.body)).toString()
                    val legacy=if(first.format=="LEGACY")parseRemoteLists(body)else null
                    val simple=if(first.format=="SIMPLE")parseSimpleRemoteList(body,first.kind)else null
                    val editor=p.edit()
                    current.forEach { config ->
                        val values=legacy?.values?.get(config.channel) ?: simple ?: emptyList()
                        val encoded=JSONArray(values).toString()
                        if(p.getString(config.k("cache_source"),"")!=config.source || p.getString(config.k("cache"),null)!=encoded)changed=true
                        editor.putString(config.k("cache"),encoded).putString(config.k("cache_source"),config.source).putString(config.k("etag"),received.etag)
                            .putLong(config.k("last_success"),now).putString(config.k("status"),"수신 완료 · ${values.size}개")
                    }
                    editor.apply()
                } catch(e:kotlinx.coroutines.CancellationException) { throw e }
                catch(e:SchedulePausedException) { throw e }
                catch(_:Exception) {
                    val editor=p.edit();due.filter { remoteChannelConfig(p,it.channel)==it }.forEach { editor.putString(it.k("status"),"수신 실패 · 같은 원본의 마지막 정상 목록 유지") };editor.apply()
                }
            }
            changed
        }
    }
}

private fun downloadRemoteLists(initial:String,etag:String?,beforeRequest:()->Unit):RemoteDownload {
    var url=initial
    repeat(2) {
        beforeRequest()
        val c=URI(url).toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=15000
        c.setRequestProperty("Accept","application/json,text/csv,text/plain");c.setRequestProperty("User-Agent","ArmbandBot-RemoteLists")
        if(!etag.isNullOrBlank())c.setRequestProperty("If-None-Match",etag)
        try {
            val status=c.responseCode
            if(status in 300..399 && status!=304) {
                val location=URI(url).resolve(c.getHeaderField("Location") ?: error("잘못된 리디렉션")).toString()
                require(it==0 && allowedRemoteRedirect(location));url=location
            } else {
                val data=if(status==200) {
                    require(c.contentLengthLong<=REMOTE_MAX_BYTES)
                    c.inputStream.use { stream ->
                        val buffer=ByteArray(8192);val out=java.io.ByteArrayOutputStream()
                        while(true) { val n=stream.read(buffer);if(n<0)break;out.write(buffer,0,n);require(out.size()<=REMOTE_MAX_BYTES) }
                        out.toByteArray()
                    }
                } else byteArrayOf()
                return RemoteDownload(status,data,c.getHeaderField("ETag"))
            }
        } finally { c.disconnect() }
    }
    error("원격 리디렉션 제한")
}
