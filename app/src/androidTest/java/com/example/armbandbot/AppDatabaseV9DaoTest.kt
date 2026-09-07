package com.heyheyon.armbandbot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseV9DaoTest {
    private lateinit var database: AppDatabase
    private lateinit var posts: PostDao
    private lateinit var claims: ModerationClaimDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        posts = database.postDao()
        claims = database.moderationClaimDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun checkedPostIdentityAndSnapshotUpdatesAreIsolatedByScope() {
        val global = post(GLOBAL_SCAN_SCOPE, 1, "/global.html")
        val bot = post("bot-a", 2, "/bot.html")
        posts.insertOrUpdate(global)
        posts.insertOrUpdate(bot)

        assertEquals(1, posts.getPost(GLOBAL_SCAN_SCOPE, "M", "g", "7")?.commentCount)
        assertEquals(2, posts.getPost("bot-a", "M", "g", "7")?.commentCount)

        assertEquals(1, posts.updateSnapshotPathIfUnchanged("bot-a", "M", "g", "7", "/bot.html", "/new.html"))
        assertEquals("/global.html", posts.getPost(GLOBAL_SCAN_SCOPE, "M", "g", "7")?.snapshotPath)
        assertEquals("/new.html", posts.getPost("bot-a", "M", "g", "7")?.snapshotPath)
    }

    @Test
    fun replacingAndDeletingScopeBaselineNeverTouchesAnotherScope() {
        posts.insertOrUpdate(post(GLOBAL_SCAN_SCOPE, 1, "/global.html"))
        posts.insertOrUpdate(post("bot-a", 2, null))

        posts.replaceScopeBaseline(GLOBAL_SCAN_SCOPE, "bot-a")

        assertEquals(1, posts.getPostsForScope(GLOBAL_SCAN_SCOPE).size)
        assertEquals(1, posts.getPost("bot-a", "M", "g", "7")?.commentCount)
        assertNull(posts.getPost("bot-a", "M", "g", "7")?.snapshotPath)
        posts.deletePostsForScope("bot-a")
        assertTrue(posts.getPostsForScope("bot-a").isEmpty())
        assertEquals(1, posts.getPostsForScope(GLOBAL_SCAN_SCOPE).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun replacingScopeRejectsSameSourceAndTarget() {
        posts.replaceScopeBaseline(GLOBAL_SCAN_SCOPE, GLOBAL_SCAN_SCOPE)
    }

    @Test
    fun blockAndHoldHistoryCanBeFilteredByActor() {
        posts.insertBlockHistory(block("bot-a"))
        posts.insertBlockHistory(block("bot-b").copy(postNum = "8"))
        posts.insertHoldHistory(hold("bot-a"))
        posts.insertHoldHistory(hold("bot-b").copy(postNum = "8"))

        assertEquals(listOf("7"), posts.getBlockHistoryForActor("bot-a").map { it.postNum })
        assertEquals(listOf("8"), posts.getHoldHistoryForActor("bot-b").map { it.postNum })
        assertTrue(posts.hasHoldHistory("M", "g", "7", "COMMENT", "1"))
    }

    @Test
    fun holdDuplicateLookupIsGlobalAcrossActors() {
        posts.insertHoldHistory(hold("bot-a"))

        assertTrue(posts.hasHoldHistory("M", "g", "7", "COMMENT", "1"))
    }

    @Test
    fun concurrentClaimAcquisitionHasExactlyOneWinnerAndOnlyOwnerCanFinalize() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val requests = listOf(key("bot-a", "owner-a"), key("bot-b", "owner-b"))
        val futures = requests.map { request ->
            pool.submit<Boolean> {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                claims.acquire(request, now = 1_000L, leaseMs = 60_000L, failureCooldownMs = 10_000L)
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        val results = futures.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdownNow()

        assertEquals(1, results.count { it })
        val winner = requests[results.indexOf(true)]
        val loser = requests[results.indexOf(false)]
        assertFalse(claims.finalize(loser, ClaimStatus.SUCCEEDED, 2_000L))
        assertTrue(claims.finalize(winner, ClaimStatus.SUCCEEDED, 2_000L))
        assertFalse(claims.acquire(loser.copy(ownerToken = "owner-later"), now = Long.MAX_VALUE, leaseMs = 0L, failureCooldownMs = 0L))
    }

    @Test
    fun staleTakeoverGetsANewGenerationAndOnlyItsExactTokenCanFinalize() {
        val oldGeneration = key("bot-a", "owner-old")
        val newGeneration = key("bot-a", "owner-new")
        assertTrue(claims.acquire(oldGeneration, now = 1_000L, leaseMs = 100L, failureCooldownMs = 10L))
        assertTrue(claims.acquire(newGeneration, now = 1_100L, leaseMs = 100L, failureCooldownMs = 10L))
        assertEquals("owner-new", claims.find("M", "g", "7", "COMMENT", "1", "BLOCK")?.ownerToken)
        assertFalse(claims.finalize(oldGeneration, ClaimStatus.SUCCEEDED, 1_101L))
        assertTrue(claims.finalize(newGeneration, ClaimStatus.FAILED, 1_101L))
        assertFalse(claims.acquire(key("bot-c", "owner-c1"), now = 1_110L, leaseMs = 100L, failureCooldownMs = 10L))
        assertTrue(claims.acquire(key("bot-c", "owner-c2"), now = 1_111L, leaseMs = 100L, failureCooldownMs = 10L))
    }

    @Test
    fun clearingLedgerRemovesSucceededClaims() {
        val claim = key("bot-a", "owner-a").copy(status = ClaimStatus.SUCCEEDED.name, claimedAt = 1L, finishedAt = 2L)
        assertTrue(claims.insertIfAbsent(claim) > 0L)
        claims.clearAll()
        assertEquals(null, claims.find("M", "g", "7", "COMMENT", "1", "BLOCK"))
    }

    private fun post(scope: String, count: Int, snapshot: String?) = CheckedPost(
        gallType = "M", gallId = "g", postNum = "7", commentCount = count,
        snapshotPath = snapshot, scopeId = scope,
    )

    private fun block(actor: String) = BlockHistory(
        gallType = "M", gallId = "g", postNum = "7", targetType = "POST",
        targetAuthor = "a", targetContent = "c", blockReason = "r", actorBotId = actor,
    )

    private fun hold(actor: String) = HoldHistory(
        gallType = "M", gallId = "g", postNum = "7", targetType = "COMMENT", targetNo = "1",
        targetAuthor = "a", targetContent = "c", holdReason = "r", actorBotId = actor,
    )

    private fun key(actor: String, ownerToken: String) = ModerationActionClaim(
        gallType = "M", gallId = "g", postNum = "7", targetType = "COMMENT", targetNo = "1",
        actionKind = "BLOCK", actorBotId = actor, ownerToken = ownerToken,
        status = ClaimStatus.PENDING.name, claimedAt = 0L,
    )
}
