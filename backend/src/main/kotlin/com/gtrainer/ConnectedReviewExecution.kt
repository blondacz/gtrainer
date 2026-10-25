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
        require(maxAttempts == if(policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) 1 else 2)
        require(maxPacketBytes in 1..32 * 1024 && maxResponseBytes in 1..12 * 1024)
        require(maxHostedCostPerAttemptUsd.signum() > 0 && maxHostedCostTotalUsd >= maxHostedCostPerAttemptUsd)
        if(policy==ConnectedReviewExecutionPolicy.PERSONAL) require(maxHostedCostPerAttemptUsd<=BigDecimal("0.25") && maxHostedCostTotalUsd<=BigDecimal("0.50"))
    }

    companion object {
        /** Work budget excludes the cleanup reserve from the 1200-second owner-UAT deadline. */
        internal fun ownerLocalUat()=ConnectedReviewExecutionLimits(attemptTimeoutMillis=900_000,
            totalTimeoutMillis=1_140_000,maxAttempts=1,policy=ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT)

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
    PERSONAL(300_000, 600_000),
    DEVELOPMENT_LOCAL(900_000, 1_840_000),
    OWNER_LOCAL_UAT(900_000, 1_140_000),
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
    /** Legacy storage retains its decoder; new-profile repositories override this assembly seam. */
    fun connectedReviewInvocation(snapshotId: String): ConnectedReviewInvocation =
        ConnectedReviewInvocation.legacy(connectedReviewPacket(snapshotId))
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
    /** Expensive preparation occurs before the final synchronous publication/consent lock boundary. */
    fun prepareConnectedReviewPublication(snapshotId: String,output: ValidatedConnectedReviewOutput,currentEvidenceDigest: String,
        currentContext: List<ContextRevisionRefV1>,provider: String,model: String,contractVersion: String): ()->ConnectedReviewStatus =
        { publishConnectedReview(snapshotId,output,currentEvidenceDigest,currentContext,provider,model,contractVersion) }
}

class ConnectedReviewBindingConflict(val packetDigest: String): IllegalStateException("Review binding changed during preparation")

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
    private val personalAttemptAuthority: (suspend (Int)->Boolean)? = null,
) {
    init {
        require(durableClaimOriginNanos == null || limits.policy in setOf(ConnectedReviewExecutionPolicy.DEVELOPMENT_LOCAL,ConnectedReviewExecutionPolicy.PERSONAL,ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT)) {
            "Durable claim timing requires an owned execution policy"
        }
        require(limits.policy !in setOf(ConnectedReviewExecutionPolicy.PERSONAL,ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) || (durableClaimOriginNanos!=null && personalAttemptAuthority!=null))
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    suspend fun executeManually(snapshotId: String, selection: ConnectedReviewProviderSelection,
                                catalog: ConnectedReviewProviderCatalog): ConnectedReviewExecutionResult {
        val snapshot = try { repository.connectedReviewStatus(snapshotId) } catch(conflict: ConnectedReviewBindingConflict) {
            return ConnectedReviewExecutionResult(snapshotId,"STALE","binding_conflict",conflict.packetDigest,emptyList())
        }
        val provider = catalog.resolve(selection)
            ?: return ConnectedReviewExecutionResult(snapshotId, "REFUSED", "provider_not_configured", snapshot.packetDigest, emptyList())
        return executeManually(snapshotId, selection, provider)
    }

    suspend fun executeManually(snapshotId: String, selection: ConnectedReviewProviderSelection,
                                provider: ConnectedReviewProvider): ConnectedReviewExecutionResult {
        val snapshot = try { repository.connectedReviewStatus(snapshotId) } catch(conflict: ConnectedReviewBindingConflict) {
            return ConnectedReviewExecutionResult(snapshotId,"STALE","binding_conflict",conflict.packetDigest,emptyList())
        }
        val packetDigest = snapshot.packetDigest
        val attempts = ConnectedReviewAttempts(attemptObserver)
        fun result(state: String, reason: String, review: InterpretationDraftV1? = null) =
            ConnectedReviewExecutionResult(snapshotId, state, reason, packetDigest, attempts.toList(), review)

        if (snapshot.state != "IN_FLIGHT") return result("REFUSED", "snapshot_not_in_flight")
        if(!ScopedProfileAdmission.supportedExecutionContract(snapshot.contractVersion)) return result("REFUSED","unsupported_profile")
        if(snapshot.contractVersion in ScopedProfileAdmission.profiles && limits.policy !in setOf(ConnectedReviewExecutionPolicy.PERSONAL,ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT))
            return result("REFUSED","personal_policy_required")
        if(limits.policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) {
            if(provider.hosted) return result("REFUSED","owner_uat_local_only")
            if(snapshot.contractVersion !in ScopedProfileAdmission.profiles) return result("REFUSED","unsupported_profile")
        }
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

        val invocation = try { repository.connectedReviewInvocation(snapshotId) }
            catch(_: ConnectedReviewBindingConflict) { return result("STALE","binding_conflict") }
        val serializedPromptBytes = listOfNotNull(invocation.exactPrompt,invocation.exactCorrectionPrompt)
            .maxOf { json.encodeToString(it).toByteArray(Charsets.UTF_8).size }
        if (serializedPromptBytes > limits.maxPacketBytes) return result("REFUSED", "input_budget_exceeded")

        val startedAt = durableClaimOriginNanos ?: monotonicNanos()
        if (durableClaimOriginNanos!=null && remainingBudgetMillis(startedAt) <= 0) {
            repository.failConnectedReview(snapshotId, "total_timeout")
            return result("UNAVAILABLE", "total_timeout")
        }
        val totalRemainingMillis = if (durableClaimOriginNanos!=null)
            remainingBudgetMillis(startedAt)
        else limits.totalTimeoutMillis
        return ConnectedReviewExecutionSession(repository, gate, limits, monotonicNanos, snapshot, invocation, provider,
            startedAt, totalRemainingMillis, costLimit, attemptObserver,personalAttemptAuthority).execute()
    }

    private fun remainingBudgetMillis(startedAt: Long): Long =
        limits.totalTimeoutMillis - (monotonicNanos() - startedAt) / 1_000_000L
}
