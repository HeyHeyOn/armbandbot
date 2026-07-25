package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Test

class BotSettingsTransferTest {
    @Test
    fun pumSettingsRoundTripWithBothValues() {
        listOf(false, true).forEach { sourceEnabled ->
            listOf(false, true).forEach { recheckEveryCycle ->
                val original = BotSettingsExport(
                    botName = "PUM 설정 봇",
                    strings = emptyMap(),
                    booleans = mapOf(
                        "is_pum_source_filter_mode" to sourceEnabled,
                        "pum_recheck_every_cycle" to recheckEveryCycle,
                    ),
                    ints = emptyMap(),
                    floats = emptyMap(),
                    stringSets = emptyMap(),
                )

                val imported = parseAndMigrateBotSettingsExport(original.toJson())

                assertEquals(sourceEnabled, imported.booleans["is_pum_source_filter_mode"])
                assertEquals(recheckEveryCycle, imported.booleans["pum_recheck_every_cycle"])
            }
        }
    }

    @Test
    fun legacyTransferDefaultsPumSettingsToFalse() {
        val imported = parseAndMigrateBotSettingsExport(
            BotSettingsExport(
                botName = "구형 봇",
                strings = emptyMap(),
                booleans = emptyMap(),
                ints = emptyMap(),
                floats = emptyMap(),
                stringSets = emptyMap(),
            ).toJson()
        )

        assertEquals(false, imported.booleans["is_pum_source_filter_mode"])
        assertEquals(false, imported.booleans["pum_recheck_every_cycle"])
    }
}
