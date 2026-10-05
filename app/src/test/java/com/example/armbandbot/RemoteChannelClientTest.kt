package com.heyheyon.armbandbot

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteChannelClientTest {
    private fun configured(vararg channels:String):MemoryPreferences = MemoryPreferences().apply {
        edit().putInt(REMOTE_CHANNEL_VERSION_KEY,1).apply()
        channels.forEach { c -> edit().putBoolean(remoteListPrefKey(c,"enabled"),true).putString(remoteListPrefKey(c,"kind"),"GITHUB").putString(remoteListPrefKey(c,"url"),"https://raw.githubusercontent.com/a/b/main/$c.txt").putString(remoteListPrefKey(c,"format"),"SIMPLE").apply() }
    }
    @Test fun cachesAndPollIntervalsAreIndependentForWhiteAndBlack() = runBlocking {
        val p=configured("user_blacklist","user_whitelist")
        val requests=mutableListOf<String>()
        val client=RemoteListClient { url,_ -> requests.add(url); RemoteDownload(200,if(url.contains("whitelist")) "trusted".toByteArray() else "bad".toByteArray(),"etag") }
        assertTrue(client.sync(p,now=1000))
        assertEquals(2,requests.size)
        assertEquals(listOf("bad"),effectiveRemoteLists(p)?.values?.get("user_blacklist"))
        assertEquals(listOf("trusted"),effectiveRemoteLists(p)?.values?.get("user_whitelist"))
        assertFalse(client.sync(p,now=2000));assertEquals(2,requests.size)
        p.edit().putBoolean(remoteListPrefKey("user_whitelist","enabled"),false).apply()
        assertNull(effectiveRemoteLists(p)?.values?.get("user_whitelist"))
        assertEquals(listOf("bad"),effectiveRemoteLists(p)?.values?.get("user_blacklist"))
    }
    @Test fun failedAndChangedSourcesNeverLeakCaches() = runBlocking {
        val p=configured("normal")
        assertTrue(RemoteListClient { _,_->RemoteDownload(200,"good".toByteArray(),"v1") }.syncList(p,"normal",force=true,now=1000))
        assertFalse(RemoteListClient { _,_->RemoteDownload(500,byteArrayOf(),null) }.syncList(p,"normal",force=true,now=2000))
        assertEquals(listOf("good"),effectiveRemoteLists(p)?.values?.get("normal"))
        p.edit().putString(remoteListPrefKey("normal","url"),"https://raw.githubusercontent.com/a/b/main/other.txt").apply()
        assertNull(effectiveRemoteLists(p))
        assertFalse(RemoteListClient { _,_->RemoteDownload(304,byteArrayOf(),null) }.syncList(p,"normal",force=true,now=3000))
        assertNull(effectiveRemoteLists(p))
    }
    @Test fun inFlightConfigGenerationChangesDiscardResponse() = runBlocking {
        val p=configured("normal")
        val client=RemoteListClient { _,_->p.edit().putLong(remoteListPrefKey("normal","generation"),1).apply();RemoteDownload(200,"stale".toByteArray(),null) }
        assertFalse(client.syncList(p,"normal",force=true,now=1000));assertNull(effectiveRemoteLists(p))
    }
    @Test fun emptyRemoteClearsOnlyItsCacheAndLocalValuesRemain() = runBlocking {
        val p=configured("normal");p.edit().putString("banned_normal_text","local").apply()
        assertTrue(RemoteListClient { _,_->RemoteDownload(200,"bad".toByteArray(),null) }.sync(p,force=true,now=1000))
        assertTrue(RemoteListClient { _,_->RemoteDownload(200,"".toByteArray(),null) }.sync(p,force=true,now=2000))
        assertEquals(listOf("local"),mergeRemoteList(listOf("local"),effectiveRemoteLists(p),"banned_normal"))
        assertEquals("local",p.getString("banned_normal_text",null))
    }
    @Test fun oneLegacyDownloadUpdatesAllLegacyChannelsWithoutOverwritingSimpleOne() = runBlocking {
        val p=MemoryPreferences();p.edit().putBoolean(REMOTE_ENABLED_KEY,true).putString("remote_lists_kind","GITHUB").putString("remote_lists_url","https://raw.githubusercontent.com/a/b/main/old.json").apply()
        var calls=0
        assertTrue(RemoteListClient { _,_->calls++;RemoteDownload(200,"{\"version\":1,\"lists\":{\"normal\":[\"one\"],\"user_blacklist\":[\"bad\"]}}".toByteArray(),null) }.sync(p,force=true,now=1000))
        assertEquals(1,calls)
        assertEquals(listOf("one"),effectiveRemoteLists(p)?.values?.get("normal"))
        assertEquals(listOf("bad"),effectiveRemoteLists(p)?.values?.get("user_blacklist"))
    }
    @Test fun importedAndCopiedChannelsKeepSourcesButLoseEnablementAndCaches() {
        val c="user_whitelist"
        val snapshot=mapOf<String,Any>(REMOTE_CHANNEL_VERSION_KEY to 1,remoteListPrefKey(c,"enabled") to true,remoteListPrefKey(c,"url") to "https://raw.githubusercontent.com/a/b/main/white.txt",remoteListPrefKey(c,"cache") to "[\"trusted\"]")
        val copied=prepareCopiedBotSettingsSnapshot(snapshot,"copy")
        assertEquals(false,copied[remoteListPrefKey(c,"enabled")]);assertEquals(snapshot[remoteListPrefKey(c,"url")],copied[remoteListPrefKey(c,"url")]);assertFalse(copied.containsKey(remoteListPrefKey(c,"cache")))
        assertFalse(EXPORTABLE_STRING_KEYS.any { it.contains("cache") || it.contains("generation") })
        assertTrue(remoteListPrefKey(c,"url") in EXPORTABLE_STRING_KEYS)
    }
}

