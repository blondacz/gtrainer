package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class DevelopmentReviewWorkerTest {
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model", "Synthetic", "model:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))
    private val output = ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(InterpretationDraftV1(InterpretationContractV1.PROFILE, emptyList(), emptyList())))

    @Test fun `startup recovers durable submissions with lost notifications and drains FIFO without overlap`() = runBlocking {
        DevelopmentReviewStore(Files.createTempDirectory("worker-recovery").resolve("isolated.sqlite")).use { store ->
            val tasks = List(3) { store.submit(store.createPreview("matched-run-recovery-facts-only", config).id) }
            val order = mutableListOf<String>()
            val done = CompletableDeferred<Unit>()
            val execution = DevelopmentRunExecution { db, owner, claim, _ ->
                order += claim.runId
                val handle = DevelopmentRuntimeHandle(claim, 8, claim.generation)
                assertTrue(db.registerRuntime(handle))
                assertEquals("PUBLISHED", owner.publish(claim, output).state)
                assertTrue(db.beginOwnedStop(handle))
                assertTrue(db.confirmOwnedStop(assertNotNull(receipt(handle))))
                if (order.size == 3) done.complete(Unit)
            }
            val worker = DevelopmentReviewWorker(store, boundary, execution, configuredModel = { true })
            val job = worker.start(this)
            try { withTimeout(3_000) { done.await() }; assertEquals(tasks.map { it.id }, order) }
            finally { job.cancelAndJoin() }
            assertTrue(store.workerSummaries().any { it.executed })
            assertTrue(store.queue().all { it.outcome == DevelopmentReviewOutcome.SUCCESSFUL })
        }
    }

    @Test fun `idle startup and inspection do not sweep expired unsubmitted previews`() = runBlocking {
        val clock = MutableClock()
        DevelopmentReviewStore(Files.createTempDirectory("worker-idle").resolve("isolated.sqlite"), clock).use { store ->
            repeat(32) { store.createPreview("matched-run-recovery-facts-only", config) }
            clock.now = clock.now.plusSeconds(86400)
            val worker = DevelopmentReviewWorker(store, boundary, DevelopmentRunExecution { _, _, _, _ -> fail("idle inference") })
            val job = worker.start(this)
            try {
                delay(30)
                assertTrue(store.previews().isEmpty())
                assertFailsWith<DevelopmentReviewCapacityReached> { store.createPreview("matched-run-recovery-facts-only", config) }
                val maintenance = store.submitMaintenance()
                worker.notifyCommitted()
                eventually { store.maintenanceTasks().any { it.id == maintenance.id && it.state == "SUCCESSFUL" } }
                assertNotNull(store.createPreview("matched-run-recovery-facts-only", config))
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun `queued input and maintenance preserve healthy active run while explicit cancellation is responsive`() = runBlocking {
        DevelopmentReviewStore(Files.createTempDirectory("worker-cancel").resolve("isolated.sqlite")).use { store ->
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val execution = DevelopmentRunExecution { db, _, claim, _ ->
                val handle = DevelopmentRuntimeHandle(claim, 8, claim.generation)
                db.registerRuntime(handle)
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { db.beginOwnedStop(handle); db.confirmOwnedStop(assertNotNull(receipt(handle))); stopped.complete(Unit) } }
            }
            val run = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            val worker = DevelopmentReviewWorker(store, boundary, execution, configuredModel = { true })
            val job = worker.start(this)
            try {
                withTimeout(3_000) { entered.await() }
                val queued = store.submit(store.createPreview("matched-run-recovery-context-present", config).id)
                val maintenance = store.submitMaintenance()
                worker.notifyCommitted(); delay(30)
                assertEquals(DevelopmentReviewOutcome.RUNNING, store.task(run.id).outcome)
                assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(queued.id).outcome)
                assertEquals("QUEUED", store.maintenanceTasks().first { it.id == maintenance.id }.state)
                store.cancel(queued.id)
                assertTrue(store.cancel(run.id).accepted); worker.notifyCommitted()
                withTimeout(3_000) { stopped.await() }
                eventually { store.maintenanceTasks().any { it.id == maintenance.id && it.state == "SUCCESSFUL" } }
                assertEquals(DevelopmentReviewOutcome.CANCELLED, store.task(run.id).outcome)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun `unknown prior execution remains blocked yet explicit maintenance and queued submissions remain available`() = runBlocking {
        val path = Files.createTempDirectory("worker-quarantine").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            store.openOwnership(boundary.nodeName).use { owner -> owner.claim(store.submit(store.createPreview("matched-run-recovery-facts-only", config).id).id, boundary) }
            val next = store.submit(store.createPreview("matched-run-recovery-context-present", config).id)
            val maintenance = store.submitMaintenance()
            val worker = DevelopmentReviewWorker(store, boundary, DevelopmentRunExecution { _, _, _, _ -> fail("uncertain replay") }, configuredModel = { true })
            val job = worker.start(this)
            try {
                eventually { store.maintenanceTasks().any { it.id == maintenance.id && it.state == "SUCCESSFUL" } }
                assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(next.id).outcome)
                assertTrue(worker.diagnostics().blocked)
                assertTrue(store.workerSummaries().any { DevelopmentWorkerReason.RUNTIME_STOP_UNCONFIRMED in it.reasons })
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun `later maintenance retries an exact same-owner stop receipt before releasing queued inference`() = runBlocking {
        DevelopmentReviewStore(Files.createTempDirectory("worker-retry-stop").resolve("isolated.sqlite")).use { store ->
            val confirm = java.util.concurrent.atomic.AtomicBoolean(false)
            val control = object : DevelopmentRuntimeSupervisor {
                override suspend fun start(claim: DevelopmentOwnedClaim) = DevelopmentRuntimeHandle(claim, 8, claim.generation)
                override suspend fun running(handle: DevelopmentRuntimeHandle) = true
                override suspend fun stop(handle: DevelopmentRuntimeHandle, budgetMillis: Long) = if (confirm.get()) receipt(handle) else null
            }
            var executions = 0
            val execution = DevelopmentRunExecution { db, owner, claim, _ ->
                executions++
                val handle = assertNotNull(control.start(claim))
                assertTrue(db.registerRuntime(handle))
                if (executions == 1) db.failOwned(claim, "execution_failed") else owner.publish(claim, output)
                DevelopmentRuntimeCleanup(db, control).stop(handle)
            }
            store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            val queued = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            val worker = DevelopmentReviewWorker(store, boundary, execution, configuredModel = { true }, supervisor = control)
            val job = worker.start(this)
            try {
                eventually { store.workerSummaries().any { it.blocked } && worker.diagnostics().blocked }
                assertEquals(1, executions)
                assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(queued.id).outcome)
                confirm.set(true)
                store.submitMaintenance(); worker.notifyCommitted()
                eventually { store.task(queued.id).execution == DevelopmentReviewExecutionState.STOPPED_CONFIRMED }
                assertEquals(2, executions)
                assertEquals(DevelopmentReviewOutcome.SUCCESSFUL, store.task(queued.id).outcome)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun `shutdown fences output before cancellation even when a late callback ignores coroutine cancellation`() = runBlocking {
        DevelopmentReviewStore(Files.createTempDirectory("worker-shutdown-fence").resolve("isolated.sqlite")).use { store ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val task = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            val worker = DevelopmentReviewWorker(store, boundary, DevelopmentRunExecution { db, owner, claim, _ ->
                val handle = DevelopmentRuntimeHandle(claim, 8, 17)
                db.registerRuntime(handle)
                try {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await(); assertEquals("CANCELLED", owner.publish(claim, output).state) }
                } finally { withContext(NonCancellable) { db.beginOwnedStop(handle); db.confirmOwnedStop(assertNotNull(receipt(handle))) } }
            }, configuredModel = { true })
            val job = worker.start(this)
            try {
                withTimeout(3_000) { entered.await() }
                worker.beginShutdown()
                job.cancel()
                release.complete(Unit)
                withTimeout(3_000) { job.join() }
                assertEquals(DevelopmentReviewOutcome.CANCELLED, store.task(task.id).outcome)
                assertNull(store.connectedReviewInspection(task.id).review)
                assertEquals(DevelopmentReviewExecutionState.STOPPED_CONFIRMED, store.task(task.id).execution)
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }

    private suspend fun eventually(check: () -> Boolean) = withTimeout(3_000) { while (!check()) delay(10) }
    private fun receipt(handle: DevelopmentRuntimeHandle) = DevelopmentRuntimeStopReceipt.decode(handle, buildJsonObject {
        put("identity", identity(handle.claim)); put("keeper_pid", handle.keeperPid); put("keeper_start_ticks", handle.keeperStartTicks)
        put("confirmed", true); put("mechanism", DevelopmentRuntimeStopReceipt.MECHANISM); put("reaped", 2); put("elapsed_millis", 1); put("escalated", false)
    }.toString().toByteArray())
    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
}
