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
}