internal class MemoryPreferences:SharedPreferences {
    private val values=linkedMapOf<String,Any?>()
    override fun getAll():MutableMap<String,*> = values.toMutableMap()
    override fun getString(key:String?,def:String?):String? = values[key] as? String ?: def
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key:String?,def:MutableSet<String>?):MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: def
    override fun getInt(key:String?,def:Int):Int = values[key] as? Int ?: def
    override fun getLong(key:String?,def:Long):Long = values[key] as? Long ?: def
    override fun getFloat(key:String?,def:Float):Float = values[key] as? Float ?: def
    override fun getBoolean(key:String?,def:Boolean):Boolean = values[key] as? Boolean ?: def
    override fun contains(key:String?):Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit():SharedPreferences.Editor = object:SharedPreferences.Editor {
        private val pending=linkedMapOf<String,Any?>();private var clear=false
        override fun putString(k:String?,v:String?):SharedPreferences.Editor=apply { pending[k!!]=v }
        override fun putStringSet(k:String?,v:MutableSet<String>?):SharedPreferences.Editor=apply { pending[k!!]=v?.toSet() }
        override fun putInt(k:String?,v:Int):SharedPreferences.Editor=apply { pending[k!!]=v }
        override fun putLong(k:String?,v:Long):SharedPreferences.Editor=apply { pending[k!!]=v }
        override fun putFloat(k:String?,v:Float):SharedPreferences.Editor=apply { pending[k!!]=v }
        override fun putBoolean(k:String?,v:Boolean):SharedPreferences.Editor=apply { pending[k!!]=v }
        override fun remove(k:String?):SharedPreferences.Editor=apply { pending[k!!]=null }
        override fun clear():SharedPreferences.Editor=apply { clear=true }
        override fun commit():Boolean { if(clear)values.clear();pending.forEach { (k,v)->if(v==null)values.remove(k) else values[k]=v };return true }
        override fun apply() { commit() }
    }
}
