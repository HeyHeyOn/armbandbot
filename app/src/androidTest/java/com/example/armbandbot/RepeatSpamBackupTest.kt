package com.heyheyon.armbandbot

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RepeatSpamBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun roundTrip(configure: android.content.SharedPreferences.Editor.() -> Unit) {
        val sourceId = "repeat_backup_source"
        val source = context.getSharedPreferences("bot_prefs_$sourceId", Context.MODE_PRIVATE)
        val master = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
        val originalIds = master.getString("bot_ids_list", null)
        val file = File(context.cacheDir, "repeat-backup-test.json")
        var restoredId: String? = null
        try {
            source.edit().clear().apply(configure).commit()
            val expected = repeatActionView(source)
            writeBotSettingsJson(context, Uri.fromFile(file).toString(), exportBotSettings(context, sourceId))
            restoredId = importBotSettingsAsNewBot(context, Uri.fromFile(file).toString())
            val restored = context.getSharedPreferences("bot_prefs_$restoredId", Context.MODE_PRIVATE)
            assertEquals(expected, repeatActionView(restored))
            assertEquals(repeatSpamSettings(source).minimumLength, repeatSpamSettings(restored).minimumLength)
            assertFalse(restored.contains(REPEAT_DURATION_KEY))
            assertFalse(restored.contains(REPEAT_DELETE_POST_KEY))
            assertFalse(restored.getBoolean("is_running", true))
            restored.edit().putInt("block_duration_hours", 24).commit()
            assertEquals(24, repeatActionView(restored).durationHours)
        } finally {
            source.edit().clear().commit()
            restoredId?.let { context.getSharedPreferences("bot_prefs_$it", Context.MODE_PRIVATE).edit().clear().commit() }
            master.edit().apply { if (originalIds == null) remove("bot_ids_list") else putString("bot_ids_list", originalIds) }.commit()
            file.delete()
        }
    }

    @Test fun minimumLengthZeroAndCustomValueSurviveActualFileImport() {
        for (minimum in listOf(0, 17, 8193)) roundTrip {
            putString(REPEAT_PROCESS_MODE_KEY, "BLOCK")
            putInt(REPEAT_MIN_LENGTH_KEY, minimum)
        }
    }

    @Test fun inheritedBlockOptionsSurviveActualFileImport() = roundTrip {
        putString(REPEAT_PROCESS_MODE_KEY, "BLOCK")
        putInt("block_duration_hours", 168)
        putBoolean("delete_post_on_block", false)
    }

    @Test fun defaultModesAndLegacyDeleteSurviveActualFileImport() {
        for (mode in listOf("HOLD", "DELETE", "BLOCK", "LEGACY_DELETE")) roundTrip {
            putBoolean(REPEAT_CUSTOM_ACTION_KEY, false)
            if (mode == "LEGACY_DELETE") putBoolean("delete_only_mode", true)
            else putString("block_process_mode", mode)
        }
    }
}
