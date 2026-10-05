package com.heyheyon.armbandbot
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteListClientTest {
 private val p get()=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("remote_integration_fixture",Context.MODE_PRIVATE)
 private val url="https://raw.githubusercontent.com/a/b/main/list.json"
 private fun setup(){p.edit().clear().putBoolean(REMOTE_ENABLED_KEY,true).putString("remote_lists_kind","GITHUB").putString("remote_lists_url",url).putString("banned_normal","local").commit()}
 @Test fun successFailureNotModifiedAndDisablePreserveLocal()=runBlocking {
  setup();var calls=0;var fail=false;var notModified=false
  val client=RemoteListClient { _,etag ->
   calls++;if(fail)throw java.io.IOException("fixture outage")
   if(notModified){assertEquals("v1",etag);RemoteDownload(304,byteArrayOf(),null)} else RemoteDownload(200,"{\"version\":1,\"lists\":{\"normal\":[\"shared\"]}}".toByteArray(),"v1")
  }
  try {
   assertTrue(client.sync(p,force=true,now=100000L));assertEquals(listOf("local","shared"),mergeRemoteList(listOf("local"),effectiveRemoteLists(p),"normal"))
   assertFalse(client.sync(p,now=100001L));assertEquals(1,calls)
   fail=true;assertFalse(client.sync(p,force=true,now=101000L));assertEquals(listOf("shared"),effectiveRemoteLists(p)!!.values["normal"])
   fail=false;notModified=true;assertFalse(client.sync(p,force=true,now=102000L));assertEquals(listOf("shared"),effectiveRemoteLists(p)!!.values["normal"])
   p.edit().also {e->REMOTE_CHANNEL_KEYS.forEach {e.putBoolean(remoteListPrefKey(it,"enabled"),false)}}.commit();assertEquals(listOf("local"),mergeRemoteList(listOf("local"),effectiveRemoteLists(p),"normal"));assertEquals("local",p.getString("banned_normal",null))
  } finally {p.edit().clear().commit()}
 }
 @Test fun staleSourcePublicationAndInvalidUtf8AreRejected()=runBlocking {
  setup()
  try {
   val stale=RemoteListClient { _,_->p.edit().also {e->REMOTE_CHANNEL_KEYS.forEach {e.putString(remoteListPrefKey(it,"url"),"https://raw.githubusercontent.com/a/b/main/other.json")}}.commit();RemoteDownload(200,"{\"version\":1,\"lists\":{\"normal\":[\"stale\"]}}".toByteArray(),null) }
   assertFalse(stale.sync(p,force=true));assertFalse(p.contains(remoteListPrefKey("normal","cache")))
   setup();val bad=RemoteListClient { _,_->RemoteDownload(200,byteArrayOf(0xc3.toByte(),0x28),null) }
   assertFalse(bad.sync(p,force=true));assertFalse(p.contains(remoteListPrefKey("normal","cache")))
  } finally {p.edit().clear().commit()}
 }
 @Test fun downloaderNeverHoldsPlatformPreferencesMonitor()=runBlocking {
  setup()
  try {
   val client=RemoteListClient { _,_->
    assertFalse("SharedPreferences disk writer must remain able to acquire its monitor",Thread.holdsLock(p))
    p.edit().putString("owned_fixture_probe","retained").commit()
    RemoteDownload(200,"""{"version":1,"lists":{"normal":["shared"]}}""".toByteArray(),null)
   }
   assertTrue(client.sync(p,force=true))
   assertEquals("retained",p.getString("owned_fixture_probe",null))
  }finally{p.edit().clear().commit()}
 }
 @Test fun pauseIsControlFlowAndSendsNothing()=runBlocking {
  setup();var sent=false
  try {
   val client=RemoteListClient { _,_->sent=true;RemoteDownload(200,"{}".toByteArray(),null) }
   try {client.sync(p,force=true,beforeRequest={throw SchedulePausedException()});fail("pause swallowed")}catch(_:SchedulePausedException){}
   assertFalse(sent);assertFalse(p.contains(remoteListPrefKey("normal","cache")))
  }finally{p.edit().clear().commit()}
 }
}
