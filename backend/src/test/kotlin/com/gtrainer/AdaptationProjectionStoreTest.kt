package com.gtrainer

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptationProjectionStoreTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")

    private fun fixture(block:(HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("adaptation-projection-")
        try { HistoryStore(dir.resolve("history.sqlite3")).use(block) }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    private fun planRequest()=AuthoredPlanRequest("Synthetic plan",PlanAuthor("user","synthetic"),listOf(
        PlannedSession("33333333-3333-4333-8333-333333333333",PlanAuthor("user","synthetic"),"run","easy",
            PlanSchedule("date_only",date="2026-10-15",timeZone="UTC"))),PlanPolicy("UTC",7,0))

    @Test fun `capture prepare and finish are inert and missing plan has no family horizon`()=fixture { store ->
        val before=store.captureAdaptationEvidence(at)
        repeat(2) {
            val captured=store.captureAdaptationEvidence(at)
            val prepared=AdaptationProjection.prepare(captured)
            assertNull(prepared.family);assertNull(prepared.horizon)
            assertTrue(prepared.missingIntegration.isNotEmpty())
            assertEquals(prepared,store.finishAdaptationProjection(prepared))
        }
        assertEquals(before.totalChanges,store.captureAdaptationEvidence(at).totalChanges)
        assertEquals(before.sourceRevision,store.captureAdaptationEvidence(at).sourceRevision)
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
    }

    @Test fun `selected plan projection freezes capture and later owner mutation is refused`()=fixture { store ->
        val authored=store.createPlan(planRequest(),at)
        store.selectPlan(PlanSelectionRequest(0,authored.reference))
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity().copy(
            sourceRecordId="adaptation-record",sport="run",startLocal="2026-10-15T10:00:00",
            startInstant="2026-10-15T10:00:00Z"))),at)
        val captured=store.captureAdaptationEvidence(at)
        val prepared=AdaptationProjection.prepare(captured)
        assertEquals(authored.reference,prepared.family?.plan)
        assertTrue(prepared.horizon!=null)
        store.correctPlan(authored.reference.planId,PlanCorrection(1,1,authored.definition.copy(title="Corrected")),at)
        assertFailsWith<PlanConflict> { store.finishAdaptationProjection(prepared) }
        assertEquals(authored.reference,prepared.captured.reconciliation.plan?.reference)
        val fresh=AdaptationProjection.prepare(store.captureAdaptationEvidence(at))
        assertEquals("Corrected",fresh.captured.reconciliation.plan?.definition?.title)
    }

    @Test fun `authored plan without explicit horizon policy does not grant an adaptation family`()=fixture { store ->
        val authored=store.createPlan(planRequest().copy(policy=null),at)
        store.selectPlan(PlanSelectionRequest(0,authored.reference))
        val prepared=AdaptationProjection.prepare(store.captureAdaptationEvidence(at))
        assertEquals(authored.reference,prepared.reconciliation.selection.selected)
        assertNull(prepared.family);assertNull(prepared.horizon)
        assertTrue(prepared.missingIntegration.isNotEmpty())
    }

}
