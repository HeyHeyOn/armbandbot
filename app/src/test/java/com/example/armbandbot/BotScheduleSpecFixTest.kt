package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

class BotScheduleSpecFixTest {
    @Test fun equalLegacySurvivesRepeatedMigrationAndExportImport() {
        for (enabled in listOf(false, true)) for (minute in listOf(0, 300, 1439)) {
            val original = mapOf("run_schedule_enabled" to enabled, "run_schedule_start_minute" to minute, "run_schedule_end_minute" to minute)
            val migrated = migrateBotSettingsSnapshot(original)
            assertEquals(minute, migrated["run_schedule_start_minute"])
            assertEquals(minute, migrated["run_schedule_end_minute"])
            assertEquals(false, migrated["run_schedule_enabled"])
            assertFalse(migrated.containsKey(RUN_SCHEDULE_WINDOWS_JSON_KEY))
            assertEquals(migrated, migrateBotSettingsSnapshot(migrated))
            val imported = parseAndMigrateBotSettingsExport(JSONObject().put("schemaVersion", 3)
                .put("booleans", JSONObject().put("run_schedule_enabled", enabled))
                .put("ints", JSONObject().put("run_schedule_start_minute", minute).put("run_schedule_end_minute", minute)))
            val roundTrip = parseAndMigrateBotSettingsExport(imported.toJson())
            assertEquals(imported, roundTrip)
            val copied = prepareImportedSettingsForNewBot(roundTrip)
            assertEquals(minute, copied["run_schedule_start_minute"])
            assertEquals(minute, copied["run_schedule_end_minute"])
            assertFalse(copied.containsKey(RUN_SCHEDULE_WINDOWS_JSON_KEY))
        }
    }
    @Test fun canonicalEqualEndpointsRemainErrorsRegardlessOfEnabled() {
        for (enabled in listOf(false, true)) {
            val raw = """[{"startMinuteOfDay":300,"endMinuteOfDay":300}]"""
            assertTrue(loadBotRunSchedule(mapOf(RUN_SCHEDULE_WINDOWS_JSON_KEY to raw, "run_schedule_enabled" to enabled)) is BotRunScheduleLoadResult.Error)
            assertThrows(IllegalArgumentException::class.java) {
                parseAndMigrateBotSettingsExport(JSONObject().put("strings", JSONObject().put(RUN_SCHEDULE_WINDOWS_JSON_KEY, raw)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { BotRunSchedule(true, 300, 300) }
        assertThrows(IllegalArgumentException::class.java) { BotRunSchedule(false, 300, 300) }
    }
    private fun check(now: String, boundary: String, state: ScheduleState, vararg windows: BotRunWindow) {
        val instant = Instant.parse(now)
        assertEquals(ScheduleDecision(state, Instant.parse(boundary).toEpochMilli() - instant.toEpochMilli()),
            evaluateSchedule(instant.toEpochMilli(), ZoneId.of("America/New_York"), BotRunSchedule(true, windows.toList())))
    }
    @Test fun springGapMaskedEndpointsDoNotEndUnion() {
        check("2026-03-08T06:45:00Z", "2026-03-08T08:00:00Z", ScheduleState.ACTIVE,
            BotRunWindow(90, 150), BotRunWindow(105, 240))
    }
    @Test fun springGapTransitionChangesUnionWithoutRealLocalEndpoint() {
        check("2026-03-08T06:59:59.999Z", "2026-03-08T07:00:00Z", ScheduleState.ACTIVE,
            BotRunWindow(90, 150), BotRunWindow(600, 660))
        check("2026-03-08T07:00:00Z", "2026-03-08T14:00:00Z", ScheduleState.WAITING,
            BotRunWindow(90, 150), BotRunWindow(600, 660))
    }
    @Test fun fallbackBothOffsetsMaskInnerEndpointAndTransitionChangesUnion() {
        val rows = arrayOf(BotRunWindow(70, 100), BotRunWindow(90, 110))
        check("2026-11-01T05:35:00Z", "2026-11-01T05:50:00Z", ScheduleState.ACTIVE, *rows)
        check("2026-11-01T06:35:00Z", "2026-11-01T06:50:00Z", ScheduleState.ACTIVE, *rows)
        check("2026-11-01T05:59:59.999Z", "2026-11-01T06:00:00Z", ScheduleState.ACTIVE,
            BotRunWindow(90, 150), BotRunWindow(600, 660))
        check("2026-11-01T06:00:00Z", "2026-11-01T06:30:00Z", ScheduleState.WAITING,
            BotRunWindow(90, 150), BotRunWindow(600, 660))
    }
    @Test fun fallbackOvernightUnsortedDuplicateOverlapHasOnlyUnionBoundary() {
        check("2026-11-01T05:45:00Z", "2026-11-01T08:00:00Z", ScheduleState.ACTIVE,
            BotRunWindow(90, 180), BotRunWindow(1320, 120), BotRunWindow(90, 180))
    }
}
