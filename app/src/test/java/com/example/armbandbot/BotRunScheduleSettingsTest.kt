package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

class BotRunScheduleSettingsTest {
    private fun decision(time: String, vararg windows: BotRunWindow) = evaluateSchedule(
        Instant.parse(time).toEpochMilli(), ZoneId.of("UTC"), BotRunSchedule(true, windows.toList()))

    @Test fun orderedDisjointWindowsAndMillisecondBoundary() {
        val windows = arrayOf(BotRunWindow(600,660), BotRunWindow(900,960))
        assertEquals(ScheduleState.ACTIVE, decision("2026-09-08T10:00:00Z", *windows).state)
        assertEquals(1L, decision("2026-09-08T10:59:59.999Z", *windows).millisUntilBoundary)
        assertEquals(ScheduleDecision(ScheduleState.WAITING,14400000L), decision("2026-09-08T11:00:00Z", *windows))
        assertEquals(ScheduleState.ACTIVE, decision("2026-09-08T15:00:00Z", *windows).state)
    }
    @Test fun overlapTouchingAndOvernightHaveOnlyUnionBoundaries() {
        assertEquals(7200000L, decision("2026-09-08T10:00:00Z", BotRunWindow(600,660),BotRunWindow(650,720)).millisUntilBoundary)
        assertEquals(7200000L, decision("2026-09-08T10:00:00Z", BotRunWindow(600,660),BotRunWindow(660,720)).millisUntilBoundary)
        assertEquals(ScheduleState.ACTIVE, decision("2026-09-08T04:59:59Z", BotRunWindow(1320,300)).state)
        assertEquals(ScheduleState.WAITING, decision("2026-09-08T05:00:00Z", BotRunWindow(1320,300)).state)
    }
    @Test fun fullDayUnionHasNoBoundary() {
        for (minute in 0..1439) {
            val d = evaluateSchedule(minute * 60000L, ZoneId.of("UTC"), BotRunSchedule(true,listOf(BotRunWindow(0,720),BotRunWindow(720,0))))
            assertEquals(ScheduleDecision(ScheduleState.ACTIVE,Long.MAX_VALUE), d)
        }
    }
    @Test fun codecRoundTripsOrderAndRejectsMalformedInputs() {
        val windows = listOf(BotRunWindow(900,960),BotRunWindow(600,660))
        assertEquals(windows,decodeBotRunWindows(encodeBotRunWindows(windows)))
        listOf("[]", "null", "[{}]", "[{'startMinuteOfDay':0,'endMinuteOfDay':1}]", "[{\"startMinuteOfDay\":0.0,\"endMinuteOfDay\":1}]", "[{\"startMinuteOfDay\":\"0\",\"endMinuteOfDay\":1}]", "[{\"startMinuteOfDay\":0,\"endMinuteOfDay\":0}]", "[{\"startMinuteOfDay\":0,\"endMinuteOfDay\":1440}]", "[{\"startMinuteOfDay\":0,\"endMinuteOfDay\":1,\"extra\":1}]").forEach {
            assertThrows(IllegalArgumentException::class.java) { decodeBotRunWindows(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { encodeBotRunWindows(List(65) { BotRunWindow(0,1) }) }
        assertThrows(IllegalArgumentException::class.java) { decodeBotRunWindows(" ".repeat(16385)) }
    }
    @Test fun canonicalCorruptionIsExplicitAndPreservedByMigration() {
        val values = mapOf("run_schedule_windows_json" to "broken", "run_schedule_enabled" to true, "unknown" to "keep")
        val error = loadBotRunSchedule(values) as BotRunScheduleLoadResult.Error
        assertTrue(error.enabled)
        assertEquals("broken",error.rawCanonical)
        val migrated = migrateBotSettingsSnapshot(values)
        assertEquals("broken",migrated["run_schedule_windows_json"])
        assertEquals(true,migrated["run_schedule_enabled"])
        assertEquals("keep",migrated["unknown"])
    }
    @Test fun canonicalOverridesLegacyAndOldSchemasUpgrade() {
        val encoded = encodeBotRunWindows(listOf(BotRunWindow(900,960),BotRunWindow(600,660)))
        for (version in 1..4) {
            val json = JSONObject().put("schemaVersion",version).put("strings",JSONObject().put("run_schedule_windows_json",encoded)).put("booleans",JSONObject().put("run_schedule_enabled",true))
            val imported = parseAndMigrateBotSettingsExport(json)
            assertEquals(4,imported.schemaVersion)
            assertEquals(900,imported.ints["run_schedule_start_minute"])
            assertEquals(encoded,parseAndMigrateBotSettingsExport(imported.toJson()).strings["run_schedule_windows_json"])
            assertThrows(IllegalArgumentException::class.java) { parseAndMigrateBotSettingsExport(JSONObject().put("schemaVersion",version).put("strings",JSONObject().put("run_schedule_windows_json","broken"))) }
            val old = parseAndMigrateBotSettingsExport(JSONObject().put("schemaVersion",version))
            assertEquals(listOf(BotRunWindow(0,1439)),decodeBotRunWindows(old.strings.getValue("run_schedule_windows_json")))
        }
    }
    @Test fun savePublishesAllScheduleFieldsInOneApplyAndPreservesOtherKeys() {
        val stored = mutableMapOf<String, Any>("unknown" to "keep")
        val pending = mutableMapOf<String, Any>()
        var edits = 0
        var applies = 0
        lateinit var editor: android.content.SharedPreferences.Editor
        editor = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.content.SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString", "putInt", "putBoolean" -> { pending[args!![0] as String] = args[1]; editor }
                "apply" -> { applies++; stored.putAll(pending); null }
                else -> error(method.name)
            }
        } as android.content.SharedPreferences.Editor
        val preferences = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.content.SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "edit" -> { edits++; editor }
                "getAll" -> stored.toMap()
                else -> error(method.name)
            }
        } as android.content.SharedPreferences
        val schedule = BotRunSchedule(true,listOf(BotRunWindow(900,960),BotRunWindow(600,660)))
        saveBotRunSchedule(preferences,schedule)
        assertEquals(1,edits)
        assertEquals(1,applies)
        assertEquals(4,pending.size)
        assertEquals("keep",stored["unknown"])
        assertEquals(900,stored["run_schedule_start_minute"])
        assertEquals(BotRunScheduleLoadResult.Valid(schedule),loadBotRunSchedule(preferences))
    }
    @Test fun invalidLegacyCannotBecomeEnabled() {
        val result = loadBotRunSchedule(mapOf("run_schedule_enabled" to true,"run_schedule_start_minute" to 30,"run_schedule_end_minute" to 30)) as BotRunScheduleLoadResult.Valid
        assertFalse(result.schedule.enabled)
        assertEquals(NormalizedRunScheduleSettings(false,30,30),result.legacyEditor)
    }
}
