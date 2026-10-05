package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class IndependentRemoteListsTest {
    @Test fun simpleGithubTextNeedsNoTypesOrHeaders() {
        assertEquals(listOf("첫째", "normal", "#문자열", "쉼표,문자열"), parseSimpleRemoteList("\uFEFF첫째\n\n normal \r\n#문자열\n쉼표,문자열\n첫째", RemoteSourceKind.GITHUB))
    }
    @Test fun sheetsOneColumnCsvPreservesQuotedValuesAndSkipsEmptyCells() {
        assertEquals(listOf("금지어", "쉼표,문자열", "따옴표\"값"), parseSimpleRemoteList("금지어\r\n\"쉼표,문자열\"\r\n\r\n\"따옴표\"\"값\"\r\n", RemoteSourceKind.SHEETS))
    }
    @Test fun appsScriptAcceptsStringArrayAndPlainLinesWithoutCategoryNames() {
        assertEquals(listOf("hello", "world"), parseSimpleRemoteList("[\"hello\",\"world\",\"hello\"]", RemoteSourceKind.JSON))
        assertEquals(listOf("hello", "world"), parseSimpleRemoteList("hello\nworld", RemoteSourceKind.JSON))
    }
    @Test fun explicitEmptyArraysAndEmptySheetsClearOnlyRemoteValues() {
        assertEquals(emptyList<String>(), parseSimpleRemoteList("[]", RemoteSourceKind.JSON))
        assertEquals(emptyList<String>(), parseSimpleRemoteList("\n\r\n", RemoteSourceKind.SHEETS))
    }
    @Test fun malformedStructuredLoginErrorAndMulticolumnSheetsAreRejected() {
        listOf("<html>login</html>", "{\"error\":\"login\"}", "[1]", "[\"ok\",null]", "[\"not closed").forEach {
            assertTrue(it, runCatching { parseSimpleRemoteList(it,RemoteSourceKind.JSON) }.isFailure)
        }
        assertTrue(runCatching { parseSimpleRemoteList("a,b\n",RemoteSourceKind.SHEETS) }.isFailure)
        assertTrue(runCatching { parseSimpleRemoteList("\"unterminated",RemoteSourceKind.SHEETS) }.isFailure)
        assertTrue(runCatching { parseSimpleRemoteList("x".repeat(REMOTE_MAX_BYTES+1),RemoteSourceKind.GITHUB) }.isFailure)
    }
    @Test fun whiteAndBlackChannelsHaveDifferentConfigurationKeys() {
        assertEquals(7,REMOTE_CHANNEL_KEYS.size)
        assertEquals(7,REMOTE_CHANNEL_KEYS.map { remoteListPrefKey(it,"url") }.distinct().size)
        assertTrue("user_whitelist" in REMOTE_CHANNEL_KEYS && "nickname_whitelist" in REMOTE_CHANNEL_KEYS)
    }
    @Test fun upgradePreservesTypedSourcesCachesLocalListsAndOffState() {
        val url="https://raw.githubusercontent.com/owner/repo/main/lists.json"
        val old=mapOf<String,Any>(REMOTE_ENABLED_KEY to true,"remote_lists_kind" to "GITHUB","remote_lists_url" to url,"remote_lists_cache_source" to url,"remote_lists_cache" to "{\"version\":1,\"lists\":{\"normal\":[\"remote\"],\"user_blacklist\":[\"bad\"]}}", "banned_normal_text" to "local")
        val result=migrateRemoteListSnapshot(old)
        assertEquals("local",result["banned_normal_text"])
        assertEquals(true,result[remoteListPrefKey("normal","enabled")])
        assertEquals("LEGACY",result[remoteListPrefKey("normal","format")])
        assertEquals(url,result[remoteListPrefKey("normal","url")])
        assertEquals("[\"remote\"]",result[remoteListPrefKey("normal","cache")])
        assertFalse(result[remoteListPrefKey("user_whitelist","enabled")] as Boolean)
        assertEquals(result,migrateRemoteListSnapshot(result))
        assertFalse(migrateRemoteListSnapshot(old+mapOf(REMOTE_ENABLED_KEY to false))[remoteListPrefKey("normal","enabled")] as Boolean)
    }
    @Test fun partiallyExistingChannelIsNotOverwrittenByUpgrade() {
        val key=remoteListPrefKey("normal","url")
        val old=mapOf<String,Any>(REMOTE_ENABLED_KEY to true,"remote_lists_kind" to "GITHUB","remote_lists_url" to "https://raw.githubusercontent.com/a/b/main/old.json",key to "https://raw.githubusercontent.com/a/b/main/new.txt",remoteListPrefKey("normal","enabled") to false)
        val result=migrateRemoteListSnapshot(old)
        assertEquals(old[key],result[key]);assertEquals(false,result[remoteListPrefKey("normal","enabled")])
    }
}
