package com.heyheyon.armbandbot
import android.content.Context
import android.widget.TimePicker
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matcher
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.heyheyon.armbandbot.ui.botColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PostAutomationUiTest {
 private fun pick(hour:Int,minute:Int) {
  onView(isAssignableFrom(TimePicker::class.java)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(object:ViewAction {
   override fun getConstraints():Matcher<View> = isAssignableFrom(TimePicker::class.java)
   override fun getDescription()="choose bump time"
   override fun perform(ui:UiController,view:View) {(view as TimePicker).apply {this.hour=hour;this.minute=minute};ui.loopMainThreadUntilIdle()}
  })
  onView(withId(android.R.id.button1)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click());compose.waitForIdle()
 }
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
   compose.onNodeWithTag("bump-time-remove-540").performClick()
   compose.onNodeWithTag("bump-save").performScrollTo().performClick()
   compose.onNodeWithTag("automation-error").assertExists();assertFalse(p.contains(BUMP_RULES_KEY))
   compose.onNodeWithTag("bump-time-add").performScrollTo().performClick();pick(9,0)
   compose.onNodeWithTag("bump-time-add").performScrollTo().performClick();pick(18,30)
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
  compose.onNodeWithTag("remote-list-enabled").assertDoesNotExist()
  compose.onNodeWithTag("remote-list-save").assertDoesNotExist()
  compose.runOnIdle {assertFalse(p.getBoolean(REMOTE_ENABLED_KEY,false))}
 }
 @Test fun remoteInvalidUrlStaysDraftAndDefaultsAreOff() {
  val p=prefs;p.edit().clear().putString("remote_lists_url","https://raw.githubusercontent.com/a/b/main/list.json").commit()
  try {
   compose.setContent { MaterialTheme { RemoteListSettingsDialog(p,"normal",botColors(false),onDismiss={}) } }
   compose.onNodeWithTag("move-enabled").assertDoesNotExist()
   compose.onNodeWithTag("remote-list-enabled").performScrollTo().assertIsOff()
   compose.onNodeWithTag("remote-list-url").performScrollTo().performTextReplacement("https://evil.example/x")
   compose.onNodeWithTag("remote-list-save").performClick()
   assertEquals("https://raw.githubusercontent.com/a/b/main/list.json",p.getString("remote_lists_url",null))
   compose.onNodeWithTag("remote-list-error").assertExists()
  } finally { p.edit().clear().commit() }
 }
}
