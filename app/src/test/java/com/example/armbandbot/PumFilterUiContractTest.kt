package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PumFilterUiContractTest {
    private fun source(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/armbandbot/$relativePath"),
            File("app/src/main/java/com/example/armbandbot/$relativePath"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Source file not found: $relativePath; cwd=${File(".").absolutePath}")
    }

    @Test
    fun `PUM filter follows word filter before actor filters`() {
        val source = source("BotDetailScreen.kt")
        val wordIndex = source.indexOf("ModernSettingItem(\"금지어 필터\"")
        val pumIndex = source.indexOf("ModernSettingItem(\"펌 필터\"")
        val userIndex = source.indexOf("ModernSettingItem(\"유저 ID/IP 필터\"")

        assertTrue(wordIndex >= 0)
        assertTrue(pumIndex > wordIndex)
        assertTrue(userIndex > pumIndex)
    }

    @Test
    fun `PUM filter switch means block all and has no source inspection toggle`() {
        val detail = source("PumFilterSettingsPanel.kt")
        val screen = source("BotDetailScreen.kt")

        assertTrue(detail.contains("title = \"펌 게시글 모두 차단\""))
        assertTrue(detail.contains("description = \"구조적으로 확인된 펌 게시글을 내용과 관계없이 차단합니다.\""))
        assertFalse(detail.contains("is_pum_source_filter_mode"))
        assertTrue(screen.contains("putBoolean(\"pum_block_all_posts\", it)"))
        assertFalse(screen.contains("putBoolean(\"is_pum_source_filter_mode\", it)"))
    }

    @Test
    fun `PUM block reason uses the shared read only card and dialog UX`() {
        val panel = source("PumFilterSettingsPanel.kt")
        val screen = source("BotDetailScreen.kt")

        assertTrue(panel.contains("ReadOnlyTextCard("))
        assertFalse(panel.contains("OutlinedTextField("))
        assertTrue(screen.contains("editDialogType = \"pum_block_reason\""))
        assertTrue(screen.contains("\"pum_block_reason\" -> \"펌 필터 차단 사유 설정\""))
        assertFalse(panel.contains("비워 두면 차단 기본 설정의 사유를 사용합니다."))
    }

    @Test
    fun `runtime always parses and resolves confirmed PUM sources`() {
        val service = source("BotService.kt")

        assertTrue(service.contains("shouldInspect = true"))
        assertTrue(service.contains("val pumSourceResolver = PumSourceResolver("))
        assertTrue(service.contains("if (effectivePumStructuralState == PumStructuralState.DETECTED)"))
        assertFalse(service.contains("initialPumDecision"))
        assertFalse(service.contains("if (!config.isPumSourceFilterMode"))
        assertFalse(service.contains("shouldRunAiStage && config.isPumSourceFilterMode"))
    }
}
