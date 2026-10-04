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
 @Test fun authenticatedListTemplateExposesCategoriesWithoutExecutingJavaScript() {
  for (template in listOf("minor_buttons-tmpl", "mini_buttons-tmpl")) {
   val d=Jsoup.parse("""<script id="$template" type="text/x-jquery-tmpl"><ul id="listHeadTxtLyr" class="option_box white" style="display:none"><li data-value="0" data-type="general"><a href="javascript:chg_headtext_batch(0)">일반</a></li><li data-value="10" data-type="general"><a href="javascript:chg_headtext_batch(10)">테스트</a></li></ul></script>""")
   assertEquals(template,listOf(GalleryCategory(0,"일반"),GalleryCategory(10,"테스트")),automationTabs(d))
   assertFalse(automationHasBumpControl(d))
  }
 }
 @Test fun unrelatedScriptNeverGrantsControls() {
  val d=Jsoup.parse("<script id='other'><button onclick='update_bump(2371)'>끌올</button><ul id='listHeadTxtLyr'><li data-value='10' data-type='general'>테스트</li></ul></script>")
  assertFalse(automationHasBumpControl(d));assertTrue(automationTabs(d).isEmpty())
 }
}
