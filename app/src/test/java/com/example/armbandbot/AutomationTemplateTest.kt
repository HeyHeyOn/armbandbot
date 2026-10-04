package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
class AutomationTemplateTest {
 @Test fun authenticatedManagerTemplateIsParsedWithoutExecutingJavaScript() {
  val d=Jsoup.parse("<script id='minor_manager_view_buttons-tmpl' type='text/x-jquery-tmpl'><button onclick='update_bump(2371)'>끌올</button><div class='mng_subject_sel'><li data-value='10' data-type='general'>테스트</li><li data-value='99' data-type='notice'>공지</li></div></script>")
  assertEquals(listOf(GalleryCategory(10,"테스트")),automationTabs(d))
  assertTrue(automationHasBumpControl(d))
 }
 @Test fun unrelatedScriptNeverGrantsControls() {
  val d=Jsoup.parse("<script id='other'><button onclick='update_bump(2371)'>끌올</button></script>")
  assertFalse(automationHasBumpControl(d));assertTrue(automationTabs(d).isEmpty())
 }
}
