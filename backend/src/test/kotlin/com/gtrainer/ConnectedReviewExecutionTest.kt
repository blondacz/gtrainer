package com.gtrainer

import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectedReviewExecutionTest {
    private val today = LocalDate.parse("2025-01-07")
    private val input = AnalysisInput(1, "a".repeat(64), "2025-01-07T00:00:00Z", "run",
        listOf(SummaryFact("e1", "current", "2025-01-01", "2025-01-07", "run", "movingTime", "Moving time",
            "seconds", "sum", 42.0, 2, 2, 7, "2025-01-01", "2025-01-07", listOf("synthetic"),
            listOf("synthetic"), listOf("synthetic"), 0, emptyList())), emptyList(), emptyList(), emptyList(), emptyList())
    private val context = AthleteContext("00000000-0000-0000-0000-000000000001", 1, "goal", "user_report",
        "athlete", "authenticated_user", "2025-01-01", content = "synthetic context")
    private val packet = InterpretationContractV1.packet(input,
        retrieveAthleteContext(listOf(context), ContextRetrievalQuery(today, hardPacketLimit = 10)))
    private val validOutput = """{"profile":"connected-review-v1","interpretations":[{"text":"Possible connection","sources":{"evidenceIds":["e1"],"context":[{"contextId":"00000000-0000-0000-0000-000000000001","revision":1}]},"scope":{"from":"2025-01-01","until":"2025-01-07","sport":"run"},"uncertainty":"moderate"}],"questions":[]}"""

    private fun repository(provider: String = "local-test", model: String = "synthetic-test") = FakeRepository(packet, provider, model)
    private fun selection(repository: FakeRepository) = ConnectedReviewProviderSelection(
        repository.status.provider.substringAfter(':'), repository.status.model, repository.status.provider.substringBefore(':'))
    private fun provider(
        repository: FakeRepository,
        hosted: Boolean = false,
        maxCost: BigDecimal? = null,
        generate: suspend (InterpretationPromptV1, String?, Int, BigDecimal?) -> ConnectedReviewProviderReply,
    ) = FakeProvider(repository.status.provider.substringAfter(':'), repository.status.model, hosted, maxCost, generate)

    @Test fun `production qualification gate defaults closed without calling provider`() = runBlocking {
        val repository = repository()
        var calls = 0
        val provider = provider(repository) { _, _, _, _ -> calls++; ConnectedReviewProviderReply(validOutput, null) }
        val result = ConnectedReviewExecutor(repository).executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("REFUSED", result.state)
        assertEquals("qualification_gate_disabled", result.reason)
        assertEquals(0, calls)
    }

    @Test fun `provider selection mismatch refuses without fallback`() = runBlocking {
        val repository = repository()
        var calls = 0
        val mismatched = FakeProvider("another-provider", "another-model", false, null) { _, _, _, _ ->
            calls++
            ConnectedReviewProviderReply(validOutput, null)
        }
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId,
                ConnectedReviewProviderSelection("another-provider", "another-model", "local"), mismatched)
        assertEquals("provider_selection_mismatch", result.reason)
        assertEquals(0, calls)
    }

    @Test fun `only one concurrent execution can claim a snapshot`() = runBlocking {
        val repository = repository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val provider = provider(repository) { _, _, _, _ ->
            calls++
            entered.complete(Unit)
            release.await()
            ConnectedReviewProviderReply(validOutput, null)
        }
        val executor = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
        val selection = selection(repository)
        val first = launch { executor.executeManually(repository.status.snapshotId, selection, provider) }
        entered.await()
        val overlapping = executor.executeManually(repository.status.snapshotId, selection, provider)
        assertEquals("REFUSED", overlapping.state)
        assertEquals("snapshot_not_in_flight", overlapping.reason)
        assertEquals(1, calls)
        release.complete(Unit)
        first.join()
        assertEquals("PUBLISHED", repository.status.state)
        assertEquals(1, calls)
    }

    @Test fun `provider catalogue requires exact per-request selection and has no default`() = runBlocking {
        val repository = repository()
        var calls = 0
        val configured = provider(repository) { _, _, _, _ -> calls++; ConnectedReviewProviderReply(validOutput, null) }
        val catalog = ConnectedReviewProviderCatalog(listOf(configured))
        val configuredSelection = selection(repository)
        assertEquals(listOf(configuredSelection), catalog.options())
        assertSame(configured, catalog.resolve(configuredSelection))

        val unknown = ConnectedReviewProviderSelection("unlisted", "model-x", "local")
        assertEquals(null, catalog.resolve(unknown))
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId, unknown, catalog)
        assertEquals("provider_not_configured", result.reason)
        assertEquals(0, calls)
    }

    @Test fun `manual request accepts one validated response within the limits`() = runBlocking {
        val repository = repository()
        var calls = 0
        val provider = provider(repository) { _, reason, responseLimit, costLimit ->
            calls++
            assertEquals(null, reason)
            assertEquals(12 * 1024, responseLimit)
            assertEquals(null, costLimit)
            ConnectedReviewProviderReply(validOutput, null)
        }
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("PUBLISHED", result.state)
        assertEquals("validated", result.reason)
        assertEquals(1, calls)
        assertEquals(listOf("accepted"), result.attempts.map { it.status })
        assertTrue(repository.status.publishedOutput!!.contains("Possible connection"))
    }

    @Test fun `one correction reuses immutable prompt and sends only fixed validation reason`() = runBlocking {
        val repository = repository()
        val prompts = mutableListOf<InterpretationPromptV1>()
        val reasons = mutableListOf<String?>()
        val provider = provider(repository) { prompt, reason, _, _ ->
            prompts += prompt
            reasons += reason
            ConnectedReviewProviderReply(if (prompts.size == 1) "not json" else validOutput, null)
        }
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("PUBLISHED", result.state)
        assertEquals(listOf(null, "invalid_output"), reasons)
        assertSame(prompts.first(), prompts.last())
        assertEquals(listOf("rejected", "accepted"), result.attempts.map { it.status })
    }

    @Test fun `snapshot invalidated during provider call cannot publish its response`() = runBlocking {
        val repository = repository()
        val provider = provider(repository) { _, _, _, _ ->
            repository.status = repository.status.copy(state = "STALE", staleReason = "context_changed")
            ConnectedReviewProviderReply(validOutput, null)
        }
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("STALE", result.state)
        assertEquals("snapshot_changed", result.reason)
        assertEquals(null, repository.status.publishedOutput)
    }

    @Test fun `provider timeout never retries`() = runBlocking {
        val repository = repository()
        var calls = 0
        val provider = provider(repository) { _, _, _, _ ->
            calls++
            delay(200)
            ConnectedReviewProviderReply(validOutput, null)
        }
        val limits = ConnectedReviewExecutionLimits(attemptTimeoutMillis = 30, totalTimeoutMillis = 100)
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null }, limits)
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("UNAVAILABLE", result.state)
        assertEquals("attempt_timeout", result.reason)
        assertEquals(1, calls)
        assertEquals("FAILED", repository.status.state)
        val repeated = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null }, limits)
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("snapshot_not_in_flight", repeated.reason)
        assertEquals(1, calls)
    }

    @Test fun `caller cancellation propagates and never schedules a correction attempt`() = runBlocking {
        val repository = repository()
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val provider = provider(repository) { _, _, _, _ ->
            calls++
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch {
            ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
                .executeManually(repository.status.snapshotId, selection(repository), provider)
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
        assertEquals("FAILED", repository.status.state)
    }

    @Test fun `total timeout is bounded independently of per-attempt timeout`() = runBlocking {
        val repository = repository()
        var calls = 0
        val provider = provider(repository) { _, _, _, _ ->
            calls++
            delay(200)
            ConnectedReviewProviderReply(validOutput, null)
        }
        val limits = ConnectedReviewExecutionLimits(attemptTimeoutMillis = 30, totalTimeoutMillis = 50)
        val gate = ConnectedReviewExecutionGate { _, _ -> delay(45); null }
        val result = ConnectedReviewExecutor(repository, gate, limits)
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("UNAVAILABLE", result.state)
        assertEquals("total_timeout", result.reason)
        assertEquals(1, calls)
    }

    @Test fun `health gate and provider resource failures never trigger retries`() = runBlocking {
        val repository = repository()
        var calls = 0
        val healthyGate = ConnectedReviewExecutionGate { _, _ -> "runtime_health_failed" }
        val neverCalled = provider(repository) { _, _, _, _ -> calls++; ConnectedReviewProviderReply(validOutput, null) }
        val unhealthy = ConnectedReviewExecutor(repository, healthyGate)
            .executeManually(repository.status.snapshotId, selection(repository), neverCalled)
        assertEquals("runtime_health_failed", unhealthy.reason)
        assertEquals(0, calls)

        val resourceFailure = provider(repository) { _, _, _, _ ->
            calls++
            throw IllegalStateException("synthetic resource failure")
        }
        val unavailable = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId, selection(repository), resourceFailure)
        assertEquals("provider_unavailable", unavailable.reason)
        assertEquals(1, calls)
    }

    @Test fun `oversized response and repeated invalid response are bounded to two attempts`() = runBlocking {
        val oversizedRepository = repository()
        var oversizedCalls = 0
        val oversizedProvider = provider(oversizedRepository) { _, _, _, _ ->
            oversizedCalls++
            ConnectedReviewProviderReply("x".repeat(12 * 1024 + 1), null)
        }
        val oversized = ConnectedReviewExecutor(oversizedRepository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(oversizedRepository.status.snapshotId, selection(oversizedRepository), oversizedProvider)
        assertEquals("response_budget_exceeded", oversized.reason)
        assertEquals(1, oversizedCalls)

        val invalidRepository = repository()
        var invalidCalls = 0
        val invalidProvider = provider(invalidRepository) { _, _, _, _ -> invalidCalls++; ConnectedReviewProviderReply("invalid", null) }
        val invalid = ConnectedReviewExecutor(invalidRepository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(invalidRepository.status.snapshotId, selection(invalidRepository), invalidProvider)
        assertEquals("REJECTED", invalid.state)
        assertEquals(2, invalidCalls)
        assertEquals(listOf("rejected", "rejected"), invalid.attempts.map { it.status })
    }

    @Test fun `gate revocation after first rejection prevents correction attempt`() = runBlocking {
        val repository = repository()
        var calls = 0
        var gateChecks = 0
        val provider = provider(repository) { _, _, _, _ ->
            calls++
            ConnectedReviewProviderReply("not json", null)
        }
        val gate = ConnectedReviewExecutionGate { _, _ ->
            gateChecks++
            if (gateChecks == 1) null else "consent_revoked"
        }
        val result = ConnectedReviewExecutor(repository, gate)
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("REFUSED", result.state)
        assertEquals("consent_revoked", result.reason)
        assertEquals(1, calls)
        assertEquals("FAILED", repository.status.state)
    }

    @Test fun `external call is refused when provider cannot enforce a cost ceiling`() = runBlocking {
        val repository = repository("hosted-test", "synthetic-hosted")
        var calls = 0
        val provider = provider(repository, hosted = true, maxCost = null) { _, _, _, _ ->
            calls++
            ConnectedReviewProviderReply(validOutput, BigDecimal("0.01"))
        }
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("REFUSED", result.state)
        assertEquals("hosted_cost_bound_unavailable", result.reason)
        assertEquals(0, calls)
    }

    @Test fun `hosted calls receive the approved per-attempt cap and publish only within declared bound`() = runBlocking {
        val repository = repository("hosted-test", "synthetic-hosted")
        var passedCostLimit: BigDecimal? = null
        val provider = provider(repository, hosted = true, maxCost = BigDecimal("0.25")) { _, _, _, costLimit ->
            passedCostLimit = costLimit
            ConnectedReviewProviderReply(validOutput, BigDecimal("0.04"))
        }
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("PUBLISHED", result.state)
        assertEquals(BigDecimal("0.25"), passedCostLimit)
        assertFalse(result.attempts.any { it.status == "rejected" })
    }

    @Test fun `hosted response exceeding its enforced cost cap is not published or retried`() = runBlocking {
        val repository = repository("hosted-test", "synthetic-hosted")
        var calls = 0
        val provider = provider(repository, hosted = true, maxCost = BigDecimal("0.25")) { _, _, _, _ ->
            calls++
            ConnectedReviewProviderReply(validOutput, BigDecimal("0.26"))
        }
        val result = ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null })
            .executeManually(repository.status.snapshotId, selection(repository), provider)
        assertEquals("UNAVAILABLE", result.state)
        assertEquals("hosted_cost_budget_exceeded", result.reason)
        assertEquals(1, calls)
        assertEquals("FAILED", repository.status.state)
    }

    @Test fun `hosted transport receives zero calls without exact consent, after packet change, and after revocation`() = runBlocking {
        suspend fun verify(refusal: suspend (ConnectedReviewService, FakeRepository, ConnectedReviewProviderSelection) -> Unit) {
            val repository = repository("hosted-test", "synthetic-hosted")
            var networkRequests = 0
            val provider = provider(repository, hosted = true, maxCost = BigDecimal("0.25")) { _, _, _, _ ->
                networkRequests++
                ConnectedReviewProviderReply(validOutput, BigDecimal("0.01"))
            }
            val selection = selection(repository)
            val service = ConnectedReviewService(repository, ConnectedReviewProviderCatalog(listOf(provider)),
                qualificationAndHealth = ConnectedReviewExecutionGate { _, _ -> null })
            refusal(service, repository, selection)
            assertEquals(0, networkRequests)
        }

        verify { service, repository, selection ->
            val result = service.executeManually(repository.status.snapshotId, selection)
            assertEquals("consent_required", result.reason)
        }

        verify { service, repository, selection ->
            val preview = service.preview(repository.status.snapshotId)
            assertEquals(repository.status.packetDigest, preview.packetDigest)
            assertEquals(packet, Json.decodeFromString<InterpretationPacketV1>(preview.exactPrompt.userPacketJson))
            service.consent(repository.status.snapshotId,
                ConnectedReviewConsentRequest(selection, preview.packetDigest, approved = true))
            repository.status = repository.status.copy(packetDigest = "c".repeat(64))
            val result = service.executeManually(repository.status.snapshotId, selection)
            assertEquals("consent_required", result.reason)
        }

        verify { service, repository, selection ->
            val preview = service.preview(repository.status.snapshotId)
            service.consent(repository.status.snapshotId,
                ConnectedReviewConsentRequest(selection, preview.packetDigest, approved = true))
            assertTrue(service.revokeConsent(repository.status.snapshotId))
            val result = service.executeManually(repository.status.snapshotId, selection)
            assertEquals("consent_required", result.reason)
        }
    }

    @Test fun `hosted consent rejects provider or packet mismatch`() {
        val repository = repository("hosted-test", "synthetic-hosted")
        val hosted = provider(repository, hosted = true, maxCost = BigDecimal("0.25")) { _, _, _, _ ->
            ConnectedReviewProviderReply(validOutput, BigDecimal("0.01"))
        }
        val selection = selection(repository)
        val service = ConnectedReviewService(repository, ConnectedReviewProviderCatalog(listOf(hosted)))
        val preview = service.preview(repository.status.snapshotId)
        assertFails {
            service.consent(repository.status.snapshotId,
                ConnectedReviewConsentRequest(selection.copy(modelId = "different-model"), preview.packetDigest, approved = true))
        }
        assertFails {
            service.consent(repository.status.snapshotId,
                ConnectedReviewConsentRequest(selection, "d".repeat(64), approved = true))
        }
    }

    private class FakeProvider(
        override val providerId: String,
        override val modelId: String,
        override val hosted: Boolean,
        override val enforcedMaximumCostUsd: BigDecimal?,
        private val block: suspend (InterpretationPromptV1, String?, Int, BigDecimal?) -> ConnectedReviewProviderReply,
    ) : ConnectedReviewProvider {
        override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                                      costLimitUsd: BigDecimal?) = block(prompt, correctionReason, responseLimitBytes, costLimitUsd)
    }

    private class FakeRepository(
        val packet: InterpretationPacketV1,
        provider: String,
        model: String,
    ) : ConnectedReviewExecutionRepository {
        private val refs = packet.context.map { ContextRevisionRefV1(it.contextId, it.revision) }
        var status = ConnectedReviewStatus(
            snapshotId = "00000000-0000-0000-0000-000000000010",
            requestId = "synthetic-review",
            generation = 1,
            evidenceDigest = packet.evidence.evidenceReportSha256,
            packetDigest = InterpretationContractV1.promptSha256(packet),
            context = refs,
            coverageFrom = "2025-01-01",
            coverageUntil = "2025-01-07",
            sport = "run",
            provider = "${if (provider.startsWith("hosted")) "hosted" else "local"}:$provider",
            model = model,
            contractVersion = InterpretationContractV1.PROFILE,
            state = "IN_FLIGHT",
            staleReason = null,
            publishedOutput = null,
            createdUtc = "2025-01-07T00:00:00Z",
        )

        override fun connectedReviewStatus(snapshotId: String): ConnectedReviewStatus {
            check(snapshotId == status.snapshotId)
            return status
        }

        override fun connectedReviewPacket(snapshotId: String): InterpretationPacketV1 {
            check(snapshotId == status.snapshotId)
            return packet
        }

        override fun connectedReviewInspection(snapshotId: String): ConnectedReviewInspection {
            check(snapshotId == status.snapshotId)
            return ConnectedReviewInspection(status, ConnectedReviewProviderSelection(
                status.provider.substringAfter(':'), status.model, status.provider.substringBefore(':')),
                InterpretationContractV1.prompt(packet), status.publishedOutput?.let {
                    InterpretationContractV1.decodeDraft(it, packet)
                }, packetAvailable = true)
        }

        override fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus? {
            check(snapshotId == status.snapshotId)
            if (status.state != "IN_FLIGHT") return null
            status = status.copy(state = "RUNNING")
            return status
        }

        override fun publishConnectedReview(snapshotId: String, output: ValidatedConnectedReviewOutput,
            currentEvidenceDigest: String, currentContext: List<ContextRevisionRefV1>, provider: String, model: String,
            contractVersion: String): ConnectedReviewStatus {
            check(snapshotId == status.snapshotId)
            if (status.state !in setOf("IN_FLIGHT", "RUNNING") || status.evidenceDigest != currentEvidenceDigest || status.context != currentContext ||
                status.provider != provider || status.model != model || status.contractVersion != contractVersion) return status
            status = status.copy(state = "PUBLISHED", publishedOutput = output.text)
            return status
        }

        override fun failConnectedReview(snapshotId: String, reason: String): ConnectedReviewStatus {
            check(snapshotId == status.snapshotId)
            if (status.state in setOf("IN_FLIGHT", "RUNNING")) status = status.copy(state = "FAILED", staleReason = reason)
            return status
        }
    }
}
