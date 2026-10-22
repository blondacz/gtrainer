package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdaptationProjectionFenceTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")

    private fun fixture(block:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("adaptation-projection-fence-")
        val path=dir.resolve("history.sqlite3")
        try { HistoryStore(path).use { block(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }

    private fun planRequest()=AuthoredPlanRequest("Synthetic plan",PlanAuthor("user","synthetic"),listOf(
        PlannedSession("33333333-3333-4333-8333-333333333333",PlanAuthor("user","synthetic"),"run","easy",
            PlanSchedule("date_only",date="2026-10-15",timeZone="UTC"))),PlanPolicy("UTC",7,0))

    private fun seedPlan(store:HistoryStore) {
        val plan=store.createPlan(planRequest(),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        store.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity().copy(
            sourceRecordId="adaptation-record",sport="run",startLocal="2026-10-15T10:00:00",
            startInstant="2026-10-15T10:00:00Z"))),at)
    }

    private fun seedRules(store:HistoryStore) {
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(WellnessRecord("synthetic","sample","2026-10-15",setOf("synthetic"),
            mapOf("hrv" to Measurement(10.0,"ms","synthetic"))))),at)
        val signalId=UUID.randomUUID().toString()
        store.saveSignalRule(PersonalSignalRuleRequest(UUID.randomUUID().toString(),signalId,0,true,
            PersonalSignalConfiguration("UTC",PersonalSignalSelector("hrv","ms","synthetic","synthetic","measurement","daily"),
                PersonalSignalBaseline(3,1,0.0,3),"low","absolute_limit",20.0,30.0,1,1,0)),at)
        val workloadId=UUID.randomUUID().toString()
        store.saveWorkloadRule(ExtraWorkloadRuleRequest(UUID.randomUUID().toString(),workloadId,0,true,
            ExtraWorkloadConfiguration("per_record","duration","seconds","moving","run","synthetic",null,"UTC",10,"greater_than",100.0)),at)
    }

    @Test fun `capture prepare finish shares source fence and is inert with enabled rules`()=fixture { _,store ->
        seedPlan(store);seedRules(store)
        val before=store.captureAdaptationEvidence(at)
        val prepared=AdaptationProjection.prepare(before)
        assertEquals(1,prepared.signals.size)
        assertEquals("unevaluable",prepared.signals.single().status)
        assertEquals("unverified",prepared.signals.single().support.last().status)
        assertEquals(1,prepared.workload.size)
        assertTrue(before.signals.all { it.sourceRevision==before.sourceRevision && it.totalChanges==before.totalChanges &&
            it.dataVersion==before.dataVersion && it.ownerId==before.ownerId })
        assertEquals(1,before.signals.single().rawRecordCount)
        assertEquals(before,prepared.captured)
        assertEquals(prepared,store.finishAdaptationProjection(prepared))
        val after=store.captureAdaptationEvidence(at)
        assertEquals(before.totalChanges,after.totalChanges)
        assertEquals(before.sourceRevision,after.sourceRevision)
        assertEquals(before.dataVersion,after.dataVersion)
        assertEquals(before.ownerId,after.ownerId)
        assertEquals(before.reasonHeads,after.reasonHeads)
        assertEquals(before.admissions,after.admissions)
        assertEquals(before.signals.map { it.stateGeneration },after.signals.map { it.stateGeneration })
        assertTrue(store.reviewScheduleState().queue.pending.isEmpty())
    }

    @Test fun `raw read work cannot disappear through signal field filtering`()=fixture { _,store ->
        seedPlan(store);seedRules(store)
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(WellnessRecord("synthetic","no-hrv","2026-10-15",setOf("synthetic"),
            mapOf("steps" to Measurement(100.0,"count","synthetic"))))),at)
        val capture=store.captureAdaptationEvidence(at)
        val signal=capture.signals.single()
        assertEquals(1,signal.samples.size);assertEquals(2,signal.rawRecordCount)
        // Model the already bounded read work, without materializing private records or
        // spending 50k inserts just to exercise the shared projection cap.
        val full=capture.copy(signals=listOf(signal.copy(rawRecordCount=50_000)))
        assertFailsWith<IllegalArgumentException> { AdaptationProjection.prepare(full) }
        assertFailsWith<IllegalArgumentException> { AdaptationProjection.prepare(capture.copy(totalChanges=capture.totalChanges+1)) }
        assertFailsWith<IllegalArgumentException> { AdaptationProjection.prepare(capture.copy(signals=listOf(signal.copy(ownerId="foreign")))) }
    }

    @Test fun `foreign store open before capture uses data version fence`()=fixture { path,store ->
        HistoryStore(path).use { foreign ->
            seedPlan(store)
            val captured=store.captureAdaptationEvidence(at)
            val prepared=AdaptationProjection.prepare(captured)
            foreign.completeActivities(ReadResult(ReadStatus.SUCCESS,listOf(syntheticActivity().copy(
                sourceRecordId="foreign-adaptation-record",sport="run",startLocal="2026-10-15T11:00:00",
                startInstant="2026-10-15T11:00:00Z"))),at)
            val fresh=store.captureAdaptationEvidence(at)
            assertEquals(captured.totalChanges,fresh.totalChanges)
            assertNotEquals(captured.dataVersion,fresh.dataVersion)
            assertFailsWith<PlanConflict> { store.finishAdaptationProjection(prepared) }
        }
    }

    @Test fun `restart owner refuses old projection capture`()=fixture { path,store ->
        val captured=store.captureAdaptationEvidence(at)
        val prepared=AdaptationProjection.prepare(captured)
        HistoryStore(path).use { restarted ->
            assertNotEquals(captured.ownerId,restarted.captureAdaptationEvidence(at).ownerId)
            assertFailsWith<PlanConflict> { restarted.finishAdaptationProjection(prepared) }
        }
    }

    @Test fun `import removal stales captured projection`()=fixture { _,store ->
        store.completeWellness(ReadResult(ReadStatus.SUCCESS,listOf(WellnessRecord("synthetic","remove-me","2026-10-15",setOf("synthetic"),
            mapOf("hrv" to Measurement(10.0,"ms","synthetic"))))),at)
        val prepared=AdaptationProjection.prepare(store.captureAdaptationEvidence(at))
        store.removeImports()
        assertFailsWith<PlanConflict> { store.finishAdaptationProjection(prepared) }
    }

    @Test fun `one snapshot preserves source association and source correction does not silently rewrite confirmed context`()=fixture { _,store ->
        seedPlan(store)
        val original=store.createContext(AthleteContextRequest("note","user_report","synthetic author","2026-10-15",content="confirmed synthetic context"))
        val note=store.createIntake(ContextIntakeRequest("synthetic source","user_report","synthetic author","2026-10-15"),at)
        val link=store.associateIntake(note.intakeId,ContextIntakeAssociation(1,original.contextId,1))
        val before=store.captureAdaptationEvidence(at)
        assertEquals(listOf(link),before.contextLinks)
        assertEquals(mapOf(note.intakeId to 1),before.sourceHeads)
        assertEquals(original,before.contexts.single().context)
        assertEquals(false,before.contexts.single().sourceChanged)
        val prepared=AdaptationProjection.prepare(before)
        store.correctIntake(note.intakeId,ContextIntakeCorrection(1,ContextIntakeRequest("corrected source","user_report","synthetic author","2026-10-15")),at)
        assertFailsWith<PlanConflict> { store.finishAdaptationProjection(prepared) }
        val after=store.captureAdaptationEvidence(at)
        assertEquals(mapOf(note.intakeId to 2),after.sourceHeads)
        assertTrue(after.contextLinks.single().sourceChanged)
        assertTrue(after.contexts.single().sourceChanged)
        assertEquals(original,after.contexts.single().context)
    }
}
