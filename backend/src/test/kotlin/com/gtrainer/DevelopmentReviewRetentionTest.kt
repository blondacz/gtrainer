package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class DevelopmentReviewRetentionTest {
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model", "Synthetic", "model:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))

    @Test fun `cleanup deletes bounded batches for expired run pairs and unsubmitted previews but keeps minimal uncertainty`() {
        val clock = MutableClock()
        DevelopmentReviewStore(Files.createTempDirectory("retention-uncertain").resolve("isolated.sqlite"), clock).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                val run = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                assertNotNull(owner.claim(run.id, boundary))
                repeat(31) { store.createPreview("matched-run-recovery-facts-only", config) }
                clock.now = clock.now.plusSeconds(86400)
                assertFalse(store.connectedReviewInspection(run.id).packetAvailable)
                assertEquals(8, store.cleanupDue().deletedIds.size)
                assertEquals(1, owner.pending().size)
                assertFailsWith<ConnectedReviewNotFound> { store.task(run.id) }
                val queued = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                assertNull(owner.claim(queued.id, boundary))
                assertEquals(boundary, owner.pending().single().boundary)
            }
        }
    }

    @Test fun `submitted payload follows run ttl rather than earlier preview ttl and maintenance remains possible at cap`() {
        val clock = MutableClock()
        DevelopmentReviewStore(Files.createTempDirectory("retention-pair").resolve("isolated.sqlite"), clock).use { store ->
            val previews = List(32) { store.createPreview("matched-run-recovery-facts-only", config) }
            clock.now = clock.now.plusSeconds(3600)
            val run = store.submit(previews.first().id)
            clock.now = clock.now.plusSeconds(23 * 3600)
            assertTrue(store.cleanupDue().deletedIds.isNotEmpty())
            assertTrue(store.connectedReviewInspection(run.id).packetAvailable)
            val first = store.submitMaintenance()
            assertEquals(first, store.submitMaintenance())
            assertEquals(1, store.maintenanceTasks().size)
            clock.now = Instant.parse(run.expiresUtc)
            repeat(4) { store.cleanupDue() }
            assertFailsWith<ConnectedReviewNotFound> { store.connectedReviewStatus(run.id) }
            assertTrue(store.queue().isEmpty())
        }
    }

    @Test fun `overflow maintenance uses a bounded coalesced non-payload signal without changing run capacity`() {
        DevelopmentReviewStore(Files.createTempDirectory("maintenance-reserve").resolve("isolated.sqlite")).use { store ->
            repeat(32) { store.createPreview("matched-run-recovery-facts-only", config) }
            repeat(8) { val task = store.submitMaintenance(); store.finishMaintenance(task, false) }
            val signal = store.submitMaintenance()
            assertTrue(signal.coalescedSignal)
            repeat(30) { store.finishMaintenance(store.submitMaintenance(), false) }
            assertEquals(9, store.maintenanceTasks().size)
            assertEquals(signal.createdUtc, store.maintenanceTasks().last().createdUtc)
            assertEquals(9, store.pendingEvents().count { it.kind == "maintenance" })
            assertFailsWith<DevelopmentReviewCapacityReached> { store.createPreview("matched-run-recovery-facts-only", config) }
        }
    }

    @Test fun `deletion failures report fixed independent retention state and retry only on a later event phase`() {
        val path = Files.createTempDirectory("failed-retention").resolve("isolated.sqlite")
        val clock = MutableClock()
        DevelopmentReviewStore(path, clock).use { store ->
            val task = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            clock.now = clock.now.plusSeconds(86400)
            java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { it.execute("CREATE TRIGGER controlled_delete_failure BEFORE DELETE ON previews BEGIN SELECT RAISE(ABORT,'private-sql-marker'); END") }
            }
            assertTrue(store.cleanupDue().failed)
            assertEquals(DevelopmentReviewRetentionState.DELETION_FAILED, store.task(task.id).retention)
            assertFalse(store.connectedReviewInspection(task.id).packetAvailable)
            assertEquals(1, store.queue().size)
            java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { it.execute("DROP TRIGGER controlled_delete_failure") }
            }
            assertEquals(listOf(task.id), store.cleanupDue().deletedIds)
        }
    }

    @Test fun `all terminal outcomes and expired queue lose associated history without extending their ttl`() {
        val path = Files.createTempDirectory("all-outcome-retention").resolve("isolated.sqlite")
        val clock = MutableClock()
        var revision = 1L
        DevelopmentReviewStore(path, clock, DevelopmentReviewFixtureSource { id ->
            DevelopmentReviewFixtures.load()[id]?.let { frozen ->
                val packet = kotlinx.serialization.json.Json.decodeFromString<InterpretationPacketV1>(frozen.packetJson)
                frozen.copy(inputRevision = revision, packetJson = kotlinx.serialization.json.Json.encodeToString(
                    packet.copy(evidence = packet.evidence.copy(evaluatedOnUtc = "2026-01-0${revision}T00:00:00Z"))))
            }
        }).use { store ->
            store.openOwnership(boundary.nodeName).use { owner ->
                fun submit() = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                val success = submit()
                val claim = assertNotNull(owner.claim(success.id, boundary))
                val handle = DevelopmentRuntimeHandle(claim, 8, 17)
                store.registerRuntime(handle)
                store.recordAttempt(claim, ConnectedReviewAttemptEvent(1, ConnectedReviewAttemptPhase.ACCEPTED, null))
                owner.publish(claim, ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(
                    InterpretationDraftV1(InterpretationContractV1.PROFILE, emptyList(), emptyList()))))
                store.beginOwnedStop(handle)
                val receipt = DevelopmentRuntimeStopReceipt.decode(handle, """{"identity":${identity(claim)},"keeper_pid":8,"keeper_start_ticks":17,"confirmed":true,"mechanism":"linux_subreaper_waitpid_echild","reaped":1,"escalated":false,"elapsed_millis":1}""".toByteArray())
                assertTrue(store.confirmOwnedStop(assertNotNull(receipt)))
                val failed = submit(); store.refuseQueued(failed.id, "provider_not_configured")
                val cancelled = submit(); store.cancel(cancelled.id)
                val superseded = submit()
                revision = 2
                val expiredQueue = submit()
                assertEquals(DevelopmentReviewOutcome.SUPERSEDED, store.task(superseded.id).outcome)
                val expiries = store.queue().associate { it.id to it.expiresUtc }
                assertEquals(5, expiries.size)
                clock.now = clock.now.plusSeconds(86400)
                assertEquals(DevelopmentReviewOutcome.EXPIRED, store.task(expiredQueue.id).outcome)
                assertTrue(store.queue().all { !store.connectedReviewInspection(it.id).packetAvailable })
                assertEquals(5, store.cleanupDue().deletedIds.size)
                assertTrue(store.pendingExecutions().isEmpty())
                java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                    for (table in listOf("tasks", "previews", "events", "development_attempts", "development_runtime_bindings", "development_execution_safety")) {
                        connection.createStatement().use { statement -> statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                            rows.next(); assertEquals(0, rows.getInt(1), table)
                        } }
                    }
                }
            }
        }
    }

    @Test fun `publication racing expiry deletion cannot resurrect payload or release minimal safety fence`() {
        val path = Files.createTempDirectory("publication-deletion").resolve("isolated.sqlite")
        val clock = MutableClock()
        DevelopmentReviewStore(path, clock).use { store ->
            DevelopmentReviewStore(path, clock).use { cleanup ->
                store.openOwnership(boundary.nodeName).use { owner ->
                    val task = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                    val claim = assertNotNull(owner.claim(task.id, boundary))
                    assertTrue(store.registerRuntime(DevelopmentRuntimeHandle(claim, 8, 17)))
                    val output = ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(
                        InterpretationDraftV1(InterpretationContractV1.PROFILE, emptyList(), emptyList())))
                    clock.now = clock.now.plusSeconds(86400)
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                    val start = java.util.concurrent.CountDownLatch(1)
                    try {
                        val publication = pool.submit(java.util.concurrent.Callable { start.await(); runCatching { owner.publish(claim, output) } })
                        val deletion = pool.submit(java.util.concurrent.Callable { start.await(); cleanup.cleanupDue() })
                        start.countDown()
                        assertNotEquals("PUBLISHED", publication.get().getOrNull()?.state)
                        assertEquals(listOf(task.id), deletion.get().deletedIds)
                        assertFailsWith<ConnectedReviewNotFound> { store.task(task.id) }
                        assertEquals(1, owner.pending().size)
                    } finally { pool.shutdownNow() }
                }
            }
        }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
}
