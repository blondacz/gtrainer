package com.gtrainer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class DevelopmentReviewQueueTest {
    private val caseId = "matched-run-recovery-facts-only"
    private val config = DevelopmentReviewConfiguration("127.0.0.1", java.nio.file.Path.of("/development/isolated.sqlite"),
        "ollama", "model-a", "Synthetic model", "model-a:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))

    @Test fun `concurrent duplicate submissions persist only one run and event`() {
        val path = Files.createTempDirectory("concurrent-submit").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { first ->
            DevelopmentReviewStore(path).use { second ->
                val preview = first.createPreview(caseId, config)
                assertNull(first.claimConnectedReview(preview.id))
                val pool = Executors.newFixedThreadPool(2)
                val start = CountDownLatch(1)
                try {
                    val results = listOf(first, second).map { store -> pool.submit(Callable { start.await(); store.submit(preview.id) }) }
                    start.countDown()
                    assertEquals(results[0].get(), results[1].get())
                    assertEquals(1, first.queue().size)
                    assertEquals(preview.id, first.queue().single().previewId)
                } finally { pool.shutdownNow() }
            }
        }
        DevelopmentReviewStore(path).use { reopened ->
            assertEquals(DevelopmentReviewOutcome.QUEUED, reopened.queue().single().outcome)
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM events").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
            }
        }
    }

    @Test fun `newer input replaces only matching queued tasks and preserves running predecessor`() {
        val path = Files.createTempDirectory("queued-supersession").resolve("isolated.sqlite")
        var revision = 1L
        val fixtures = DevelopmentReviewFixtureSource { id -> revised(id, revision) }
        DevelopmentReviewStore(path, fixtures = fixtures).use { store ->
            val running = store.submit(store.createPreview(caseId, config).id)
            assertNotNull(store.claimConnectedReview(running.id))
            val queued = store.submit(store.createPreview(caseId, config).id)
            val independentModel = store.submit(store.createPreview(caseId, config.copy(modelId = "model-b")).id)
            val independentCase = store.submit(store.createPreview("matched-run-recovery-context-present", config).id)
            revision = 2
            val successor = store.submit(store.createPreview(caseId, config).id)
            assertEquals(DevelopmentReviewOutcome.RUNNING, store.task(running.id).outcome)
            assertEquals(DevelopmentReviewOutcome.SUPERSEDED, store.task(queued.id).outcome)
            assertEquals(successor.id, store.task(queued.id).replacementId)
            assertEquals("queued_input_superseded", store.task(queued.id).reason)
            assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(independentModel.id).outcome)
            assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(independentCase.id).outcome)
            assertFalse(store.task(running.id).latestInput)
            assertTrue(store.task(successor.id).latestInput)
            assertNull(store.claimConnectedReview(successor.id))
            val snapshot = store.connectedReviewStatus(running.id)
            val published = store.publishConnectedReview(running.id,
                ValidatedConnectedReviewOutput(InterpretationContractV1.encodeDraft(
                    InterpretationDraftV1(InterpretationContractV1.PROFILE, emptyList(), emptyList()))),
                snapshot.evidenceDigest, snapshot.context, snapshot.provider, snapshot.model, snapshot.contractVersion)
            assertEquals("PUBLISHED", published.state)
            assertFalse(store.task(running.id).latestInput)
            // Publication is not process-stop evidence, even for a successful run.
            assertEquals(DevelopmentReviewExecutionState.STARTING, store.task(running.id).execution)
            assertNull(store.claimConnectedReview(successor.id))
        }
    }

    @Test fun `older equal and identical input cannot evict genuinely newer work`() {
        val path = Files.createTempDirectory("stale-input").resolve("isolated.sqlite")
        var revision = 3L
        var inputRevision = 3L
        DevelopmentReviewStore(path, fixtures = DevelopmentReviewFixtureSource { id -> revised(id, inputRevision)?.copy(inputRevision = revision) }).use { store ->
            val newest = store.submit(store.createPreview(caseId, config).id)
            revision = 2; inputRevision = 2
            val older = store.submit(store.createPreview(caseId, config).id)
            revision = 3; inputRevision = 3
            val equal = store.submit(store.createPreview(caseId, config).id)
            revision = 4 // Trusted catalogue version increased but input itself is unchanged.
            val identical = store.submit(store.createPreview(caseId, config).id)
            assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(newest.id).outcome)
            assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(equal.id).outcome)
            assertEquals(DevelopmentReviewOutcome.QUEUED, store.task(identical.id).outcome)
            assertEquals(DevelopmentReviewOutcome.SUPERSEDED, store.task(older.id).outcome)
            assertEquals(equal.id, store.task(older.id).replacementId)
            assertNull(store.task(newest.id).replacementId)
        }
    }

    @Test fun `run creation is submission time and duplicate never resets ttl or budgets`() {
        val path = Files.createTempDirectory("submission-ttl").resolve("isolated.sqlite")
        val clock = MutableClock()
        DevelopmentReviewStore(path, clock).use { store ->
            val preview = store.createPreview(caseId, config)
            clock.now = clock.now.plusSeconds(120)
            val run = store.submit(preview.id)
            assertEquals(clock.now.toString(), run.createdUtc)
            assertEquals(clock.now.plusSeconds(86400).toString(), run.expiresUtc)
            clock.now = clock.now.plusSeconds(3600)
            assertEquals(run, store.submit(preview.id))
            assertEquals(config.executionPolicy, store.task(run.id).policy)
            clock.now = Instant.parse(run.createdUtc).minusSeconds(120).plusSeconds(86400)
            assertFailsWith<DevelopmentReviewPreviewExpired> { store.submit(preview.id) }
            assertTrue(store.connectedReviewInspection(run.id).packetAvailable)
            clock.now = Instant.parse(run.expiresUtc)
            assertNull(store.claimConnectedReview(run.id))
            assertEquals(DevelopmentReviewRetentionState.DELETION_DUE, store.task(run.id).retention)
        }
    }

    @Test fun `capacity rejects excess preview but permits already retained submissions while busy`() {
        val path = Files.createTempDirectory("queue-capacity").resolve("isolated.sqlite")
        DevelopmentReviewStore(path).use { store ->
            val previews = List(32) { store.createPreview(caseId, config) }
            val first = store.submit(previews.first().id)
            assertNotNull(store.claimConnectedReview(first.id))
            val queued = previews.drop(1).map { store.submit(it.id) }
            assertTrue(queued.all { it.outcome == DevelopmentReviewOutcome.QUEUED })
            assertEquals(32, store.queue().size)
            assertEquals(store.queue().map { it.queueOrder }.sorted(), store.queue().map { it.queueOrder })
            assertFailsWith<DevelopmentReviewCapacityReached> { store.createPreview(caseId, config) }
            assertEquals(queued.last(), store.submit(previews.last().id))
        }
    }

    private fun revised(id: String, revision: Long): DevelopmentReviewFixture? {
        val frozen = DevelopmentReviewFixtures.load()[id] ?: return null
        val packet = Json.decodeFromString<InterpretationPacketV1>(frozen.packetJson)
        val synthetic = packet.copy(evidence = packet.evidence.copy(evaluatedOnUtc = "2026-01-0${revision}T00:00:00Z"))
        return frozen.copy(packetJson = Json.encodeToString(synthetic), inputRevision = revision)
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
}
