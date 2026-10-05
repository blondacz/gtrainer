package com.gtrainer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DevelopmentReviewOwnershipTest {
    private val caseId = "matched-run-recovery-facts-only"
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model-a", "Synthetic model", "model-a:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))
    private val replacement = boundary.copy(containerId = "b".repeat(64))
    private val output = ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(
        InterpretationDraftV1(InterpretationContractV1.PROFILE, emptyList(), emptyList())))

    @Test fun `lifetime lock rejects concurrent owners across connections and nodes without advancing epoch`() {
        val path = Files.createTempDirectory("owner-contention").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { first ->
            DevelopmentReviewStore(path).use { second ->
                first.openOwnership(boundary.nodeName).use { owner ->
                    assertEquals(1, owner.epoch.number)
                    val pool = Executors.newSingleThreadExecutor()
                    try {
                    val failure = pool.submit(Callable {
                            assertFailsWith<DevelopmentReviewOwnershipFailure> { second.openOwnership("other-node") }
                        }).get()
                        assertEquals("development_owner_busy", failure.reason)
                    } finally { pool.shutdownNow() }
                    val run = first.submit(first.createPreview(caseId, config).id)
                    assertNotNull(owner.claim(run.id, boundary))
                    assertNull(second.claimConnectedReview(run.id))
                    assertEquals(1, owner.epoch.number)
                }
            }
        }
        DevelopmentReviewStore(path).use { store ->
            store.openOwnership(boundary.nodeName).use { assertEquals(2, it.epoch.number) }
            assertEquals("development_owner_node_mismatch",
                assertFailsWith<DevelopmentReviewOwnershipFailure> { store.openOwnership("other-node") }.reason)
            store.openOwnership(boundary.nodeName).use { assertEquals(3, it.epoch.number) }
        }
    }

    @Test fun `recovery fences old output and fails claimed run but preserves unclaimed work`() = runBlocking {
        val path = Files.createTempDirectory("owner-recovery").resolve("isolated.sqlite")
        lateinit var claim: DevelopmentOwnedClaim
        lateinit var queuedId: String
        DevelopmentReviewStore(path).use { store ->
            val owner = store.openOwnership(boundary.nodeName)
            val running = store.submit(store.createPreview(caseId, config).id)
            claim = assertNotNull(owner.claim(running.id, boundary))
            assertTrue(owner.started(claim))
            queuedId = store.submit(store.createPreview(caseId, config).id).id
            assertNull(owner.claim(queuedId, boundary))
            owner.close() // A released lock is not termination proof.
            assertFailsWith<DevelopmentReviewOwnershipFailure> { owner.publish(claim, output) }
        }
        DevelopmentReviewStore(path).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                assertEquals(2, owner.epoch.number)
                assertEquals(DevelopmentReviewOutcome.FAILED, store.task(claim.runId).outcome)
                assertEquals("worker_interrupted", store.task(claim.runId).reason)
                assertEquals(DevelopmentReviewExecutionState.UNKNOWN, store.task(claim.runId).execution)
                assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(queuedId).outcome)
                assertFalse(store.ownedStarted(claim))
                val late = publish(store, claim)
                assertEquals("FAILED", late.state)
                assertNull(late.publishedOutput)
                assertEquals("worker_interrupted", store.failOwned(claim, "cancelled").staleReason)
                assertNull(owner.claim(queuedId, replacement))
                assertEquals(0, owner.reconcile(DevelopmentNodeTerminationVerifier()))
                assertEquals(1, owner.pending().size)
                assertEquals(1, owner.reconcile(verifier()))
                assertEquals(DevelopmentReviewExecutionState.STOPPED_CONFIRMED, store.task(claim.runId).execution)
                assertEquals(DevelopmentReviewOutcome.FAILED, store.task(claim.runId).outcome)
                val next = assertNotNull(owner.claim(queuedId, replacement))
                assertTrue(next.generation > claim.generation)
                assertTrue(owner.started(next))
                assertEquals("PUBLISHED", owner.publish(next, output).state)
                // Even publication does not permit a new runtime until its bound receipt is persisted.
                val third = store.submit(store.createPreview(caseId, config).id)
                assertNull(owner.claim(third.id, replacement))
            }
        }
    }

    @Test fun `starting claim is interrupted even before any start acknowledgement`() = runBlocking {
        val path = Files.createTempDirectory("owner-start-intent").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            val id = store.submit(store.createPreview(caseId, config).id).id
            store.openOwnership(boundary.nodeName).use { assertNotNull(it.claim(id, boundary)) }
            store.openOwnership(boundary.nodeName).use { owner ->
                assertEquals(DevelopmentReviewOutcome.FAILED, store.task(id).outcome)
                assertEquals(boundary, owner.pending().single().boundary)
                assertEquals(1, owner.reconcile(verifier("HOST_REBOOTED", "00000000-0000-0000-0000-000000000002")))
                assertTrue(owner.pending().isEmpty())
                assertNull(owner.claim(id, replacement)) // Interrupted claimed inference is never replayed.
            }
        }
    }

    @Test fun `restart preserves terminal outcome but still reconciles unfinished execution`() = runBlocking {
        for (outcome in listOf("SUCCESSFUL", "FAILED", "CANCELLED")) {
            val path = Files.createTempDirectory("owner-terminal-recovery").resolve("isolated.sqlite")
            DevelopmentReviewStore(path).use { store ->
                val id = store.submit(store.createPreview(caseId, config).id).id
                store.openOwnership(boundary.nodeName).use { owner ->
                    val claim = assertNotNull(owner.claim(id, boundary))
                    assertTrue(owner.started(claim))
                    when (outcome) {
                        "SUCCESSFUL" -> owner.publish(claim, output)
                        "CANCELLED" -> store.failOwned(claim, "cancelled")
                        else -> store.failOwned(claim, "provider_unavailable")
                    }
                }
                store.openOwnership(boundary.nodeName).use { owner ->
                    assertEquals(outcome, store.task(id).outcome.name)
                    assertEquals(DevelopmentReviewExecutionState.UNKNOWN, store.task(id).execution)
                    val queued = store.submit(store.createPreview(caseId, config).id)
                    assertNull(owner.claim(queued.id, replacement))
                    assertEquals(1, owner.reconcile(verifier()))
                    assertEquals(outcome, store.task(id).outcome.name)
                    assertEquals(DevelopmentReviewExecutionState.STOPPED_CONFIRMED, store.task(id).execution)
                }
            }
        }
    }

    @Test fun `lifetime lock is visible to another operating-system process`() {
        val path = Files.createTempDirectory("owner-process-lock").resolve("isolated.sqlite")
        val source = path.parent.resolve("LockProbe.java")
        Files.writeString(source, """
            import java.nio.channels.*;
            import java.nio.file.*;
            class LockProbe {
                public static void main(String[] args) throws Exception {
                    try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
                        try (var lock = channel.tryLock()) { System.out.print(lock == null ? "BUSY" : "ACQUIRED"); }
                    }
                }
            }
        """.trimIndent())
        fun probe(): String {
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m",
                source.toString(), path.parent.resolve("development-worker.lock").toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                return process.inputStream.use { it.readNBytes(32).decodeToString() }
            } finally { process.destroyForcibly() }
        }
        DevelopmentReviewStore(path).use { store ->
            store.openOwnership(boundary.nodeName).use { assertEquals("BUSY", probe()) }
            assertEquals("ACQUIRED", probe())
            store.openOwnership(boundary.nodeName).use { assertEquals(2, it.epoch.number) }
        }
    }

    @Test fun `unreachable wrong node and unrelated proof retain block but permit bounded submissions`() = runBlocking {
        val path = Files.createTempDirectory("owner-quarantine").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                assertNotNull(owner.claim(store.submit(store.createPreview(caseId, config).id).id, boundary))
            }
            store.openOwnership(boundary.nodeName).use { owner ->
                val queued = store.submit(store.createPreview(caseId, config).id)
                val unavailable = DevelopmentNodeTerminationVerifier(boundary.nodeName) { throw IllegalStateException("synthetic-private-marker") }
                assertEquals(0, owner.reconcile(unavailable))
                assertEquals(0, owner.reconcile(DevelopmentNodeTerminationVerifier("other-node") { fail("wrong node was contacted") }))
                assertEquals(0, owner.reconcile(DevelopmentNodeTerminationVerifier(boundary.nodeName) { proofBytes(container = "c".repeat(64)) }))
                assertNull(owner.claim(queued.id, replacement))
                assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(queued.id).outcome)
                assertTrue(store.connectedReviewInspection(queued.id).packetAvailable)
            }
        }
    }

    @Test fun `reconciliation never holds database monitor or transaction during node observation`() = runBlocking {
        val path = Files.createTempDirectory("owner-observation-lock").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                assertNotNull(owner.claim(store.submit(store.createPreview(caseId, config).id).id, boundary))
            }
            store.openOwnership(boundary.nodeName).use { owner ->
                val observing = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val task = async {
                    owner.reconcile(DevelopmentNodeTerminationVerifier(boundary.nodeName) {
                        observing.complete(Unit); release.await(); proofBytes()
                    })
                }
                observing.await()
                withTimeout(2_000) {
                    DevelopmentReviewStore(path).use { api ->
                        val queued = api.submit(api.createPreview(caseId, config).id)
                        assertEquals(DevelopmentReviewOutcome.QUEUED, api.task(queued.id).outcome)
                    }
                }
                release.complete(Unit)
                assertEquals(1, task.await())
            }
        }
    }

    @Test fun `publication requires matching epoch generation boundary execution and monotonic deadline`() {
        val path = Files.createTempDirectory("owner-publication").resolve("isolated.sqlite")
        var nanos = 123L
        DevelopmentReviewStore(path, monotonicNanos = { nanos }).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                val run = store.submit(store.createPreview(caseId, config).id)
                val claim = assertNotNull(owner.claim(run.id, boundary))
                assertEquals(nanos, claim.claimedAtNanos)
                assertEquals("RUNNING", publish(store, claim).state)
                assertTrue(owner.started(claim))
                assertEquals("RUNNING", publish(store, claim.copy(generation = claim.generation + 1)).state)
                assertEquals("RUNNING", publish(store, claim.copy(boundary = replacement)).state)
                val status = store.connectedReviewStatus(run.id)
                assertEquals("RUNNING", store.publishConnectedReview(run.id, output, status.evidenceDigest,
                    status.context, status.provider, status.model, status.contractVersion).state)
                assertEquals("RUNNING", store.failConnectedReview(run.id, "cancelled").state)
                nanos += claim.limits.totalTimeoutMillis * 1_000_000
                assertEquals("RUNNING", owner.publish(claim, output).state)
                assertNull(store.connectedReviewStatus(run.id).publishedOutput)
            }
        }
    }

    @Test fun `claim budgets begin at claim and use immutable run expiry rather than preview expiry`() {
        val path = Files.createTempDirectory("owner-ttl").resolve("isolated.sqlite")
        val clock = MutableClock()
        DevelopmentReviewStore(path, clock, monotonicNanos = { 27L }).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                val preview = store.createPreview(caseId, config)
                clock.now = clock.now.plusSeconds(3600)
                val run = store.submit(preview.id)
                clock.now = Instant.parse(run.expiresUtc).minusMillis(700)
                val claim = assertNotNull(owner.claim(run.id, boundary))
                assertEquals(700, claim.limits.totalTimeoutMillis)
                assertEquals(700, claim.limits.attemptTimeoutMillis)
                assertEquals(27, claim.claimedAtNanos)
                assertTrue(owner.started(claim))
                clock.now = Instant.parse(run.expiresUtc)
                assertEquals("EXPIRED", owner.publish(claim, output).state)
                assertNull(store.connectedReviewStatus(run.id).publishedOutput)
            }
        }
    }

    @Test fun `legacy unbound execution is not assumed stopped or assigned replacement identity`() = runBlocking {
        val path = Files.createTempDirectory("owner-legacy").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            val run = store.submit(store.createPreview(caseId, config).id)
            assertNotNull(store.claimConnectedReview(run.id))
            store.openOwnership(boundary.nodeName).use { owner ->
                assertNull(owner.pending().single().boundary)
                assertEquals(0, owner.reconcile(DevelopmentNodeTerminationVerifier(boundary.nodeName) { fail("unbound legacy identity contacted node") }))
                val queued = store.submit(store.createPreview(caseId, config).id)
                assertNull(owner.claim(queued.id, boundary))
                assertEquals(DevelopmentReviewExecutionState.UNKNOWN, store.task(run.id).execution)
            }
        }
    }

    @Test fun `unsafe lock is refused without changing permissions or truncating target`() {
        val path = Files.createTempDirectory("owner-unsafe-lock").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            val lock = path.parent.resolve("development-worker.lock")
            Files.writeString(lock, "synthetic-private-marker")
            val permissions = PosixFilePermissions.fromString("rw-r--r--")
            Files.setPosixFilePermissions(lock, permissions)
            assertEquals("development_owner_unavailable",
                assertFailsWith<DevelopmentReviewOwnershipFailure> { store.openOwnership(boundary.nodeName) }.reason)
            assertEquals("synthetic-private-marker", Files.readString(lock))
            assertEquals(permissions, Files.getPosixFilePermissions(lock))
            Files.delete(lock) // Test-owned regular file only.
            val target = path.parent.resolve("untouched.txt")
            Files.writeString(target, "synthetic-private-marker")
            Files.createSymbolicLink(lock, target)
            assertFailsWith<DevelopmentReviewOwnershipFailure> { store.openOwnership(boundary.nodeName) }
            assertEquals("synthetic-private-marker", Files.readString(target))
        }
    }

    private fun publish(store: DevelopmentReviewStore, claim: DevelopmentOwnedClaim): ConnectedReviewStatus {
        val status = store.connectedReviewStatus(claim.runId)
        return store.publishOwned(claim, output, status.evidenceDigest, status.context, status.provider, status.model, status.contractVersion)
    }

    private fun verifier(result: String = "CONTAINER_EXITED", boot: String = boundary.bootId) =
        DevelopmentNodeTerminationVerifier(boundary.nodeName) { proofBytes(result, boot) }

    private fun proofBytes(result: String = "CONTAINER_EXITED", boot: String = boundary.bootId, container: String = boundary.containerId) =
        """{"profile":"gtrainer-development-node-proof-v1","containerId":"$container","observedBootId":"$boot","result":"$result"}""".toByteArray()

    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
}
