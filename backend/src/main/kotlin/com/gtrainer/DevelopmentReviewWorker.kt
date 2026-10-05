package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicReference

/** One elected owner. Notifications are hints, durable events are the authority; no idle poll/TTL timer. */
internal class DevelopmentReviewWorker(private val store: DevelopmentReviewStore, private val boundary: DevelopmentNodeBoundary,
    private val execution: DevelopmentRunExecution, private val verifier: DevelopmentNodeTerminationVerifier = DevelopmentNodeTerminationVerifier(),
    private val configuredModel: (DevelopmentReviewModelBinding) -> Boolean = { false }, private val memoryLimitBytes: Long? = null,
    private val supervisor: DevelopmentRuntimeSupervisor? = null, private val health: () -> DevelopmentHealthDiagnostics? = { null }) {
    private val notifications = Channel<Unit>(Channel.CONFLATED)
    private val active = AtomicReference<String?>(null)
    private var started = false
    @Volatile private var failure: String? = null
    @Volatile private var shuttingDown = false

    fun notifyCommitted() { notifications.trySend(Unit) }
    fun beginShutdown() {
        shuttingDown = true
        active.get()?.let { runCatching { store.cancel(it) } } // Fence late publication BEFORE coroutine/process cancellation.
    }
    fun diagnostics(): DevelopmentWorkerDiagnostics {
        val pending = store.pendingExecutions().size
        return DevelopmentWorkerDiagnostics(true, active.get(), pending > 0 && active.get() == null, pending,
            failure ?: if (pending > 0 && active.get() == null) "runtime_stop_unconfirmed" else "development_worker_ready", memoryLimitBytes,
            health = health())
    }

    @Synchronized fun start(scope: CoroutineScope): Job {
        check(!started); started = true
        return scope.launch(Dispatchers.IO) {
            try {
                store.openOwnership(boundary.nodeName).use { owner ->
                    owner.reconcile(verifier) // Startup ownership reconciliation, NOT an unconditional retention sweep.
                    drain(owner)
                    for (ignored in notifications) drain(owner)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = "development_worker_unavailable" }
        }
    }

    private suspend fun drain(owner: DevelopmentReviewOwnershipSession) {
        val processed = mutableSetOf<Long>()
        val cleaned = mutableSetOf<Long>()
        val retried = mutableSetOf<String>()
        var progress: Boolean
        do {
            progress = false
            for (event in store.pendingEvents()) {
                if (!processed.add(event.id)) continue // Each durable event has bounded cleanup/retry work per wake-up.
                val retention = if (cleaned.add(event.id)) reconcilePhase(owner, retried) else DevelopmentRetentionResult(emptyList(), false)
                if (processEvent(owner, event, retention)) { store.acknowledge(event); progress = true }
            }
            if (owner.pending().isEmpty()) processed.removeAll(store.pendingEvents().filter { it.kind == "submitted" }.map { it.id }.toSet())
        } while (progress && store.pendingEvents().any { it.id !in processed })
    }

    private suspend fun reconcilePhase(owner: DevelopmentReviewOwnershipSession, retried: MutableSet<String>): DevelopmentRetentionResult {
        val retention = store.cleanupDue()
        if (supervisor != null) for (pending in owner.pending()) {
            if (retried.add(pending.runId)) store.runtimeHandle(pending, owner.epoch)?.let {
                DevelopmentRuntimeCleanup(store, supervisor).stop(it)
            }
        }
        owner.reconcile(verifier)
        return retention
    }

    private data class Disposition(val reasons: List<DevelopmentWorkerReason>, val executed: Boolean = false, val acknowledge: Boolean = true)

    private suspend fun processEvent(owner: DevelopmentReviewOwnershipSession, event: DevelopmentQueueEvent, retention: DevelopmentRetentionResult): Boolean {
        val disposition = when (event.kind) {
            "maintenance" -> {
                store.maintenanceTasks().find { it.id == event.taskId }?.let { store.finishMaintenance(it, owner.pending().isNotEmpty(), retention.failed) }
                Disposition(listOf(DevelopmentWorkerReason.MAINTENANCE_COMPLETED))
            }
            "submitted" -> submitted(owner, event)
            "queued_input_superseded" -> Disposition(listOf(DevelopmentWorkerReason.QUEUED_INPUT_SUPERSEDED))
            "running_predecessor_preserved" -> Disposition(listOf(DevelopmentWorkerReason.RUNNING_PREDECESSOR_PRESERVED))
            else -> Disposition(listOf(DevelopmentWorkerReason.EVENT_RECONCILED))
        }
        val reasons = disposition.reasons.toMutableList()
        if (retention.deletedIds.isNotEmpty()) reasons += DevelopmentWorkerReason.PAYLOAD_DELETED
        if (retention.expiredQueued) reasons += DevelopmentWorkerReason.EXPIRED_BEFORE_CLAIM
        if (retention.failed) reasons += DevelopmentWorkerReason.CLEANUP_FAILED
        val blocked = owner.pending().isNotEmpty()
        if (blocked && active.get() == null) reasons += DevelopmentWorkerReason.RUNTIME_STOP_UNCONFIRMED
        store.recordSummary(event, reasons, retention.deletedIds + listOfNotNull(event.taskId), retention.deletedIds.size, disposition.executed, blocked)
        return disposition.acknowledge
    }

    private suspend fun submitted(owner: DevelopmentReviewOwnershipSession, event: DevelopmentQueueEvent): Disposition {
        val task = event.taskId?.let { id -> try { store.task(id) } catch (_: ConnectedReviewNotFound) { null } }
        if (task?.outcome == DevelopmentReviewOutcome.SUPERSEDED) return Disposition(listOf(DevelopmentWorkerReason.QUEUED_INPUT_SUPERSEDED))
        if (task == null || task.outcome == DevelopmentReviewOutcome.EXPIRED) return Disposition(listOf(DevelopmentWorkerReason.EXPIRED_BEFORE_CLAIM))
        if (task.outcome != DevelopmentReviewOutcome.QUEUED) return Disposition(listOf(DevelopmentWorkerReason.EVENT_RECONCILED))
        if (!configuredModel(task.model)) {
            store.refuseQueued(task.id, "provider_not_configured")
            return Disposition(listOf(DevelopmentWorkerReason.EXECUTION_REFUSED))
        }
        val claim = owner.claim(task.id, boundary)
            ?: return Disposition(listOf(DevelopmentWorkerReason.RUNTIME_STOP_UNCONFIRMED), acknowledge = false)
        runActive(owner, claim, task)
        val reasons = mutableListOf(DevelopmentWorkerReason.EXECUTED)
        if (store.task(task.id).outcome == DevelopmentReviewOutcome.FAILED && store.attempts(task.id).isEmpty()) reasons += DevelopmentWorkerReason.EXECUTION_REFUSED
        return Disposition(reasons, executed = true)
    }

    private suspend fun runActive(owner: DevelopmentReviewOwnershipSession, claim: DevelopmentOwnedClaim, task: DevelopmentReviewTask) = supervisorScope {
        active.set(task.id)
        if (shuttingDown) store.cancel(task.id)
        val run = async { execution.execute(store, owner, claim, task) }
        try {
            while (!run.isCompleted) {
                try {
                    select<Unit> {
                        run.onAwait { }
                        notifications.onReceive {
                            if (store.task(task.id).outcome != DevelopmentReviewOutcome.RUNNING) run.cancel()
                            // New input and maintenance do not interrupt a healthy running predecessor.
                        }
                    }
                } catch (cancelled: CancellationException) { if (!currentCoroutineContext().isActive) throw cancelled }
            }
            try { run.await() } catch (cancelled: CancellationException) { if (!currentCoroutineContext().isActive) throw cancelled }
        } finally {
            withContext(NonCancellable) { run.cancelAndJoin() }
            active.set(null)
        }
    }
}
