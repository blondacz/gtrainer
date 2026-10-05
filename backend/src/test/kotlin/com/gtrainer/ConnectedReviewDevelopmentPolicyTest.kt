package com.gtrainer

import java.math.BigDecimal
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ConnectedReviewDevelopmentPolicyTest {
    @Test fun `explicit development budgets clamp to remaining ttl and retain all existing payload bounds`() {
        val full = ConnectedReviewExecutionLimits.developmentLocal(Long.MAX_VALUE)
        assertEquals(900_000L, full.attemptTimeoutMillis)
        assertEquals(1_840_000L, full.totalTimeoutMillis)
        assertEquals(ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL, full.policy)
        val short = ConnectedReviewExecutionLimits.developmentLocal(250_000)
        assertEquals(250_000L, short.attemptTimeoutMillis)
        assertEquals(250_000L, short.totalTimeoutMillis)
        assertEquals(32 * 1024, short.maxPacketBytes)
        assertEquals(12 * 1024, short.maxResponseBytes)
        assertFails { ConnectedReviewExecutionLimits.developmentLocal(0) }
        assertFails { ConnectedReviewExecutionLimits(attemptTimeoutMillis = 900_001, totalTimeoutMillis = 1_840_000,
            policy = ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL) }
        assertFails { ConnectedReviewExecutionLimits(attemptTimeoutMillis = 900_000, totalTimeoutMillis = 1_840_001,
            policy = ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL) }
        assertEquals(300_000L, ConnectedReviewExecutionLimits().attemptTimeoutMillis)
        assertEquals(600_000L, ConnectedReviewExecutionLimits().totalTimeoutMillis)
        assertFails { ConnectedReviewExecutionLimits(attemptTimeoutMillis = 600_001, totalTimeoutMillis = 1_200_000) }
        assertFails { ConnectedReviewExecutionLimits(totalTimeoutMillis = 1_200_001) }
        val custom = ConnectedReviewExecutionLimits.developmentLocal(600, 400, 1_000)
        assertEquals(400L, custom.attemptTimeoutMillis)
        assertEquals(600L, custom.totalTimeoutMillis)
    }

    @Test fun `profile identity is explicit and cost caps remain unchanged`() {
        assertEquals("connected-review-development-local-v1", ConnectedReviewExecutionLimits.DEVELOPMENT_PROFILE)
        val limits = ConnectedReviewExecutionLimits.developmentLocal(1)
        assertEquals(BigDecimal("0.25"), limits.maxHostedCostPerAttemptUsd)
        assertEquals(BigDecimal("0.50"), limits.maxHostedCostTotalUsd)
    }

    @Test fun `hosted execution refuses development policy without provider calls`() = runBlocking {
        val repository = BudgetRepository(hosted = true)
        var calls = 0
        val provider = provider(hosted = true) { calls++; ConnectedReviewProviderReply(EMPTY_DRAFT, BigDecimal("0.01")) }
        val result = executor(repository, { 0 }).executeManually(ID, repository.selection, provider)
        assertEquals("development_policy_local_only", result.reason)
        assertEquals(0, calls)
    }

    @Test fun `ordinary service construction rejects development limits`() {
        assertFails {
            ConnectedReviewService(BudgetRepository(), ConnectedReviewProviderCatalog(emptyList()),
                limits = ConnectedReviewExecutionLimits.developmentLocal(100))
        }
    }

    @Test fun `exhausted claim origin closes snapshot without sending`() = runBlocking {
        val repository = BudgetRepository()
        var calls = 0
        val result = executor(repository, { 100_000_000 }).executeManually(ID, repository.selection,
            provider { calls++; ConnectedReviewProviderReply(EMPTY_DRAFT, null) })
        assertEquals("total_timeout", result.reason)
        assertEquals("FAILED", repository.state.state)
        assertEquals(0, calls)
    }

    @Test fun `startup and pre-send health consume the same claim budget`() = runBlocking {
        val repository = BudgetRepository()
        var nanos = 80_000_000L
        var calls = 0
        val executor = ConnectedReviewExecutor(repository,
            ConnectedReviewExecutionGate { _, _ -> nanos += 21_000_000; null },
            ConnectedReviewExecutionLimits.developmentLocal(100), { nanos }, durableClaimOriginNanos = 0)
        val result = executor.executeManually(ID, repository.selection,
            provider { calls++; ConnectedReviewProviderReply(EMPTY_DRAFT, null) })
        assertEquals("total_timeout", result.reason)
        assertEquals(0, calls)
        assertEquals("FAILED", repository.state.state)
    }

    @Test fun `remaining startup time limits transport rather than resetting total`() = runBlocking {
        val repository = BudgetRepository()
        var calls = 0
        val result = executor(repository, { 80_000_000 }).executeManually(ID, repository.selection,
            provider { calls++; awaitCancellation() })
        assertEquals("total_timeout", result.reason)
        assertEquals(1, calls)
        assertEquals("FAILED", repository.state.state)
    }

    @Test fun `late completed reply cannot publish after claim budget expires`() = runBlocking {
        val repository = BudgetRepository()
        var nanos = 80_000_000L
        val result = executor(repository, { nanos }).executeManually(ID, repository.selection,
            provider { nanos = 101_000_000; ConnectedReviewProviderReply(EMPTY_DRAFT, null) })
        assertEquals("total_timeout", result.reason)
        assertEquals(0, repository.publications)
    }

    @Test fun `queue waiting does not consume a later claim budget`() = runBlocking {
        val repository = BudgetRepository()
        val laterClaim = 3_600_000_000_000L
        val result = executor(repository, { laterClaim }, origin = laterClaim).executeManually(ID, repository.selection,
            provider { ConnectedReviewProviderReply(EMPTY_DRAFT, null) })
        assertEquals("PUBLISHED", result.state)
        assertEquals(1, repository.publications)
    }

    @Test fun `attempt observer reports correction lifecycle without response text`() = runBlocking {
        val repository = BudgetRepository()
        val events = mutableListOf<ConnectedReviewAttemptEvent>()
        val prompts = mutableListOf<InterpretationPromptV1>()
        val corrections = mutableListOf<String?>()
        val executor = observedExecutor(repository, events)
        val provider = object : ConnectedReviewProvider {
            override val providerId = "budget-test"
            override val modelId = "synthetic"
            override val hosted = false
            override val enforcedMaximumCostUsd: BigDecimal? = null
            override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?,
                responseLimitBytes: Int, costLimitUsd: BigDecimal?): ConnectedReviewProviderReply {
                prompts.add(prompt)
                corrections.add(correctionReason)
                return ConnectedReviewProviderReply(if (prompts.size == 1) "synthetic-rejected-marker" else EMPTY_DRAFT, null)
            }
        }
        val result = executor.executeManually(ID, repository.selection, provider)
        assertEquals("PUBLISHED", result.state)
        assertEquals(listOf(null, "invalid_output"), corrections)
        assertTrue(prompts[0] === prompts[1])
        assertEquals(listOf(ConnectedReviewAttemptPhase.STARTED, ConnectedReviewAttemptPhase.REJECTED,
            ConnectedReviewAttemptPhase.CORRECTING, ConnectedReviewAttemptPhase.ACCEPTED), events.map { it.phase })
        assertEquals(listOf(1, 1, 2, 2), events.map { it.number })
        assertFalse(Json.encodeToString(events).contains("synthetic-rejected-marker"))
    }

    @Test fun `operational failure is observed once without correction`() = runBlocking {
        val repository = BudgetRepository()
        val events = mutableListOf<ConnectedReviewAttemptEvent>()
        var calls = 0
        val result = observedExecutor(repository, events).executeManually(ID, repository.selection,
            provider { calls++; error("synthetic-provider-error-marker") })
        assertEquals("provider_unavailable", result.reason)
        assertEquals(1, calls)
        assertEquals(listOf(ConnectedReviewAttemptPhase.STARTED, ConnectedReviewAttemptPhase.FAILED), events.map { it.phase })
        assertFalse(Json.encodeToString(events).contains("synthetic-provider-error-marker"))
    }

    @Test fun `fixed adapter failure is recorded without retry or raw error`() = runBlocking {
        val repository = BudgetRepository()
        val events = mutableListOf<ConnectedReviewAttemptEvent>()
        var calls = 0
        val result = observedExecutor(repository, events).executeManually(ID, repository.selection,
            provider { calls++; throw ConnectedReviewProviderFailure(ConnectedReviewProviderFailureCode.BINDING_MISMATCH) })
        assertEquals("runtime_binding_mismatch", result.reason)
        assertEquals(1, calls)
        assertEquals("runtime_binding_mismatch", events.last().reason)
    }

    @Test fun `correction never resets whole run monotonic claim deadline`() = runBlocking {
        val repository = BudgetRepository()
        var nanos = 0L
        var calls = 0
        val result = executor(repository, { nanos }).executeManually(ID, repository.selection,
            provider {
                calls++
                nanos += 60_000_000
                ConnectedReviewProviderReply(if (calls == 1) "invalid-draft-marker" else EMPTY_DRAFT, null)
            })
        assertEquals(2, calls)
        assertEquals("total_timeout", result.reason)
        assertEquals(0, repository.publications)
    }

    @Test fun `cancellation emits metadata without retrying or publishing`() = runBlocking {
        val repository = BudgetRepository()
        val events = mutableListOf<ConnectedReviewAttemptEvent>()
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            observedExecutor(repository, events).executeManually(ID, repository.selection,
                provider { entered.complete(Unit); awaitCancellation() })
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(listOf(ConnectedReviewAttemptPhase.STARTED, ConnectedReviewAttemptPhase.CANCELLED), events.map { it.phase })
        assertEquals("FAILED", repository.state.state)
        assertEquals(0, repository.publications)
    }

    private fun observedExecutor(repository: BudgetRepository, events: MutableList<ConnectedReviewAttemptEvent>) =
        ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null },
            attemptObserver = ConnectedReviewAttemptObserver { events.add(it) })

    private fun executor(repository: BudgetRepository, clock: () -> Long, origin: Long = 0) =
        ConnectedReviewExecutor(repository, ConnectedReviewExecutionGate { _, _ -> null },
            ConnectedReviewExecutionLimits.developmentLocal(100), clock, durableClaimOriginNanos = origin)

    private fun provider(hosted: Boolean = false, reply: suspend () -> ConnectedReviewProviderReply) =
        object : ConnectedReviewProvider {
            override val providerId = "budget-test"
            override val modelId = "synthetic"
            override val hosted = hosted
            override val enforcedMaximumCostUsd = if (hosted) BigDecimal("0.05") else null
            override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                costLimitUsd: BigDecimal?) = reply()
        }

    private class BudgetRepository(hosted: Boolean = false) : ConnectedReviewExecutionRepository {
        val selection = ConnectedReviewProviderSelection("budget-test", "synthetic", if (hosted) "hosted" else "local")
        private val packet = InterpretationPacketV1(InterpretationContractV1.PROFILE,
            AnalysisInput(1, "a".repeat(64), "2025-01-07T00:00:00Z", null,
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), emptyList())
        var state = ConnectedReviewStatus(ID, "synthetic-request", 1, packet.evidence.evidenceReportSha256,
            InterpretationContractV1.promptSha256(packet), emptyList(), "2025-01-01", "2025-01-07", null,
            selection.storageId, selection.modelId, InterpretationContractV1.PROFILE, "IN_FLIGHT", null, null,
            "2025-01-07T00:00:00Z")
        var publications = 0
        override fun connectedReviewStatus(snapshotId: String) = state
        override fun connectedReviewPacket(snapshotId: String) = packet
        override fun connectedReviewInspection(snapshotId: String) = ConnectedReviewInspection(state, selection,
            InterpretationContractV1.prompt(packet), null, true)
        override fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus? {
            if (state.state != "IN_FLIGHT") return null
            state = state.copy(state = "RUNNING")
            return state
        }
        override fun failConnectedReview(snapshotId: String, reason: String): ConnectedReviewStatus {
            state = state.copy(state = "FAILED", staleReason = reason)
            return state
        }
        override fun publishConnectedReview(snapshotId: String, output: ValidatedConnectedReviewOutput,
            currentEvidenceDigest: String, currentContext: List<ContextRevisionRefV1>, provider: String,
            model: String, contractVersion: String): ConnectedReviewStatus {
            publications++
            state = state.copy(state = "PUBLISHED", publishedOutput = output.text)
            return state
        }
    }

    private companion object {
        const val ID = "00000000-0000-0000-0000-000000000010"
        const val EMPTY_DRAFT = """{"profile":"connected-review-v1","interpretations":[],"questions":[]}"""
    }
}
