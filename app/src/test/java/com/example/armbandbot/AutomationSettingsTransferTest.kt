package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test
class AutomationSettingsTransferTest {
 @Test fun copyingDoesNotDuplicateActiveAutomationOrRemoteCache() {
  val result=prepareCopiedBotSettingsSnapshot(mapOf(BUMP_ENABLED_KEY to true, MOVE_ENABLED_KEY to true, REMOTE_ENABLED_KEY to true, "remote_lists_cache" to "private cache", "saved_cookie" to "existing session"), "copy")
  assertEquals(false,result[BUMP_ENABLED_KEY]);assertEquals(false,result[MOVE_ENABLED_KEY]);assertEquals(false,result[REMOTE_ENABLED_KEY]);assertFalse(result.containsKey("remote_lists_cache"));assertEquals("existing session",result["saved_cookie"])
 }
 @Test fun importKeepsRulesButNeverAutoEnablesExternalActions() {
  val backup=BotSettingsExport(botName="automation",strings=mapOf(BUMP_RULES_KEY to "[]",MOVE_RULES_KEY to "[]","remote_lists_url" to "https://raw.githubusercontent.com/a/b/main/list.json","remote_lists_kind" to "GITHUB"),booleans=mapOf(BUMP_ENABLED_KEY to true,MOVE_ENABLED_KEY to true,REMOTE_ENABLED_KEY to true),ints=mapOf("remote_lists_interval_minutes" to 15),floats=emptyMap(),stringSets=emptyMap())
  val restored=parseAndMigrateBotSettingsExport(backup.toJson())
  assertEquals(backup.strings,restored.strings.filterKeys { it in backup.strings })
  val values=prepareImportedSettingsForNewBot(restored)
  listOf(BUMP_ENABLED_KEY,MOVE_ENABLED_KEY,REMOTE_ENABLED_KEY).forEach { assertEquals(false,values[it]) }
  assertEquals(15,values["remote_lists_interval_minutes"])
 }
 @Test fun runtimeHistoryAndRemoteCacheNeverEnterSettingsBackup() {
  listOf("remote_lists_cache_json","remote_lists_cache_source","remote_lists_etag","automation_last_status").forEach { assertFalse(it in EXPORTABLE_STRING_KEYS) }
 }
}
