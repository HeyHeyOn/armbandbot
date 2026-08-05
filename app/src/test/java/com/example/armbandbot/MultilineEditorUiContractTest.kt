package com.heyheyon.armbandbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MultilineEditorUiContractTest {
    private fun source(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/armbandbot/$relativePath"),
            File("app/src/main/java/com/example/armbandbot/$relativePath"),
        )
        return (candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Source file not found: $relativePath; cwd=${File(".").absolutePath}"))
            .replace("\r\n", "\n")
    }

    @Test
    fun `multiline editor retains cursor selection and opens at the list end`() {
        val source = source("BotDetailScreen.kt")

        assertTrue(source.contains("var tempEditValue by remember { mutableStateOf(TextFieldValue()) }"))
        assertTrue(source.contains("TextFieldValue(tempEditText, selection = TextRange(tempEditText.length))"))
        assertTrue(source.contains("value = tempEditValue"))
        assertTrue(source.contains("tempEditText = updatedValue.text"))
    }

    @Test
    fun `multiline editor uses keyboard aware large dialog instead of fixed 250 dp field`() {
        val source = source("BotDetailScreen.kt")

        assertTrue(source.contains("DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)"))
        assertTrue(source.contains("Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.88f).imePadding()"))
        assertTrue(source.contains("Modifier.fillMaxWidth().fillMaxHeight(0.72f)"))
        assertTrue(!source.contains("Modifier.fillMaxWidth().height(250.dp)"))
    }

    @Test
    fun `generic ordered lists save normalized text and runtime set together`() {
        val source = source("BotDetailScreen.kt")

        assertTrue(source.contains("persistOrderedMultilineText(botPref, \"bypass\", tempEditText)"))
        assertTrue(source.contains("persistOrderedMultilineText(botPref, \"user_blacklist\", tempEditText)"))
        assertTrue(source.contains("loadOrderedMultilineText(botPref, \"user_blacklist\")"))
    }
}
