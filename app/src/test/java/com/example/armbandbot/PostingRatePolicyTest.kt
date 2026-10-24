package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test

class PostingRatePolicyTest {
    private val now=1_800_000_000_000L
    private fun p(n:Int,author:String="ip:203.239",age:Long=0,type:String="M",gall:String="fixture") =
        RepeatSpamPost(PostKey(type,gall,n.toString()),author,now-age,"다른제목$n","다른본문$n")
    private fun tracker(rate:PostingRateSettings=PostingRateSettings(enabled=true,ipCount=2,uidCount=2),content:Boolean=false,observe:Boolean=false) =
        RepeatSpamTracker().apply { updatePolicy("fixture",RepeatSpamSettings(enabled=true,count=2,contentEnabled=content,observeOnly=observe,postingRate=rate)) }
    @Test fun independentIdentityAndGalleryIncludeFirstWithoutTextMatching() {
        for(type in listOf("M","MI")) for(author in listOf("ip:203.239","uid:fixture")) {
            val t=tracker();val a=p(1,author,10_000,type);val b=p(2,author,0,type)
            assertNull(t.observe(a,false,now,true))
            val match=t.observe(b,false,now,true)!!
            assertEquals(SpamMatchKind.POSTING_RATE,match.kind);assertEquals(setOf(a.key,b.key),match.keys)
            assertTrue(t.canAct(a.key,now));assertTrue(t.canAct(b.key,now))
            assertTrue(match.reason.contains("최근 60초"))
        }
    }
    @Test fun freshDefaultsKeepContentOnAndRateOff() {
        val t=RepeatSpamTracker();t.updatePolicy("default",RepeatSpamSettings(enabled=true,count=2))
        assertNull(t.observe(p(1),true,now,true));assertNull(t.observe(p(2),true,now,true))
        assertTrue(RepeatSpamSettings().contentEnabled);assertFalse(PostingRateSettings().enabled)
    }
    @Test fun blankAndShortContentStillCountsButIneligiblePostDoesNot() {
        val t=tracker();assertNull(t.observe(p(1).copy(title="",body=""),false,now,true))
        assertNull(t.observe(p(2),true,now,false))
        assertNotNull(t.observe(p(3).copy(title="!",body=""),false,now,true))
        assertFalse(t.canAct(p(2).key,now))
    }
    @Test fun duplicateScansNeverInflateCount() {
        val t=tracker();repeat(100) { assertNull(t.observe(p(1),false,now,true)) }
        assertEquals(1,t.size());assertNotNull(t.observe(p(2),false,now,true))
    }
    @Test fun distinctAuthorGalleryAndTypeNeverCombine() {
        val t=tracker()
        listOf(p(1),p(2,"ip:203.240"),p(3,"uid:203.239"),p(4,gall="other"),p(5,type="MI"))
            .forEach { assertNull(t.observe(it,false,now,true)) }
    }
    @Test fun exactBoundaryExpiresWithoutAnotherObservation() {
        val t=tracker();val a=p(1,age=60_000)
        t.observe(a,false,now,true);assertNotNull(t.observe(p(2),false,now,true))
        assertFalse(t.canAct(a.key,now+1));assertFalse(t.canAct(p(2).key,now+1))
    }
    @Test fun uidAndIpHaveIndependentThresholdsAndWindows() {
        val t=tracker(PostingRateSettings(enabled=true,ipSeconds=20,ipCount=3,uidSeconds=60,uidCount=2))
        t.observe(p(1,age=30_000),false,now,true);t.observe(p(2),false,now,true)
        assertNull(t.observe(p(3),false,now,true));assertNotNull(t.observe(p(4),false,now,true))
        t.observe(p(5,"uid:fixture",30_000),false,now,true);assertNotNull(t.observe(p(6,"uid:fixture"),false,now,true))
    }
    @Test fun targetSwitchesAndInvalidIdentitiesNeverCount() {
        val t=tracker(PostingRateSettings(enabled=true,ipCount=2,uidCount=2,yudong=false))
        for(author in listOf("ip:203.239","","uid:","nick")) for(n in 1..2) assertNull(t.observe(p(n,author),false,now,true))
        t.observe(p(3,"uid:fixture"),false,now,true);assertNotNull(t.observe(p(4,"uid:fixture"),false,now,true))
    }
    @Test fun invalidFutureOldDatesAndProtectionsRemoveEvidence() {
        val t=tracker()
        for(created in listOf(-1L,now+1,now-60_001)) assertNull(t.observe(p(1).copy(createdAt=created),false,now,true))
        t.observe(p(1),false,now,true);t.observe(p(2),false,now,true)
        t.observe(p(1),false,now,false)
        assertFalse(t.canAct(p(2).key,now));assertEquals(1,t.size())
    }
    @Test fun settingsOffAndStopClearAllPending() {
        val t=tracker();t.observe(p(1),false,now,true);t.observe(p(2),false,now,true)
        t.updatePolicy("off",RepeatSpamSettings());assertFalse(t.canAct(p(1).key,now));assertEquals(0,t.size())
        t.clear();assertEquals(0,t.size())
    }
    @Test fun contentAndRateUseOwnExpirationAndEligibility() {
        val t=tracker(content=true)
        val a=p(1,age=90_000).copy(title="똑같은제목입니다");val b=p(2).copy(title=a.title)
        t.observe(a,true,now,true)
        assertEquals(SpamMatchKind.CONTENT,t.observe(b,true,now,true)!!.kind)
        t.complete(b.key)
        assertFalse(t.canAct(b.key,now)) // rate has only one recent post
    }
    @Test fun activeRateWinsContentHoldButContentWinsEqualActions() {
        for(contentHold in listOf(false,true)) {
            val t=tracker(PostingRateSettings(enabled=true,ipCount=2,observeOnly=false),true,contentHold)
            val a=p(1).copy(title="똑같은제목입니다");val b=p(2).copy(title=a.title)
            t.observe(a,true,now,true)
            assertEquals(if(contentHold) SpamMatchKind.POSTING_RATE else SpamMatchKind.CONTENT,t.observe(b,true,now,true)!!.kind)
            t.complete(b.key,if(contentHold)SpamMatchKind.POSTING_RATE else SpamMatchKind.CONTENT,allKinds=true)
            assertFalse(t.canAct(b.key,now))
        }
    }
    @Test fun completedHoldDoesNotSuppressLaterContentAction() {
        val t=tracker(content=true);t.observe(p(1),true,now,true);val b=p(2).copy(title="나중에반복되는충분한길이의제목")
        assertEquals(SpamMatchKind.POSTING_RATE,t.observe(b,true,now,true)!!.kind)
        t.complete(b.key,SpamMatchKind.POSTING_RATE)
        val c=p(3).copy(title=b.title)
        t.observe(c,true,now,true)
        assertTrue(t.canAct(b.key,now,SpamMatchKind.CONTENT))
    }
    @Test fun shorterRateWindowExpiresEvenWhenContentRetainsEvents() {
        val t=tracker(content=true);t.observe(p(1,age=60_000),true,now,true);t.observe(p(2),true,now,true)
        assertTrue(t.canAct(p(2).key,now));assertFalse(t.canAct(p(2).key,now+1));assertEquals(2,t.size())
    }
    @Test fun secondsAndCountsAreBoundedAndInputRejectsMalformedNumbers() {
        assertEquals(10,PostingRateSettings(ipSeconds=-1).bounded().ipSeconds)
        assertEquals(3600,PostingRateSettings(uidSeconds=Int.MAX_VALUE).bounded().uidSeconds)
        assertEquals(2,PostingRateSettings(ipCount=-1).bounded().ipCount)
        assertEquals(100,PostingRateSettings(uidCount=Int.MAX_VALUE).bounded().uidCount)
        for(s in listOf("","-1","1.5","2147483648","a","0")) assertNull(parsePostingRateValue(s,true))
        assertEquals(60,parsePostingRateValue("60",true));assertNull(parsePostingRateValue("3601",true))
        assertEquals(2,parsePostingRateValue("2",false));assertNull(parsePostingRateValue("101",false))
    }
}
