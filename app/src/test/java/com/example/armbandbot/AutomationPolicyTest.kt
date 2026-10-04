package com.heyheyon.armbandbot
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AutomationPolicyTest {
 private val url="https://gall.dcinside.com/mgallery/board/view/?id=laboratory1&no=2361"
 @Test fun dailySlotIsDueOnlyInsideBoundedWindow() {
  val r=BumpRule("r",url,listOf(600))
  assertEquals(1,dueBumps(listOf(r),Instant.parse("2026-10-01T01:02:00Z").toEpochMilli(),ZoneId.of("Asia/Seoul")).size)
  assertTrue(dueBumps(listOf(r),Instant.parse("2026-10-01T01:16:00Z").toEpochMilli(),ZoneId.of("Asia/Seoul")).isEmpty())
 }
 @Test fun eachDayHasDifferentOccurrenceButDuplicateRulesShareOccurrence() {
  val a=dueBumps(listOf(BumpRule("a",url,listOf(600)),BumpRule("b",url,listOf(600))),Instant.parse("2026-10-01T01:02:00Z").toEpochMilli(),ZoneId.of("Asia/Seoul"))
  val b=dueBumps(listOf(BumpRule("a",url,listOf(600))),Instant.parse("2026-10-02T01:02:00Z").toEpochMilli(),ZoneId.of("Asia/Seoul"))
  assertEquals(1,a.size);assertNotEquals(a.single().occurrence,b.single().occurrence)
 }
 @Test fun futureAndDisabledSlotsAreNotDue() {
  assertTrue(dueBumps(listOf(BumpRule("r",url,listOf(700))),Instant.parse("2026-10-01T01:02:00Z").toEpochMilli(),ZoneId.of("Asia/Seoul")).isEmpty())
 }
 @Test fun timesAreStrictAndDeduplicated() { assertEquals(listOf(0,600,1439),parseBumpTimes("00:00, 10:00, 23:59, 10:00")) }
 @Test(expected=IllegalArgumentException::class) fun invalidTimeIsNotSilentlyRepaired() { parseBumpTimes("24:00") }
 @Test(expected=IllegalArgumentException::class) fun malformedTimeIsRejected() { parseBumpTimes("1:2") }
 @Test(expected=IllegalArgumentException::class) fun externalBumpUrlRejected() { BumpRule("r","https://evil.example/view/?id=x&no=1",listOf(1)) }
 @Test(expected=IllegalArgumentException::class) fun normalGalleryBumpRejected() { BumpRule("r","https://gall.dcinside.com/board/view/?id=x&no=1",listOf(1)) }
 @Test fun rulesRoundTrip() {
  val bumps=listOf(BumpRule("r",url,listOf(600,900)))
  assertEquals(bumps,parseBumpRules(encodeBumpRules(bumps)))
  val moves=listOf(TabMoveRule("m","M","laboratory1","abc",10,"제안",false))
  assertEquals(moves,parseTabMoveRules(encodeTabMoveRules(moves)))
 }
 @Test(expected=IllegalArgumentException::class) fun corruptRulesFailClosed() { parseBumpRules("{}") }
 @Test fun destinationIsGalleryBoundAndWhitelistWins() {
  val rule=TabMoveRule("r","M","laboratory1","ABC",10,"제안",false)
  assertEquals(rule,matchingMove(listOf(rule),PostKey("M","laboratory1","1"),"abc body",false,false,false))
  assertNull(matchingMove(listOf(rule),PostKey("MI","laboratory1","1"),"abc",false,false,false))
  assertNull(matchingMove(listOf(rule),PostKey("M","other","1"),"abc",false,false,false))
  assertNull(matchingMove(listOf(rule),PostKey("M","laboratory1","1"),"abc",true,false,false))
  assertNull(matchingMove(listOf(rule),PostKey("M","laboratory1","1"),"abc",false,true,false))
  assertNull(matchingMove(listOf(rule),PostKey("M","laboratory1","1"),"abc",false,false,true))
 }
 @Test fun categoryParserUsesStableIdsAndMarksConceptRemoval() {
  val html="""<ul class="subject_list"><li><a href="?id=x&headid=10">제안</a></li></ul><ul id="listHeadTxtLyr"><li data-value="20" data-type="obstruct">일반</li></ul>"""
  assertEquals(listOf(10,20),parseGalleryCategories(html).map{it.id})
  assertTrue(parseGalleryCategories(html).last().obstruct)
 }
 @Test fun payloadMatchesOfficialManagerContract() {
  val key=PostKey("MI","armbandbot","295")
  assertEquals("https://gall.dcinside.com/ajax/mini_manager_board_ajax/update_bump",nativeActionUrl(key,"update_bump"))
  assertEquals("295",nativeBumpPayload(key,"token")["nos[]"])
  assertEquals("10",nativeMovePayload(key,"token",10)["headtext"])
 }
 @Test fun policyRevisionForcesBoundedRecheckWithoutDeletingRows() {
  val state=AutomationRecheckState();val key=PostKey("M","x","1")
  state.update("a");assertTrue(state.needs(key));state.mark(key);assertFalse(state.needs(key))
  state.update("a");assertFalse(state.needs(key));state.update("b");assertTrue(state.needs(key))
 }
}
