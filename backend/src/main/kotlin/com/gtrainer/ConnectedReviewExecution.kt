package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/** Budgets for an explicitly invoked connected review. Never inherit scheduler budgets. */
data class ConnectedReviewExecutionLimits(
    val attemptTimeoutMillis: Long = 300_000,
    val totalTimeoutMillis: Long = 600_000,
    val maxAttempts: Int = 2,
    val maxPacketBytes: Int = 32 * 1024,
    val maxResponseBytes: Int = 12 * 1024,
    val maxHostedCostPerAttemptUsd: BigDecimal = BigDecimal("0.25"),
    val maxHostedCostTotalUsd: BigDecimal = BigDecimal("0.50"),
    val policy: ConnectedReviewExecutionPolicy = ConnectedReviewExecutionPolicy.ORDINARY,
) {
    init {
        require(attemptTimeoutMillis in 1..policy.attemptCapMillis)
        require(totalTimeoutMillis in attemptTimeoutMillis..policy.totalCapMillis)
        require(maxAttempts == 2)
        require(maxPacketBytes in 1..32 * 1024 && maxResponseBytes in 1..12 * 1024)
        require(maxHostedCostPerAttemptUsd.signum() > 0 && maxHostedCostTotalUsd >= maxHostedCostPerAttemptUsd)
    }

    companion object {
        fun developmentLocal(
            remainingTtlMillis: Long,
            attemptTimeoutMillis: Long = DEVELOPMENT_ATTEMPT_CAP_MILLIS,
            totalTimeoutMillis: Long = DEVELOPMENT_TOTAL_CAP_MILLIS,
        ): ConnectedReviewExecutionLimits {
            require(remainingTtlMillis > 0) { "Development review TTL is exhausted" }
            val configured = ConnectedReviewExecutionLimits(attemptTimeoutMillis, totalTimeoutMillis,
                policy = ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL)
            val total = minOf(configured.totalTimeoutMillis, remainingTtlMillis)
            return configured.copy(attemptTimeoutMillis = minOf(configured.attemptTimeoutMillis, total), totalTimeoutMillis = total)
        }

        const val DEVELOPMENT_PROFILE = "connected-review-development-local-v1"
        const val DEVELOPMENT_ATTEMPT_CAP_MILLIS = 900_000L
        const val DEVELOPMENT_TOTAL_CAP_MILLIS = 1_840_000L
    }
}

enum class ConnectedReviewExecutionPolicy(val attemptCapMillis: Long, val totalCapMillis: Long) {
    ORDINARY(600_000, 1_200_000),
    DEVELOPMENT_LOCAL(900_000, 1_840_000),
}

data class ConnectedReviewProviderReply(val response: String, val costUsd: BigDecimal?) {
    override fun toString() = "ConnectedReviewProviderReply([REDACTED])"
}

/** Providers must enforce their declared hosted upper bound before accepting a request. */
interface ConnectedReviewProvider {
    val providerId: String
    val modelId: String
    val hosted: Boolean
    val enforcedMaximumCostUsd: BigDecimal?

    suspend fun generate(
        prompt: InterpretationPromptV1,
        correctionReason: String?,
        responseLimitBytes: Int,
        costLimitUsd: BigDecimal?,
    ): ConnectedReviewProviderReply
}

/** Immutable allowlist of configured providers. Resolution is exact; there is no implicit default. */
class ConnectedReviewProviderCatalog(providers: Collection<ConnectedReviewProvider>) {
    private val bySelection: Map<ConnectedReviewProviderSelection, ConnectedReviewProvider> = providers.associateBy { provider ->
        ConnectedReviewProviderSelection(provider.providerId, provider.modelId, if (provider.hosted) "hosted" else "local")
    }

    init {
        require(providers.size <= 8 && bySelection.size == providers.size) { "Invalid connected-review provider catalogue" }
    }

    fun options(): List<ConnectedReviewProviderSelection> = bySelection.keys.sortedWith(
        compareBy({ it.providerKind }, { it.providerId }, { it.modelId }))

    fun resolve(selection: ConnectedReviewProviderSelection): ConnectedReviewProvider? = bySelection[selection]
}

fun interface ConnectedReviewExecutionGate {
    /** Null permits work; otherwise returns a fixed, non-sensitive refusal reason. */
    suspend fun refusalReason(snapshot: ConnectedReviewStatus, provider: ConnectedReviewProvider): String?
}

interface ConnectedReviewExecutionRepository {
    fun connectedReviewStatus(snapshotId: String): ConnectedReviewStatus
    fun connectedReviewPacket(snapshotId: String): InterpretationPacketV1
    fun connectedReviewInspection(snapshotId: String): ConnectedReviewInspection
    fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus?
    fun publishConnectedReview(
        snapshotId: String,
        output: ValidatedConnectedReviewOutput,
        currentEvidenceDigest: String,
        currentContext: List<ContextRevisionRefV1>,
        provider: String,
        model: String,
        contractVersion: String,
    ): ConnectedReviewStatus
    fun failConnectedReview(snapshotId: String, reason: String): ConnectedReviewStatus
}

