package com.gtrainer

import kotlinx.coroutines.*

internal fun interface DevelopmentRunExecution {
    suspend fun execute(store: DevelopmentReviewStore, owner: DevelopmentReviewOwnershipSession, claim: DevelopmentOwnedClaim, task: DevelopmentReviewTask)
}

/** Bridges a durably preclaimed run to the ordinary validator/executor without re-claiming it. */
internal class DevelopmentClaimedRepository(private val store: DevelopmentReviewStore, private val claim: DevelopmentOwnedClaim,
    private val health: DevelopmentReviewHealthObserver) : ConnectedReviewExecutionRepository {
    private var admitted = false
    override fun connectedReviewStatus(snapshotId: String): ConnectedReviewStatus {
        require(snapshotId == claim.runId)
        val status = store.connectedReviewStatus(snapshotId)
        return if (!admitted && status.state == "RUNNING") status.copy(state = "IN_FLIGHT") else status
    }
    override fun connectedReviewPacket(snapshotId: String) = store.connectedReviewPacket(snapshotId)
    override fun connectedReviewInspection(snapshotId: String) = store.connectedReviewInspection(snapshotId)
    override fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus? {
        if (snapshotId != claim.runId || admitted || !store.ownedActive(claim)) return null
        admitted = true
        return store.connectedReviewStatus(snapshotId)
    }
    override fun failConnectedReview(snapshotId: String, reason: String): ConnectedReviewStatus {
        require(snapshotId == claim.runId); return store.failOwned(claim, reason)
    }
    override fun publishConnectedReview(snapshotId: String, output: ValidatedConnectedReviewOutput, currentEvidenceDigest: String,
        currentContext: List<ContextRevisionRefV1>, provider: String, model: String, contractVersion: String): ConnectedReviewStatus = synchronized(health) {
        require(snapshotId == claim.runId)
        health.refusal()?.let { return@synchronized store.failOwned(claim, it.reason) }
        store.publishOwned(claim, output, currentEvidenceDigest, currentContext, provider, model, contractVersion)
    }
}

internal class DevelopmentOwnedRunExecutor(private val supervisor: DevelopmentRuntimeSupervisor,
    private val policy: DevelopmentHealthPolicy,
    private val healthSource: (DevelopmentRuntimeHandle, OwnedOllamaRuntimeToken) -> DevelopmentRuntimeHealthSource,
    private val providerFactory: (OwnedOllamaRuntimeToken, OllamaRuntimeOwnershipCheck, Long) -> ConnectedReviewProvider = { token, ownership, timeout ->
        OllamaConnectedReviewProvider(token.desiredBinding, token, ownership, OllamaConnectedReviewProvider.ownedClient(timeout), timeout)
    }, private val nanos: () -> Long = System::nanoTime) : DevelopmentRunExecution {
    override suspend fun execute(store: DevelopmentReviewStore, owner: DevelopmentReviewOwnershipSession, claim: DevelopmentOwnedClaim, task: DevelopmentReviewTask) {
        var handle: DevelopmentRuntimeHandle? = null
        try {
            val remaining = claim.limits.totalTimeoutMillis - (nanos() - claim.claimedAtNanos) / 1_000_000
            val finished = if (remaining > 0) withTimeoutOrNull(remaining) {
                handle = supervisor.start(claim)
                val runtime = handle ?: throw DevelopmentReviewOwnershipFailure("runtime_ownership_unavailable")
                if (!store.registerRuntime(runtime)) throw DevelopmentReviewOwnershipFailure("runtime_ownership_unavailable")
                val token = OwnedOllamaRuntimeToken(claim.owner.number, claim.generation, claim.boundary.containerId, task.model)
                executeWithHealth(store, claim, task, runtime, token)
                true
            } else null
            if (finished == null) store.failOwned(claim, "total_timeout")
        } catch (cancelled: CancellationException) {
            store.failOwned(claim, "cancelled"); throw cancelled
        } catch (_: Exception) { store.failOwned(claim, "execution_failed") }
        finally {
            withContext(NonCancellable) {
                if (handle == null) {
                    handle = runCatching { withTimeoutOrNull(5_000) { supervisor.lookup(claim) } }.getOrNull()
                    handle?.let { runCatching { store.registerRuntime(it) } }
                }
                handle?.let { DevelopmentRuntimeCleanup(store, supervisor, nanos).stop(it) }
                // Without a bound handle/receipt the start intent remains uncertain and blocks the next run.
            }
        }
    }

    private suspend fun executeWithHealth(store: DevelopmentReviewStore, claim: DevelopmentOwnedClaim, task: DevelopmentReviewTask,
        handle: DevelopmentRuntimeHandle, token: OwnedOllamaRuntimeToken) = coroutineScope {
        val source = healthSource(handle, token)
        try { DevelopmentReviewHealthObserver(token, claim.boundary, policy, this, source, nanos).use { observer ->
            val gate = DevelopmentReviewGate(store, claim, token, observer) { supervisor.running(handle) }
            val watchdog = launch { observer.watchdog { store.failOwned(claim, it.reason); this@coroutineScope.cancel("runtime_health_failed") } }
            try {
                observer.opportunity().refusal?.let { store.failOwned(claim, it.reason); return@coroutineScope }
                if (!store.ownedActive(claim)) return@coroutineScope
                val monitoring = launch {
                    while (isActive) {
                        delay(5_000)
                        observer.opportunity().refusal?.let { store.failOwned(claim, it.reason); this@coroutineScope.cancel("runtime_health_failed") }
                    }
                }
                val provider = providerFactory(token, gate, claim.limits.attemptTimeoutMillis)
                try {
                    val result = ConnectedReviewExecutor(DevelopmentClaimedRepository(store, claim, observer), gate, claim.limits, nanos,
                        claim.claimedAtNanos, ConnectedReviewAttemptObserver { store.recordAttempt(claim, it) })
                        .executeManually(claim.runId, ConnectedReviewProviderSelection(task.model.providerId, task.model.modelId, "local"), provider)
                    if (store.task(claim.runId).outcome == DevelopmentReviewOutcome.RUNNING) store.failOwned(claim, result.reason)
                } finally {
                    monitoring.cancel()
                    runCatching { (provider as? AutoCloseable)?.close() }
                }
            } finally { watchdog.cancel() }
        } } finally { runCatching { (source as? AutoCloseable)?.close() } }
    }
}
