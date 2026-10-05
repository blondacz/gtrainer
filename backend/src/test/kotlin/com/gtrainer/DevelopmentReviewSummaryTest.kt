package com.gtrainer

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class DevelopmentReviewSummaryTest {
    private val config = DevelopmentReviewConfiguration("127.0.0.1", Path.of("/development/isolated.sqlite"),
        "ollama", "model", "Synthetic", "model:synthetic", "a".repeat(64), "0.35.0", 2048, 1536, 3, 17, 0.1,
        DevelopmentReviewPolicyBinding(900_000, 1_840_000))
    private val boundary = DevelopmentNodeBoundary("synthetic-node", "00000000-0000-0000-0000-000000000001", "a".repeat(64))

    @Test fun `summaries bound trigger reasons ids count and retention without exposing markers`() {
        val clock = MutableClock()
        val path = Files.createTempDirectory("safe-summary").resolve("isolated.sqlite")
        DevelopmentReviewStore(path, clock).use { store ->
            val run = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            val event = store.pendingEvents().single()
            repeat(20) { store.recordSummary(event.copy(kind = "private-trigger-marker"), listOf(DevelopmentWorkerReason.EXECUTION_REFUSED),
                listOf(run.id, "private-id-marker"), 9000, false, true) }
            val summaries = store.workerSummaries()
            assertEquals(4, summaries.size)
            assertTrue(summaries.all { it.trigger == "queue_event" && it.deleted == 8 && it.affectedIds == listOf(run.id) })
            assertFalse(Json.encodeToString(summaries).contains("private-"))
            (summaries.first().affectedIds as MutableList).clear()
            assertEquals(listOf(run.id), store.workerSummaries().first().affectedIds)
            clock.now = clock.now.plusSeconds(86400)
            assertTrue(store.workerSummaries().isEmpty())
            assertEquals(listOf(run.id), store.cleanupDue().deletedIds)
            store.recordSummary(event, listOf(DevelopmentWorkerReason.PAYLOAD_DELETED), listOf(run.id), 0, false, false)
            assertTrue(store.workerSummaries().isEmpty()) // No late callback resurrects an associated summary.
        }
    }

    @Test fun `queued supersession and preserved predecessor have distinct durable content-free causes`() {
        var revision = 1L
        DevelopmentReviewStore(Files.createTempDirectory("causal-summary").resolve("isolated.sqlite"), fixtures = DevelopmentReviewFixtureSource { id ->
            DevelopmentReviewFixtures.load()[id]?.let { fixture ->
                val packet = Json.decodeFromString<InterpretationPacketV1>(fixture.packetJson)
                fixture.copy(inputRevision = revision, packetJson = Json.encodeToString(packet.copy(
                    evidence = packet.evidence.copy(evaluatedOnUtc = "2026-01-0${revision}T00:00:00Z"))))
            }
        }).use { store ->
            val first = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            store.openOwnership(boundary.nodeName).use { owner ->
                assertNotNull(owner.claim(first.id, boundary))
                val queued = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                revision = 2
                store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
                val events = store.pendingEvents()
                assertTrue(events.any { it.kind == "running_predecessor_preserved" && it.taskId == first.id })
                assertTrue(events.any { it.kind == "queued_input_superseded" && it.taskId == queued.id })
                assertEquals(DevelopmentReviewOutcome.RUNNING, store.task(first.id).outcome)
                assertEquals(DevelopmentReviewOutcome.SUPERSEDED, store.task(queued.id).outcome)
            }
        }
    }

    @Test fun `expired queued cleanup is explained by aggregate maintenance summary without inference`() = runBlocking {
        val clock = MutableClock()
        DevelopmentReviewStore(Files.createTempDirectory("expiry-summary").resolve("isolated.sqlite"), clock).use { store ->
            val task = store.submit(store.createPreview("matched-run-recovery-facts-only", config).id)
            clock.now = clock.now.plusSeconds(86400)
            store.submitMaintenance()
            val worker = DevelopmentReviewWorker(store, boundary, DevelopmentRunExecution { _, _, _, _ -> fail("expired inference") })
            val job = worker.start(this)
            try {
                withTimeout(3_000) { while (store.workerSummaries().none { DevelopmentWorkerReason.EXPIRED_BEFORE_CLAIM in it.reasons }) delay(10) }
                assertTrue(store.workerSummaries().all { !it.executed })
                assertTrue(store.workerSummaries().any { task.id in it.affectedIds && it.deleted == 1 })
            } finally { job.cancelAndJoin() }
        }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }
}