@Serializable
data class ConnectedReviewAttemptResult(val number: Int, val status: String, val reason: String?, val costUsd: String?)

@Serializable
data class ConnectedReviewExecutionResult(
    val snapshotId: String,
    val state: String,
    val reason: String,
    val packetDigest: String,
    val attempts: List<ConnectedReviewAttemptResult>,
    val review: InterpretationDraftV1? = null,
) {
    override fun toString() = "ConnectedReviewExecutionResult([REDACTED])"
}

@Serializable
data class ConnectedReviewInspection(
    val status: ConnectedReviewStatus,
    val providerSelection: ConnectedReviewProviderSelection,
    val exactPrompt: InterpretationPromptV1,
    val review: InterpretationDraftV1?,
    val packetAvailable: Boolean,
)

/** Manual-only execution primitive. No providers or qualification gate are wired by default. */
class ConnectedReviewExecutor(
    private val repository: ConnectedReviewExecutionRepository,
    private val gate: ConnectedReviewExecutionGate = ConnectedReviewExecutionGate { _, _ -> "qualification_gate_disabled" },
    private val limits: ConnectedReviewExecutionLimits = ConnectedReviewExecutionLimits(),
    private val monotonicNanos: () -> Long = System::nanoTime,
    /** Monotonic origin recorded at durable claim; runtime startup/preflight must precede sends, not this origin. */
    private val durableClaimOriginNanos: Long? = null,
    private val attemptObserver: ConnectedReviewAttemptObserver = ConnectedReviewAttemptObserver { },
) {
    init {
        require(durableClaimOriginNanos == null || limits.policy == ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL) {
            "Durable claim timing requires local development policy"
        }
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    suspend fun executeManually(snapshotId: String, selection: ConnectedReviewProviderSelection,
                                catalog: ConnectedReviewProviderCatalog): ConnectedReviewExecutionResult {
        val snapshot = repository.connectedReviewStatus(snapshotId)
        val provider = catalog.resolve(selection)
            ?: return ConnectedReviewExecutionResult(snapshotId, "REFUSED", "provider_not_configured", snapshot.packetDigest, emptyList())
        return executeManually(snapshotId, selection, provider)
    }

    suspend fun executeManually(snapshotId: String, selection: ConnectedReviewProviderSelection,
                                provider: ConnectedReviewProvider): ConnectedReviewExecutionResult {
        val snapshot = repository.connectedReviewStatus(snapshotId)
        val packetDigest = snapshot.packetDigest
        val attempts = ConnectedReviewAttempts(attemptObserver)
        fun result(state: String, reason: String, review: InterpretationDraftV1? = null) =
            ConnectedReviewExecutionResult(snapshotId, state, reason, packetDigest, attempts.toList(), review)

        if (snapshot.state != "IN_FLIGHT") return result("REFUSED", "snapshot_not_in_flight")
        if (limits.policy == ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL && provider.hosted)
            return result("REFUSED", "development_policy_local_only")
        if (selection.storageId != snapshot.provider || selection.modelId != snapshot.model ||
            provider.providerId != selection.providerId || provider.modelId != selection.modelId ||
            (selection.providerKind == "hosted") != provider.hosted)
            return result("REFUSED", "provider_selection_mismatch")
        val costLimit = if (provider.hosted) {
            val enforced = provider.enforcedMaximumCostUsd ?: return result("REFUSED", "hosted_cost_bound_unavailable")
            if (enforced > limits.maxHostedCostPerAttemptUsd || enforced > limits.maxHostedCostTotalUsd)
                return result("REFUSED", "hosted_cost_bound_exceeded")
            enforced
        } else null
        if (!provider.hosted && provider.enforcedMaximumCostUsd != null) return result("REFUSED", "invalid_local_cost_binding")

        val packet = repository.connectedReviewPacket(snapshotId)
        val prompt = InterpretationContractV1.prompt(packet)
        val serializedPromptBytes = json.encodeToString(prompt).toByteArray(Charsets.UTF_8).size
        if (serializedPromptBytes > limits.maxPacketBytes) return result("REFUSED", "input_budget_exceeded")

        val startedAt = durableClaimOriginNanos ?: monotonicNanos()
        if (limits.policy == ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL && remainingBudgetMillis(startedAt) <= 0) {
            repository.failConnectedReview(snapshotId, "total_timeout")
            return result("UNAVAILABLE", "total_timeout")
        }
        val totalRemainingMillis = if (limits.policy == ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL)
            remainingBudgetMillis(startedAt)
        else limits.totalTimeoutMillis
        return ConnectedReviewExecutionSession(repository, gate, limits, monotonicNanos, snapshot, packet, prompt, provider,
            startedAt, totalRemainingMillis, costLimit, attemptObserver).execute()
    }

    private fun remainingBudgetMillis(startedAt: Long): Long =
        limits.totalTimeoutMillis - (monotonicNanos() - startedAt) / 1_000_000L
}
