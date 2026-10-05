package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class DevelopmentReviewCancellationTest {
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model", "Synthetic", "model:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))
    private val output = ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(
        InterpretationDraftV1(InterpretationContractV1.PROFILE, emptyList(), emptyList())))

    @Test fun `cancellation wins publication fence before stop while queued admission continues`() = runBlocking {
        fixture { store, owner, handle ->
            assertTrue(store.cancel(handle.claim.runId).accepted)
            assertTrue(store.cancel(handle.claim.runId).accepted)
            assertEquals("CANCELLED", owner.publish(handle.claim, output).state)
            val queued = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            assertNull(owner.claim(queued.id, boundary))
            assertEquals(DevelopmentReviewExecutionState.STOPPING, store.task(handle.claim.runId).execution)
            assertTrue(DevelopmentRuntimeCleanup(store, supervisor(handle)).stop(handle))
            assertEquals(DevelopmentReviewExecutionState.STOPPED_CONFIRMED, store.task(handle.claim.runId).execution)
            assertNotNull(owner.claim(queued.id, boundary))
        }
    }

    @Test fun `successful publication cannot be relabelled cancelled and still requires cleanup`() = runBlocking {
        fixture { store, owner, handle ->
            assertEquals("PUBLISHED", owner.publish(handle.claim, output).state)
            assertFalse(store.cancel(handle.claim.runId).accepted)
            assertTrue(DevelopmentRuntimeCleanup(store, supervisor(handle)).stop(handle))
            assertEquals(DevelopmentReviewOutcome.SUCCESSFUL, store.task(handle.claim.runId).outcome)
        }
    }

    @Test fun `cancellation during startup still registers and terminates its owned boundary`() = runBlocking {
        val path = Files.createTempDirectory("startup-cancel").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                val run = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                val claim = assertNotNull(owner.claim(run.id, boundary))
                assertTrue(store.cancel(run.id).accepted)
                val handle = DevelopmentRuntimeHandle(claim, 8, 17)
                assertTrue(store.registerRuntime(handle))
                assertFalse(store.ownedActive(claim))
                assertTrue(DevelopmentRuntimeCleanup(store, supervisor(handle)).stop(handle))
                assertEquals(DevelopmentReviewOutcome.CANCELLED, store.task(run.id).outcome)
            }
        }
    }

    @Test fun `unknown or foreign receipt blocks execution without blocking bounded submission`() = runBlocking {
        fixture { store, owner, handle ->
            store.failOwned(handle.claim, "total_timeout")
            val uncertain = object : DevelopmentRuntimeSupervisor by supervisor(handle) {
                override suspend fun stop(handle: DevelopmentRuntimeHandle, budgetMillis: Long): DevelopmentRuntimeStopReceipt? = null
            }
            assertFalse(DevelopmentRuntimeCleanup(store, uncertain).stop(handle))
            assertEquals(DevelopmentReviewExecutionState.UNKNOWN, store.task(handle.claim.runId).execution)
            assertEquals("total_timeout", store.task(handle.claim.runId).reason)
            val next = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            assertNull(owner.claim(next.id, boundary))
            assertNull(DevelopmentRuntimeStopReceipt.decode(handle.copy(keeperPid = 9), receipt(handle)))
            assertNull(DevelopmentRuntimeStopReceipt.decode(handle.copy(keeperStartTicks = 18), receipt(handle)))
            assertNull(DevelopmentRuntimeStopReceipt.decode(handle.copy(claim = handle.claim.copy(generation = 19)), receipt(handle)))
        }
    }

    @Test fun `request-close errors and caller cancellation do not skip supervised termination`() = runBlocking {
        fixture { store, _, handle ->
            var stops = 0
            val control = object : DevelopmentRuntimeSupervisor by supervisor(handle) {
                override suspend fun stop(handle: DevelopmentRuntimeHandle, budgetMillis: Long): DevelopmentRuntimeStopReceipt? {
                    stops++
                    assertTrue(budgetMillis in 1..57_000)
                    return DevelopmentRuntimeStopReceipt.decode(handle, receipt(handle))
                }
            }
            val entered = CompletableDeferred<Unit>()
            val worker = launch {
                try { entered.complete(Unit); awaitCancellation() }
                finally { assertTrue(DevelopmentRuntimeCleanup(store, control).stop(handle) { throw IllegalStateException("synthetic-private-marker") }) }
            }
            entered.await(); worker.cancelAndJoin()
            assertEquals(1, stops)
        }
    }

    @Test fun `publication and cancellation race has one authoritative terminal winner across connections`() {
        val path = Files.createTempDirectory("cancel-publish-race").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            DevelopmentReviewStore(path).use { api ->
                store.openOwnership(boundary.nodeName).use { owner ->
                    val id = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id).id
                    val claim = assertNotNull(owner.claim(id, boundary))
                    assertTrue(store.registerRuntime(DevelopmentRuntimeHandle(claim, 8, 17)))
                    val pool = Executors.newFixedThreadPool(2)
                    val start = CountDownLatch(1)
                    try {
                        val publish = pool.submit(Callable { start.await(); owner.publish(claim, output) })
                        val cancel = pool.submit(Callable { start.await(); api.cancel(id) })
                        start.countDown(); publish.get()
                        val accepted = cancel.get().accepted
                        val status = store.connectedReviewStatus(id)
                        assertEquals(if (accepted) "CANCELLED" else "PUBLISHED", status.state)
                        if (accepted) assertNull(status.publishedOutput)
                    } finally { pool.shutdownNow() }
                }
            }
        }
    }

    @Test fun `bounded supervisor protocol checks identities and never echoes rejected fields`() = runBlocking {
        fixture { _, _, handle ->
            val control = DevelopmentReviewSupervisorClient { payload, budget ->
                val request = Json.parseToJsonElement(payload.decodeToString()).jsonObject
                assertEquals(identity(handle.claim), request["identity"])
                when (request["op"]?.jsonPrimitive?.content) {
                    "stop" -> { assertTrue(budget <= 57_000); receipt(handle) }
                    else -> status(handle)
                }
            }
            assertEquals(handle, control.start(handle.claim))
            assertTrue(control.running(handle))
            assertNotNull(control.stop(handle, 57_000))
            val invalid = DevelopmentReviewSupervisorClient { _, _ -> "synthetic-private-marker".toByteArray() }
            assertFailsWith<IllegalArgumentException> { invalid.start(handle.claim) }
            assertNull(DevelopmentRuntimeStopReceipt.decode(handle, receipt(handle).decodeToString().replace("linux_subreaper_waitpid_echild", "http_eof").toByteArray()))
        }
    }

    private suspend fun fixture(action: suspend (DevelopmentReviewStore, DevelopmentReviewOwnershipSession, DevelopmentRuntimeHandle) -> Unit) {
        DevelopmentReviewStore(Files.createTempDirectory("development-stop").resolve("isolated.sqlite")).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                val run = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                val handle = DevelopmentRuntimeHandle(assertNotNull(owner.claim(run.id, boundary)), 8, 17)
                assertTrue(store.registerRuntime(handle))
                action(store, owner, handle)
            }
        }
    }
    private fun supervisor(handle: DevelopmentRuntimeHandle) = object : DevelopmentRuntimeSupervisor {
        override suspend fun start(claim: DevelopmentOwnedClaim) = handle
        override suspend fun running(handle: DevelopmentRuntimeHandle) = true
        override suspend fun stop(handle: DevelopmentRuntimeHandle, budgetMillis: Long) = DevelopmentRuntimeStopReceipt.decode(handle, receipt(handle))
    }
    private fun receipt(handle: DevelopmentRuntimeHandle) = buildJsonObject {
        put("ok", true); put("identity", identity(handle.claim)); put("keeper_pid", handle.keeperPid); put("keeper_start_ticks", handle.keeperStartTicks)
        put("confirmed", true); put("mechanism", DevelopmentRuntimeStopReceipt.MECHANISM); put("reaped", 2); put("escalated", true); put("elapsed_millis", 15)
    }.toString().toByteArray()
    private fun status(handle: DevelopmentRuntimeHandle) = buildJsonObject {
        put("ok", true); put("identity", identity(handle.claim)); put("keeper_pid", handle.keeperPid); put("keeper_start_ticks", handle.keeperStartTicks)
        put("runtime_exited", false); put("confirmed", false)
    }.toString().toByteArray()
}
