package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DevelopmentReviewRunExecutorTest {
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))
    private val policy = DevelopmentHealthPolicy(5L * 1024 * 1024 * 1024, false)
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model", "Synthetic", "model:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(500, 1000))
    private val valid = InterpretationContractV1.encodeDraft(InterpretationDraftV1(InterpretationContractV1.PROFILE, emptyList(), emptyList()))

    @Test fun `one owned runtime spans correction then durably confirms stop after publication`() = runBlocking {
        fixture { store, owner, claim, task ->
            val supervisor = Control()
            var calls = 0
            val closed = java.util.concurrent.atomic.AtomicInteger()
            val executor = executor(supervisor, closed = closed) { _, correction ->
                calls++
                assertEquals(if (calls == 1) null else "invalid_output", correction)
                if (calls == 1) "not-json-private-marker" else valid
            }
            executor.execute(store, owner, claim, task)
            assertEquals(2, calls)
            assertEquals(1, supervisor.starts)
            assertEquals(1, supervisor.stops)
            assertEquals(1, closed.get())
            assertEquals(DevelopmentReviewOutcome.SUCCESSFUL, store.task(task.id).outcome)
            assertEquals(DevelopmentReviewExecutionState.STOPPED_CONFIRMED, store.task(task.id).execution)
            assertEquals(listOf(ConnectedReviewAttemptPhase.REJECTED, ConnectedReviewAttemptPhase.ACCEPTED), store.attempts(task.id).map { it.phase })
            assertFalse(Json.encodeToString(store.attempts(task.id)).contains("private-marker"))
        }
    }

    @Test fun `attempt deadline and health refusal both terminate without correction`() = runBlocking {
        fixture { store, owner, claim, task ->
            val supervisor = Control()
            var calls = 0
            executor(supervisor) { _, _ -> calls++; delay(2_000); valid }.execute(store, owner, claim, task)
            assertEquals(1, calls)
            assertEquals("attempt_timeout", store.task(task.id).reason)
            assertEquals(1, supervisor.stops)
            assertEquals(DevelopmentReviewExecutionState.STOPPED_CONFIRMED, store.task(task.id).execution)
        }
        fixture { store, owner, claim, task ->
            val supervisor = Control()
            executor(supervisor, healthy = false) { _, _ -> fail("health refusal must not send") }.execute(store, owner, claim, task)
            assertEquals("resource_health_failed", store.task(task.id).reason)
            assertEquals(1, supervisor.stops)
            assertNull(store.connectedReviewInspection(task.id).review)
        }
    }

    @Test fun `shutdown suppresses publication and uncertain stop preserves admission block`() = runBlocking {
        fixture { store, owner, claim, task ->
            val entered = CompletableDeferred<Unit>()
            val supervisor = Control(confirm = false)
            val job = launch { executor(supervisor) { _, _ -> entered.complete(Unit); awaitCancellation() }.execute(store, owner, claim, task) }
            withTimeout(3_000) { entered.await() }
            job.cancelAndJoin()
            assertEquals(DevelopmentReviewOutcome.CANCELLED, store.task(task.id).outcome)
            assertEquals(DevelopmentReviewExecutionState.UNKNOWN, store.task(task.id).execution)
            assertEquals(1, supervisor.stops)
            val next = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            assertNull(owner.claim(next.id, boundary))
        }
    }

    @Test fun `storage failure cannot skip physical owned termination`() = runBlocking {
        fixture { store, _, claim, _ ->
            val supervisor = Control()
            val handle = DevelopmentRuntimeHandle(claim, 8, 17)
            assertTrue(store.registerRuntime(handle))
            store.close()
            assertFalse(DevelopmentRuntimeCleanup(store, supervisor).stop(handle))
            assertEquals(1, supervisor.stops)
        }
    }

    private fun executor(control: Control, healthy: Boolean = true, closed: java.util.concurrent.atomic.AtomicInteger? = null,
        generate: suspend (InterpretationPromptV1, String?) -> String) =
        DevelopmentOwnedRunExecutor(control, policy, { _, token -> object : DevelopmentRuntimeHealthSource, AutoCloseable {
            override suspend fun collect(request: DevelopmentHealthRequest): DevelopmentHealthReadings {
                val now = System.nanoTime()
                return DevelopmentHealthReadings(DevelopmentHealthMeasurement(DevelopmentRuntimeHealth(token.epoch, token.runtimeGeneration, boundary,
                    token.desiredBinding, true, 0), now), DevelopmentHealthMeasurement(DevelopmentHostResources(
                    if (healthy) DevelopmentHealthPolicy.HOST_HEADROOM_BYTES else 0, policy.applianceMemoryLimitBytes), now))
            }
            override fun close() { closed?.incrementAndGet() }
        } }, { _, _, _ -> object : ConnectedReviewProvider {
            override val providerId = "ollama"
            override val modelId = "model"
            override val hosted = false
            override val enforcedMaximumCostUsd = null
            override suspend fun generate(prompt: InterpretationPromptV1, correctionReason: String?, responseLimitBytes: Int,
                costLimitUsd: java.math.BigDecimal?) = ConnectedReviewProviderReply(generate(prompt, correctionReason), null)
        } })

    private suspend fun fixture(action: suspend (DevelopmentReviewStore, DevelopmentReviewOwnershipSession, DevelopmentOwnedClaim, DevelopmentReviewTask) -> Unit) {
        DevelopmentReviewStore(Files.createTempDirectory("owned-run").resolve("isolated.sqlite")).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                val task = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                action(store, owner, assertNotNull(owner.claim(task.id, boundary)), task)
            }
        }
    }
    private class Control(private val confirm: Boolean = true) : DevelopmentRuntimeSupervisor {
        var starts = 0
        var stops = 0
        override suspend fun start(claim: DevelopmentOwnedClaim): DevelopmentRuntimeHandle { starts++; return DevelopmentRuntimeHandle(claim, 8, 17) }
        override suspend fun running(handle: DevelopmentRuntimeHandle) = true
        override suspend fun stop(handle: DevelopmentRuntimeHandle, budgetMillis: Long): DevelopmentRuntimeStopReceipt? {
            stops++
            if (!confirm) return null
            return DevelopmentRuntimeStopReceipt.decode(handle, buildJsonObject {
                put("identity", identity(handle.claim)); put("keeper_pid", handle.keeperPid); put("keeper_start_ticks", handle.keeperStartTicks)
                put("confirmed", true); put("mechanism", DevelopmentRuntimeStopReceipt.MECHANISM); put("reaped", 2); put("elapsed_millis", 1); put("escalated", false)
            }.toString().toByteArray())
        }
    }
}
