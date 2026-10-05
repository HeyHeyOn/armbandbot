package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
class AutomationPermissionTest {
 @Test fun managerUsesCookieCsrfWhenListHasNoHiddenToken() {
  val doc=Jsoup.parse("<a onclick='listSearchHead(999)'>매니저</a>")
  assertTrue(automationManagerConfirmed(doc))
  assertEquals("fixture",automationCiToken(doc,"session=test; ci_c=fixture"))
 }
 @Test fun publicManagementHistoryLinkIsNotAuthority() {
  assertFalse(automationManagerConfirmed(Jsoup.parse("<button class='btn_mngadmin_report'>갤러리 관리 내역</button>")))
  assertEquals("",automationCiToken(Jsoup.parse("<body></body>"),"session=test"))
 }
 @Test fun managerSettingsButtonWithoutDeletedPostsTabConfirmsAuthority() {
  for (path in listOf("mgallery", "mini")) {
   val doc=Jsoup.parse("""<div class="useradmin_go"><button class="sp_img btn_useradmin_go" onclick="location.href='/$path/management?id=fixture';"><span class="blind">관리</span></button></div>""")
   assertTrue(path,automationManagerConfirmed(doc))
  }
 }
 @Test fun listManagementTemplatesConfirmAuthorityWithoutManagerLink() {
  for (id in listOf("minor_buttons-tmpl","mini_buttons-tmpl")) {
   val doc=Jsoup.parse("""<script type="text/x-jquery-tmpl" id="$id"><div class="mng_subject_sel"><ul id="listHeadTxtLyr"><li data-value="10" data-type="general"><a href="javascript:chg_headtext_batch(10)">테스트</a></li></ul></div></script>""")
   assertTrue(id,automationManagerConfirmed(doc))
   assertEquals(listOf(GalleryCategory(10,"테스트")),automationTabs(doc))
  }
 }
 @Test fun managerTabAcceptsQuotedIdsAndJavascriptWhitespace() {
  assertTrue(automationManagerConfirmed(Jsoup.parse("""<a onclick="listSearchHead(
 '999'	 ); return false;">매니저</a>""")))
  assertTrue(automationManagerConfirmed(Jsoup.parse("""<a onclick="javascript:listSearchHead(999)">매니저</a>""")))
  assertTrue(automationManagerConfirmed(Jsoup.parse("""<a onclick="if (ready) listSearchHead(999);">매니저</a>""")))
 }
 @Test fun publicCategoriesTokensAndUnrelatedTemplatesDoNotGrantAuthority() {
  val public="""<input name="ci_t" value="fixture"><a href="?headid=10">테스트</a><button class="btn_mngadmin_report">갤러리 관리 내역</button>"""
  assertFalse(automationManagerConfirmed(Jsoup.parse(public)))
  assertFalse(automationManagerConfirmed(Jsoup.parse(public+"""<script id="other"><div class="mng_subject_sel"><a href="javascript:chg_headtext_batch(10)">테스트</a></div></script>""")))
  assertFalse(automationManagerConfirmed(Jsoup.parse("""<script id="minor_buttons-tmpl"><div class="head_text"><li data-value="10">테스트</li></div></script>""")))
 }
}
