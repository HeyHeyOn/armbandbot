package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BotServiceScopeScheduleClaimContractTest {
    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/example/armbandbot/$name"),
            File("app/src/main/java/com/example/armbandbot/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("source not found: $name")
    }

    @Test
    fun serviceFixesScanScopeForTheJobAndRoutesEveryCheckedPostOperationThroughIt() {
        val service = source("BotService.kt")
        val global = source("MainActivity.kt")

        assertTrue(service.contains("val scanScopeId = resolveScanScopeId("))
        assertTrue(service.contains("independent_scan_state_enabled"))
        assertTrue(service.contains("GlobalBotState.getSavedPost(config.scanScopeId,"))
        assertTrue(service.contains("scopeId = config.scanScopeId"))
        assertTrue(service.contains("tryLockGeneralSnapshot(config.scanScopeId, botId,"))
        assertTrue(service.contains("tryLockBlockSnapshot(config.scanScopeId, botId,"))
        assertTrue(Regex("updateSnapshotPath\\(\\s*config\\.scanScopeId,").containsMatchIn(service))
        assertTrue(global.contains("fun getSavedPost(scopeId: String,"))
        assertTrue(global.contains("scopeId = scopeId"))
    }

    @Test
    fun serviceHistoryAlwaysRecordsTheActorBot() {
        val service = source("BotService.kt")
        val global = source("MainActivity.kt")

        assertFalse(service.contains("GlobalBotState.saveBlockHistory(\n                gallType"))
        assertFalse(service.contains("GlobalBotState.saveHoldHistory(\n                    gallType"))
        assertTrue(service.contains("actorBotId = botId"))
        assertTrue(Regex("fun\\s+saveBlockHistory\\(\\s*actorBotId: String").containsMatchIn(global))
        assertTrue(Regex("fun\\s+saveHoldHistory\\(\\s*actorBotId: String").containsMatchIn(global))
    }

    @Test
    fun databaseApisRequireExplicitScopeActorAndTargetIdentity() {
        val dao = source("AppDatabase.kt")
        val global = source("MainActivity.kt")

        assertFalse(Regex("fun\\s+getPost\\(\\s*gallType:").containsMatchIn(dao))
        assertFalse(Regex("fun\\s+deletePost\\(\\s*gallType:").containsMatchIn(dao))
        assertFalse(Regex("fun\\s+updateSnapshotPath(?:IfUnchanged)?\\(\\s*gallType:").containsMatchIn(dao))
        assertFalse(Regex("fun\\s+getSavedPost\\(\\s*gallType:").containsMatchIn(global))
        assertFalse(Regex("fun\\s+(?:tryLock|unlock)(?:General|Block)Snapshot\\(\\s*gallType:").containsMatchIn(global))
        assertFalse(Regex("scopeId:\\s*String\\s*=\\s*GLOBAL_SCAN_SCOPE").containsMatchIn(global))
        assertFalse(Regex("fun\\s+saveBlockHistory\\([^)]*targetNo:\\s*String\\s*=", RegexOption.DOT_MATCHES_ALL).containsMatchIn(global))
        assertTrue(Regex("fun\\s+hasHoldHistory\\(\\s*gallType:").containsMatchIn(dao))
        assertFalse(dao.contains("hasHoldHistory(LEGACY_ACTOR_BOT_ID"))
    }

    @Test
    fun checkedPostAndHistoryDatabaseFailuresAreNotConvertedToMissingOrAllowed() {
        val service = source("BotService.kt")
        val global = source("MainActivity.kt")

        assertTrue(global.contains("private fun requireDb(): AppDatabase"))
        assertFalse(Regex("fun\\s+getSavedPost[\\s\\S]*?catch \\(e: Exception\\) \\{\\s*null", RegexOption.DOT_MATCHES_ALL).containsMatchIn(global))
        assertFalse(Regex("fun\\s+hasHoldHistory[\\s\\S]*?catch \\(e: Exception\\) \\{\\s*false", RegexOption.DOT_MATCHES_ALL).containsMatchIn(global))
        assertTrue(global.contains("check(rowId != -1L)"))
        assertTrue(service.contains("persistModerationHistoryOrLog("))
        assertTrue(service.contains("Log.e(\"BotService\", \"[durable history write failed]"))
    }

    @Test
    fun scheduleIsReadFreshBeforeNetworkAndMutationAndWaitsWithoutStoppingTheJob() {
        val service = source("BotService.kt")

        assertTrue(service.contains("awaitActiveSchedule(botId, botPref"))
        assertTrue(service.contains("readCurrentSchedule(botId)"))
        assertTrue(service.contains("ZoneId.systemDefault()"))
        assertTrue(service.contains("MAX_SCHEDULE_RECHECK_DELAY_MS"))
        assertTrue(service.contains("예약 대기"))
        assertTrue(service.contains("예약 재개"))
        assertTrue(service.contains("currentCoroutineContext().ensureActive()"))
        assertTrue(service.contains("UrlProcessOutcome.PAUSED_BY_SCHEDULE"))
        assertFalse(service.contains("serviceScope.isActive"))
    }

    @Test
    fun centralFetchPathsRecheckScheduleImmediatelyBeforeStartingRequests() {
        val service = source("BotService.kt")

        assertTrue(
            Regex("requireActiveScheduleForRequest\\(botId\\)\\s*val pageFetchStartedAt")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("requireActiveScheduleForRequest\\(botId\\)\\s*val detailFetchStartedAt")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("requireActiveScheduleForRequest\\(botId\\)\\s*val commentFetchStartedAt")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("for \\(target in targets\\) \\{\\s*requireActiveScheduleForRequest\\(botId\\)")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("requireActiveScheduleForRequest\\(botId\\)\\s*val responseBody = connection.gatedExecute")
                .containsMatchIn(service)
        )
    }

    @Test
    fun autoLoginRechecksScheduleBeforeEveryNetworkRequest() {
        val service = source("BotService.kt")

        assertTrue(service.contains("private suspend fun isSessionValid(cookie: String, botId: String): Boolean"))
        assertTrue(service.contains("private suspend fun performAutoLogin(loginId: String, loginPw: String, botId: String): String?"))
        assertTrue(service.contains("private suspend fun tryRecoverSession("))
        assertTrue(
            Regex("requireActiveScheduleForRequest\\(botId\\)\\s*val sessionCheckDoc = Jsoup\\.connect")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("requireActiveScheduleForRequest\\(botId\\)\\s*val loginPageResponse = Jsoup\\.connect")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("requireActiveScheduleForRequest\\(botId\\)\\s*val loginResponse = Jsoup\\.connect")
                .containsMatchIn(service)
        )
        assertTrue(service.contains("isSessionValid(mergedCookie, botId)"))
        assertTrue(service.contains("isSessionValid(currentCookie, botId)"))
    }

    @Test
    fun secondarySnapshotAndImmediateExecutionFetchesRecheckSchedule() {
        val service = source("BotService.kt")

        assertTrue(
            Regex("if \\(!isScheduleActiveNow\\(botId\\)\\) throw SchedulePausedException\\(\\)\\s*val postDoc = Jsoup\\.connect\\(pcPostDetailUrl\\)")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("if \\(!isScheduleActiveNow\\(botId\\)\\) throw SchedulePausedException\\(\\)\\s*val commentResponse = Jsoup\\.connect\\(commentApiUrl\\)")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("if \\(!isScheduleActiveNow\\(botId\\)\\) throw SchedulePausedException\\(\\)\\s*Jsoup\\.connect\\(snapshotUrl\\)")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("if \\(!isScheduleActiveNow\\(botId\\)\\) throw SchedulePausedException\\(\\)\\s*val immediatePostDoc = Jsoup\\.connect")
                .containsMatchIn(service)
        )
        assertTrue(
            Regex("if \\(!isScheduleActiveNow\\(botId\\)\\) throw SchedulePausedException\\(\\)\\s*val immediateCommentPostDoc = Jsoup\\.connect")
                .containsMatchIn(service)
        )
    }

    @Test
    fun schedulePausePropagatesAndBypassesNormalCycleDelay() {
        val service = source("BotService.kt")

        assertTrue(service.contains("catch (paused: SchedulePausedException)"))
        assertTrue(service.contains("ScheduleCycleOutcome.PAUSED_BY_SCHEDULE"))
        assertTrue(service.contains("cycleDelayAfterScheduleOutcome("))
        assertTrue(service.contains("if (cycleDelay == null) continue"))
        assertTrue(service.contains("catch (cancelled: CancellationException)"))
    }

    @Test
    fun scopeAndActorCannotBeOmittedAndResetClearsClaimLedger() {
        val database = source("AppDatabase.kt")
        val service = source("BotService.kt")
        val global = source("MainActivity.kt")
        assertFalse(database.contains("val scopeId: String = GLOBAL_SCAN_SCOPE"))
        assertFalse(service.contains("val scanScopeId: String = GLOBAL_SCAN_SCOPE"))
        assertTrue(service.contains("if (!requiresModerationClaim(actionKind))"))
        assertTrue(global.contains("moderationClaimDao().clearAll()"))
    }

    @Test
    fun claimedModerationActionRechecksScheduleAfterClaimBeforeRemoteRequest() {
        val service = source("BotService.kt")
        val functionStart = service.indexOf("private fun executeClaimedModerationAction(")
        val functionEnd = service.indexOf("private fun finalizeModerationClaimOrLog", functionStart)
        assertTrue(functionStart >= 0 && functionEnd > functionStart)
        val functionBody = service.substring(functionStart, functionEnd)
        val claimAcquired = functionBody.indexOf("GlobalBotState.acquireModerationClaim(")
        val postClaimScheduleCheck = functionBody.indexOf("if (!isScheduleActiveNow(botId))", claimAcquired + 1)
        val remoteExecution = functionBody.indexOf("val response = execute()", claimAcquired + 1)

        assertTrue(claimAcquired >= 0)
        assertTrue(postClaimScheduleCheck > claimAcquired)
        assertTrue(remoteExecution > postClaimScheduleCheck)
        assertTrue(
            functionBody.substring(postClaimScheduleCheck, remoteExecution)
                .contains("finalizeModerationClaimOrLog(claim, ClaimStatus.FAILED)")
        )
    }

    @Test
    fun everyDestructivePathUsesGlobalClaim() {
        val service = source("BotService.kt")
        val global = source("MainActivity.kt")

        assertTrue(service.contains("executeClaimedModerationAction("))
        assertTrue(service.contains("actionKind = moderationClaimActionKind(actionConfig.mode.name, \"POST\", actionConfig.deletePostOnBlock)"))
        assertTrue(service.contains("actionKind = moderationClaimActionKind(actionConfig.mode.name, \"COMMENT\", actionConfig.deletePostOnBlock)"))
        assertTrue(service.contains("actionKind = spamBurstDeleteClaimActionKind(\"POST\")"))
        assertTrue(service.contains("return classifyModerationClaimStatus(response) == ClaimStatus.SUCCEEDED"))
        assertTrue(service.contains("classifyModerationClaimStatus(response)"))
        assertFalse(service.contains("response.contains(\"\\\"result\\\":\\\"success\\\"\")"))
        assertFalse(service.contains("deleteResponse.contains(\"\\\"result\\\":\\\"success\\\"\")"))
        assertTrue(service.contains("ClaimStatus.SUCCEEDED") || source("ModerationActionClaim.kt").contains("ClaimStatus.SUCCEEDED"))
        assertTrue(source("ModerationActionClaim.kt").contains("ClaimStatus.FAILED"))
        assertTrue(service.contains("catch (cancelled: CancellationException)"))
        assertTrue(global.contains("fun acquireModerationClaim("))
        assertTrue(global.contains("fun finalizeModerationClaim("))
    }

    @Test
    fun uncertainRemoteOutcomeStaysSuppressedAndOwnerTokenIsNeverLogged() {
        val service = source("BotService.kt")
        assertTrue(service.contains("finalizeModerationClaimOrLog(claim, ClaimStatus.UNKNOWN)"))
        assertFalse(service.contains("owner=${'$'}{claim.ownerToken"))
    }

    @Test
    fun claimLifecycleUsesUniqueGenerationTokensAndChecksEveryFinalizeCas() {
        val service = source("BotService.kt")
        val claims = source("ModerationActionClaim.kt")

        assertTrue(service.contains("ownerToken = UUID.randomUUID().toString()"))
        assertTrue(service.contains("logModerationClaimFinalizeFailure("))
        assertTrue(service.contains("MODERATION_CLAIM_LEASE_MS"))
        assertTrue(service.contains(".timeout(MODERATION_REQUEST_TIMEOUT_MS)"))
        assertTrue(claims.contains("ownerToken=:expectedOwnerToken"))
        assertTrue(claims.contains("ownerToken=:ownerToken AND status='PENDING'"))
    }
}
