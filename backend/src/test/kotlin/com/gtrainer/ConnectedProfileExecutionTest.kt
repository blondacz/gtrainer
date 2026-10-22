package com.gtrainer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectedProfileExecutionTest {
    private val intake = ContextIntake("11111111-1111-4111-8111-111111111111", 1,
        "Wednesday unavailable", "user_report", "athlete", "user", "2026-03-01", "2026-03-02T10:00:00Z")
    private val packet = ContextIntakeSuggestionContractV1.packet(intake)
    private val valid = ContextIntakeSuggestionContractV1.encodeDraft(ContextIntakeSuggestionDraftV1(
        ContextIntakeSuggestionContractV1.PROFILE, intake.intakeId, intake.revision, emptyList(), emptyList()))

    private fun setup(hosted: Boolean = false, invocation: ConnectedReviewInvocation? = null): Pair<ProfileRepo, ProfileProvider> {
        val repo = ProfileRepo(invocation)
        val provider = ProfileProvider(if (hosted) "hosted-test" else "local-test", hosted)
        repo.status = repo.status.copy(provider = "${if (hosted) "hosted" else "local"}:${provider.providerId}", model = provider.modelId)
        return repo to provider
    }
    private fun selection(repo: ProfileRepo) = ConnectedReviewProviderSelection(
        repo.status.provider.substringAfter(':'), repo.status.model, repo.status.provider.substringBefore(':'))

    @Test fun `preview binds exact correction prompt and rejected content is never sent`() = runBlocking {
        val (repo, provider) = setup(hosted = true)
        val service = service(repo, provider)
        val selection = selection(repo)
        val preview = service.preview(repo.status.snapshotId)
        assertEquals(repo.status.packetDigest, preview.packetDigest)
        assertEquals(preview.exactPrompt.userPacketJson, preview.exactCorrectionPrompt!!.userPacketJson)
        assertTrue(preview.exactCorrectionPrompt.systemInstructions.contains("unchanged supplied packet"))
        assertTrue(preview.packetDigest.matches(Regex("[a-f0-9]{64}")))
        service.consent(repo.status.snapshotId, ConnectedReviewConsentRequest(selection, preview.packetDigest, true))
        provider.responses = listOf("SENSITIVE_REJECTED_TEXT", valid)
        val result = service.executeManually(repo.status.snapshotId, selection)
        assertEquals("PUBLISHED", result.state)
        assertEquals(2, provider.prompts.size)
        assertEquals(preview.exactPrompt, provider.prompts[0])
        assertEquals(preview.exactCorrectionPrompt, provider.prompts[1])
        assertFalse(provider.prompts.any { it.userPacketJson.contains("SENSITIVE_REJECTED_TEXT") })
        assertEquals(listOf<String?>(null, null), provider.reasons)
    }

    @Test fun `closed gate mismatch and terminal failures make no correction`() = runBlocking {
        val (repo, provider) = setup()
        val selection = selection(repo)
        assertEquals("qualification_gate_disabled", ConnectedReviewService(repo, ConnectedReviewProviderCatalog(listOf(provider)), limits = personalLimits(), monotonicNanos = { 1L }, durableClaimOriginNanos = 1L, personalAttemptAuthority = { true })
            .executeManually(repo.status.snapshotId, selection).reason)
        assertEquals(0, provider.calls)
        val mismatch = ConnectedReviewExecutor(repo, ConnectedReviewExecutionGate { _, _ -> null }, personalLimits(), { 1L }, 1L, personalAttemptAuthority = { true })
            .executeManually(repo.status.snapshotId, selection.copy(modelId = "wrong"), provider)
        assertEquals("provider_selection_mismatch", mismatch.reason)
        assertEquals(0, provider.calls)

        for ((failure, expected) in listOf("timeout" to "attempt_timeout", "resource" to "provider_unavailable",
            "oversize" to "response_budget_exceeded")) {
            val (r, p) = setup()
            p.block = when (failure) {
                "timeout" -> { _, _, _, _ -> awaitCancellation() }
                "resource" -> { _, _, _, _ -> throw IllegalStateException("synthetic") }
                else -> { _, _, _, _ -> ConnectedReviewProviderReply("x".repeat(12 * 1024 + 1), null) }
            }
            val limits = ConnectedReviewExecutionLimits(attemptTimeoutMillis = 100, totalTimeoutMillis = 300, policy = ConnectedReviewExecutionPolicy.PERSONAL)
            val result = ConnectedReviewExecutor(r, ConnectedReviewExecutionGate { _, _ -> null }, limits, { 1L }, 1L, personalAttemptAuthority = { true })
                .executeManually(r.status.snapshotId, selection(r), p)
            assertEquals(1, p.calls)
            assertEquals("UNAVAILABLE", result.state)
            assertEquals(expected, result.reason)
        }
    }

    @Test fun `hosted revocation during inference and validation cannot publish`() = runBlocking {
        val (repo, provider) = setup(hosted = true)
        val ledger = ConnectedReviewConsentLedger()
        val service = ConnectedReviewService(repo, ConnectedReviewProviderCatalog(listOf(provider)), ledger,
            ConnectedReviewExecutionGate { _, _ -> null }, personalLimits(), { 1L }, 1L, personalAttemptAuthority = { true })
        val selection = selection(repo)
        val preview = service.preview(repo.status.snapshotId)
        service.consent(repo.status.snapshotId, ConnectedReviewConsentRequest(selection, preview.packetDigest, true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        provider.block = { _, _, _, _ -> entered.complete(Unit); release.await(); ConnectedReviewProviderReply(valid, BigDecimal("0.01")) }
        val job = launch { service.executeManually(repo.status.snapshotId, selection) }
        entered.await()
        assertTrue(service.revokeConsent(repo.status.snapshotId))
        release.complete(Unit)
        job.join()
        assertEquals("FAILED", repo.status.state)
        assertEquals(null, repo.status.publishedOutput)

        val (r2, p2) = setup(hosted = true)
        val ledger2 = ConnectedReviewConsentLedger()
        lateinit var s2: ConnectedReviewService
        val base = ConnectedReviewInvocation.intake(packet)
        val inv = ConnectedReviewInvocation(base.profile, base.exactPrompt, base.exactCorrectionPrompt, base.consentDigest, { raw ->
                s2.revokeConsent(r2.status.snapshotId)
                ConnectedReviewValidation.Accepted(ValidatedConnectedReviewOutput(raw))
            })
        r2.invocation = inv
        s2 = ConnectedReviewService(r2, ConnectedReviewProviderCatalog(listOf(p2)), ledger2,
            ConnectedReviewExecutionGate { _, _ -> null }, personalLimits(), { 1L }, 1L, personalAttemptAuthority = { true })
        val sel2 = selection(r2)
        val prev2 = s2.preview(r2.status.snapshotId)
        s2.consent(r2.status.snapshotId, ConnectedReviewConsentRequest(sel2, prev2.packetDigest, true))
        p2.responses = listOf(valid)
        val outcome = s2.executeManually(r2.status.snapshotId, sel2)
        assertEquals("REFUSED", outcome.state)
        assertEquals("consent_revoked", outcome.reason)
        assertNull(r2.status.publishedOutput)
    }

    @Test fun `consent revocation proceeds while hosted publication preparation is blocked`() = runBlocking {
        val (delegate, provider) = setup(hosted = true)
        val preparing = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val repo = BlockingPreparationRepo(delegate, preparing, releasePreparation)
        val service = ConnectedReviewService(repo, ConnectedReviewProviderCatalog(listOf(provider)),
            ConnectedReviewConsentLedger(), ConnectedReviewExecutionGate { _, _ -> null }, personalLimits(), { 1L }, 1L, personalAttemptAuthority = { true })
        val selected = selection(delegate)
        val preview = service.preview(delegate.status.snapshotId)
        service.consent(delegate.status.snapshotId, ConnectedReviewConsentRequest(selected, preview.packetDigest, true))
        provider.responses = listOf(valid)

        val execution = async(Dispatchers.Default) { service.executeManually(delegate.status.snapshotId, selected) }
        try {
            withContext(Dispatchers.IO) {
                check(preparing.await(5, TimeUnit.SECONDS)) { "publication preparation did not start" }
            }
            val revoked = withTimeout(5_000) {
                async(Dispatchers.Default) { service.revokeConsent(delegate.status.snapshotId) }.await()
            }
            assertTrue(revoked)
        } finally {
            releasePreparation.countDown()
        }

        val result = withTimeout(5_000) { execution.await() }
        assertEquals("REFUSED", result.state)
        assertEquals("consent_revoked", result.reason)
        assertEquals(1, provider.calls)
        assertNull(delegate.status.publishedOutput)
    }

    @Test fun `validator infrastructure failure is terminal and never requests correction`() = runBlocking {
        val base = ConnectedReviewInvocation.intake(packet)
        val broken = ConnectedReviewInvocation(base.profile, base.exactPrompt, base.exactCorrectionPrompt, base.consentDigest) {
            throw IllegalStateException("synthetic validator failure")
        }
        val (repo, provider) = setup(invocation = broken)
        provider.responses = listOf(valid)
        val result = ConnectedReviewExecutor(repo, ConnectedReviewExecutionGate { _, _ -> null }, personalLimits(), { 1L }, 1L, personalAttemptAuthority = { true })
            .executeManually(repo.status.snapshotId, selection(repo), provider)
        assertEquals("validation_unavailable", result.reason)
        assertEquals(1, provider.calls)
    }

    @Test fun `invalid model timezone is rejection eligible for the one bounded correction`() = runBlocking {
        val bad=ContextIntakeSuggestionDraftV1(ContextIntakeSuggestionContractV1.PROFILE,intake.intakeId,intake.revision,
            listOf(ContextIntakeSuggestionV1("commitment",0,9,"Wednesday",commitment=ProposedCommitmentSuggestionV1(
                "Meeting","commitment",null,null,"Invalid/Timezone",null),unresolvedFields=listOf("startsLocal","endsLocal","recurrence"))),
            listOf(ContextIntakeSuggestionQuestionV1("Which date and recurrence?")))
        val (repo,provider)=setup()
        provider.responses=listOf(ContextIntakeSuggestionContractV1.encodeDraft(bad),valid)
        val result=ConnectedReviewExecutor(repo,ConnectedReviewExecutionGate { _,_->null },personalLimits(),{ 1L },1L,personalAttemptAuthority={ true }).executeManually(repo.status.snapshotId,selection(repo),provider)
        assertEquals("PUBLISHED",result.state)
        assertEquals(listOf("rejected","accepted"),result.attempts.map { it.status })
        assertEquals(2,provider.calls)
    }

    @Test fun `personal send gate exhausting monotonic total budget cannot start inference`() = runBlocking {
        val (repo,provider)=setup()
        var nanos=0L
        var gateChecks=0
        val gate=ConnectedReviewExecutionGate { _,_->
            if(++gateChecks==2) nanos=101_000_000L
            null
        }
        val limits=ConnectedReviewExecutionLimits(attemptTimeoutMillis=100,totalTimeoutMillis=100,policy=ConnectedReviewExecutionPolicy.PERSONAL)
        val result=ConnectedReviewExecutor(repo,gate,limits,monotonicNanos={nanos},durableClaimOriginNanos=0L,personalAttemptAuthority={ true }).executeManually(repo.status.snapshotId,selection(repo),provider)
        assertEquals("total_timeout",result.reason)
        assertEquals(0,provider.calls)
        assertEquals("FAILED",repo.status.state)
    }

    @Test fun `unknown profiles refuse without assembly or provider calls and admission has no legacy inheritance`() = runBlocking {
        val (repo,provider)=setup()
        repo.status=repo.status.copy(contractVersion="unknown-profile-v1")
        val result=ConnectedReviewExecutor(repo,ConnectedReviewExecutionGate { _,_->null },personalLimits(),{ 1L },1L,personalAttemptAuthority={ true }).executeManually(repo.status.snapshotId,selection(repo),provider)
        assertEquals("unsupported_profile",result.reason)
        assertEquals(0,provider.calls)
        for(status in ScopedProfileAdmission.inspectAll()) {
            assertFalse(status.executionAllowed)
            assertNull(status.selectedProviderModel)
            assertEquals("unqualified",status.qualificationState)
            assertEquals("closed_not_accepted",status.runtimeAdmissionState)
            assertEquals(300_000L,status.policyLimits.attemptTimeoutMillis)
            assertEquals(600_000L,status.policyLimits.totalTimeoutMillis)
            assertEquals(32768,status.policyLimits.maxPromptBytes)
            assertEquals(12288,status.policyLimits.maxResponseBytes)
        }
        assertFalse(ScopedProfileAdmission.inspect(InterpretationContractV1.PROFILE).supported)
        assertEquals(listOf("unsupported_profile"),ScopedProfileAdmission.inspect("unknown-profile-v1").blockingReasons)
    }

    private fun personalLimits() = ConnectedReviewExecutionLimits(policy = ConnectedReviewExecutionPolicy.PERSONAL)
    private fun service(repo: ProfileRepo, provider: ProfileProvider) = ConnectedReviewService(repo,
        ConnectedReviewProviderCatalog(listOf(provider)), qualificationAndHealth = ConnectedReviewExecutionGate { _, _ -> null }, limits = personalLimits(), monotonicNanos = { 1L }, durableClaimOriginNanos = 1L, personalAttemptAuthority = { true })

    private inner class ProfileProvider(override val providerId: String, override val hosted: Boolean) : ConnectedReviewProvider {
        override val modelId = "synthetic-model"
        override val enforcedMaximumCostUsd = if (hosted) BigDecimal("0.25") else null
        var calls = 0
        var responses = listOf<String>()
        var prompts = mutableListOf<InterpretationPromptV1>()
        var reasons = mutableListOf<String?>()
        var block: suspend (InterpretationPromptV1, String?, Int, BigDecimal?) -> ConnectedReviewProviderReply = { _, _, _, _ ->
            ConnectedReviewProviderReply(responses.getOrElse(calls - 1) { valid }, if (hosted) BigDecimal("0.01") else null)
        }
        override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                                      costLimitUsd: BigDecimal?): ConnectedReviewProviderReply {
            calls++
            prompts += prompt
            reasons += correctionReason
            return block(prompt, correctionReason, responseLimitBytes, costLimitUsd)
        }
    }

    private class BlockingPreparationRepo(
        private val delegate: ConnectedReviewExecutionRepository,
        private val preparing: CountDownLatch,
        private val releasePreparation: CountDownLatch,
    ) : ConnectedReviewExecutionRepository by delegate {
        override fun prepareConnectedReviewPublication(snapshotId: String, output: ValidatedConnectedReviewOutput,
            currentEvidenceDigest: String, currentContext: List<ContextRevisionRefV1>, provider: String,
            model: String, contractVersion: String): () -> ConnectedReviewStatus {
            preparing.countDown()
            check(releasePreparation.await(5, TimeUnit.SECONDS)) { "publication preparation was not released" }
            return delegate.prepareConnectedReviewPublication(snapshotId, output, currentEvidenceDigest,
                currentContext, provider, model, contractVersion)
        }
    }

    private inner class ProfileRepo(var invocation: ConnectedReviewInvocation?) : ConnectedReviewExecutionRepository {
        var status = ConnectedReviewStatus("00000000-0000-0000-0000-000000000010", "synthetic-review", 1,
            "a".repeat(64), "", emptyList(), "2026-03-01", "2026-03-02", null, "local:local-test", "synthetic-model",
            ContextIntakeSuggestionContractV1.PROFILE, "IN_FLIGHT", null, null, "2026-03-02T10:00:00Z")
        init { status = status.copy(packetDigest = (invocation ?: ConnectedReviewInvocation.intake(packet)).consentDigest) }
        override fun connectedReviewStatus(snapshotId: String) = status.also { check(snapshotId == status.snapshotId) }
        override fun connectedReviewPacket(snapshotId: String): InterpretationPacketV1 = error("legacy packet path used")
        override fun connectedReviewInspection(snapshotId: String): ConnectedReviewInspection = error("legacy inspection path used")
        override fun connectedReviewInvocation(snapshotId: String) = invocation ?: ConnectedReviewInvocation.intake(packet)
        override fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus? = if (status.state == "IN_FLIGHT") status.copy(state = "RUNNING").also { status = it } else null
        override fun publishConnectedReview(snapshotId: String, output: ValidatedConnectedReviewOutput, currentEvidenceDigest: String,
            currentContext: List<ContextRevisionRefV1>, provider: String, model: String, contractVersion: String): ConnectedReviewStatus {
            if (status.state == "RUNNING" && status.evidenceDigest == currentEvidenceDigest && status.context == currentContext &&
                status.provider == provider && status.model == model && status.contractVersion == contractVersion)
                status = status.copy(state = "PUBLISHED", publishedOutput = output.text)
            return status
        }
        override fun failConnectedReview(snapshotId: String, reason: String) = status.copy(state = "FAILED", staleReason = reason).also { status = it }
    }
}
