package com.heyheyon.armbandbot
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PostAutomationUiTest {
 @get:Rule val compose=createComposeRule()
 @get:Rule val testName=org.junit.rules.TestName()
 @org.junit.After fun captureEvidence() {
  compose.waitForIdle()
  val i=InstrumentationRegistry.getInstrumentation()
  val image=i.uiAutomation.takeScreenshot() ?: return
  java.io.File(i.targetContext.getExternalFilesDir(null),testName.methodName+".png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
  image.recycle()
 }
 private val prefs get()=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("automation_ui_fixture",Context.MODE_PRIVATE)
 @Test fun invalidDraftNeverOverwritesSavedScheduleAndValidSaveReopens() {
  val p=prefs;p.edit().clear().putString("target_urls","https://gall.dcinside.com/mgallery/board/lists/?id=laboratory1").commit()
  try {
   compose.setContent { MaterialTheme { PostAutomationSettingsScreen(p,botColors(true),onBack={}) } }
   compose.onNodeWithTag("bump-enabled").assertIsOff()
   compose.onNodeWithTag("bump-url").performTextReplacement("https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2361")
   compose.onNodeWithTag("bump-times").performTextReplacement("24:00")
   compose.onNodeWithTag("bump-save").performScrollTo().performClick()
   compose.onNodeWithTag("automation-error").assertExists();assertFalse(p.contains(BUMP_RULES_KEY))
   compose.onNodeWithTag("bump-times").performTextReplacement("09:00, 18:30")
   compose.onNodeWithTag("bump-save").performScrollTo().performClick()
   assertEquals(listOf(540,1110),parseBumpRules(p.getString(BUMP_RULES_KEY,"[]")!!).single().minutes)
   compose.onNodeWithTag("bump-enabled").performScrollTo().performClick().assertIsOn()
   compose.onNodeWithTag("bump-enabled").performClick().assertIsOff()
   assertEquals(1,parseBumpRules(p.getString(BUMP_RULES_KEY,"[]")!!).size)
  } finally { p.edit().clear().commit() }
 }
 @Test fun darkPageControlsRemainPresentAndDisabledByDefault() {
  val p=prefs
  p.edit().clear().commit()
  compose.setContent {MaterialTheme {PostAutomationSettingsScreen(p,botColors(true)) {}}}
  compose.onNodeWithTag("bump-enabled").assertIsOff().assertIsDisplayed()
  compose.onNodeWithTag("bump-save").performScrollTo().assertIsDisplayed()
  compose.onNodeWithTag("remote-enabled").performScrollTo().assertIsOff()
  compose.onNodeWithTag("remote-save").performScrollTo().assertIsDisplayed()
  compose.runOnIdle {assertFalse(p.getBoolean(REMOTE_ENABLED_KEY,false))}
 }
 @Test fun remoteInvalidUrlStaysDraftAndDefaultsAreOff() {
  val p=prefs;p.edit().clear().putString("remote_lists_url","https://raw.githubusercontent.com/a/b/main/list.json").commit()
  try {
   compose.setContent { MaterialTheme { PostAutomationSettingsScreen(p,botColors(false),onBack={}) } }
   compose.onNodeWithTag("move-enabled").performScrollTo().assertIsOff()
   compose.onNodeWithTag("remote-enabled").performScrollTo().assertIsOff()
   compose.onNodeWithTag("remote-url").performScrollTo().performTextReplacement("https://evil.example/x")
   compose.onNodeWithTag("remote-save").performScrollTo().performClick()
   assertEquals("https://raw.githubusercontent.com/a/b/main/list.json",p.getString("remote_lists_url",null))
   compose.onNodeWithTag("automation-error").assertExists()
  } finally { p.edit().clear().commit() }
 }
}
