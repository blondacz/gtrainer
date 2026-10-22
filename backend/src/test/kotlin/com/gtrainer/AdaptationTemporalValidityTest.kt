package com.gtrainer

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdaptationTemporalValidityTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private val id="11111111-1111-4111-8111-111111111111"
    private fun fixture(block:(HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("adaptation-validity-")
        try { HistoryStore(dir.resolve("history.sqlite3")).use(block) }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun signal(zone:String)=PersonalSignalRule(id,1,true,PersonalSignalConfiguration(zone,
        PersonalSignalSelector("hrv","ms","synthetic","synthetic","rmssd","overnight"),
        PersonalSignalBaseline(1,1,1.0,1),"low","absolute_limit",10.0,15.0,1,1,0),at.toString())

    @Test fun `DST local midnights preserve both 23 and 25 hour civil days`()=fixture { store ->
        for((start,end,hours) in listOf(
            Triple("2026-03-08T05:00:00Z","2026-03-09T04:00:00Z",23L),
            Triple("2026-11-01T04:00:00Z","2026-11-02T05:00:00Z",25L))) {
            val capture=store.captureAdaptationEvidence(Instant.parse(start))
            val until=AdaptationTemporalValidity.until(capture,"America/New_York")
            assertEquals(Instant.parse(end),until)
            assertEquals(hours,Duration.between(capture.at,until).toHours())
        }
    }

    @Test fun `supplied and selected policy zones are both respected without accepting offset guesses`()=fixture { store ->
        val capture=store.captureAdaptationEvidence(at)
        assertEquals(Instant.parse("2026-10-15T15:00:00Z"),AdaptationTemporalValidity.until(capture,"Asia/Tokyo"))
        assertEquals(Instant.parse("2026-10-16T00:00:00Z"),AdaptationTemporalValidity.until(capture,"UTC"))
        assertFailsWith<IllegalArgumentException> { AdaptationTemporalValidity.until(capture,"+01:00") }
        val plan=store.createPlan(AuthoredPlanRequest("Synthetic",PlanAuthor("user","synthetic"),emptyList(),PlanPolicy("Europe/Prague",7,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        val selected=store.captureAdaptationEvidence(at)
        assertEquals(Instant.parse("2026-10-15T22:00:00Z"),AdaptationTemporalValidity.until(selected,"UTC"))
    }

    @Test fun `every enabled rule zone fences midnight while disabled configuration has no authority`()=fixture { store ->
        val capture=store.captureAdaptationEvidence(at)
        val rule=signal("Asia/Tokyo")
        val child=CapturedPersonalSignal(rule,PersonalSignalQuery("2026-10-15","2026-10-15"),at,emptyList(),emptyList(),
            capture.sourceRevision,capture.totalChanges,0,null,capture.ownerId,capture.dataVersion)
        val workload=ExtraWorkloadRule("22222222-2222-4222-8222-222222222222",1,true,
            ExtraWorkloadConfiguration("per_record","duration","seconds","moving","run","synthetic",null,"Asia/Kolkata",1,"greater_than",100.0),at.toString())
        val both=capture.copy(signals=listOf(child),workloadRules=listOf(workload))
        assertEquals(Instant.parse("2026-10-15T15:00:00Z"),AdaptationTemporalValidity.until(both,"UTC"))
        val onlyWorkload=both.copy(signals=listOf(child.copy(rule=rule.copy(enabled=false))))
        assertEquals(Instant.parse("2026-10-15T18:30:00Z"),AdaptationTemporalValidity.until(onlyWorkload,"UTC"))
        val disabled=onlyWorkload.copy(workloadRules=listOf(workload.copy(enabled=false)))
        assertEquals(Instant.parse("2026-10-16T00:00:00Z"),AdaptationTemporalValidity.until(disabled,"UTC"))
    }

    @Test fun `session start end and configured grace are exclusive within-day fences`()=fixture { store ->
        val session=PlannedSession(id,PlanAuthor("user","synthetic"),"run","easy",
            PlanSchedule("timed",startsLocal="2026-10-15T12:30",endsLocal="2026-10-15T13:00",timeZone="UTC"))
        val plan=store.createPlan(AuthoredPlanRequest("Synthetic",PlanAuthor("user","synthetic"),listOf(session),PlanPolicy("UTC",7,3600)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference))
        val capture=store.captureAdaptationEvidence(at)
        assertEquals(Instant.parse("2026-10-15T12:30:00Z"),AdaptationTemporalValidity.until(capture,"UTC"))
        assertEquals(Instant.parse("2026-10-15T13:00:00Z"),AdaptationTemporalValidity.until(capture.copy(at=Instant.parse("2026-10-15T12:30:00Z")),"UTC"))
        assertEquals(Instant.parse("2026-10-15T14:00:00Z"),AdaptationTemporalValidity.until(capture.copy(at=Instant.parse("2026-10-15T13:00:00Z")),"UTC"))
        val after=capture.copy(at=Instant.parse("2026-10-15T14:00:00Z"))
        assertEquals(Instant.parse("2026-10-16T00:00:00Z"),AdaptationTemporalValidity.until(after,"UTC"))
        assertEquals(Instant.parse("2026-10-15T12:30:00Z"),AdaptationProjection.prepare(capture).temporalValidUntil)
    }

    @Test fun `future verified recording can become historical without a write but unknown timing never invents an instant`()=fixture { store ->
        val capture=store.captureAdaptationEvidence(at)
        val unknown=ReconciliationActivity("a".repeat(64),1,"b".repeat(64),"run","2026-10-15T12:15:00","2026-10-15T12:15:00Z","unknown")
        val verified=unknown.copy(activityId="c".repeat(64),startLocal="2026-10-15T13:00:00",startInstant="2026-10-15T13:00:00Z",timeContext="upstream_instant")
        val withActivity=capture.copy(reconciliation=capture.reconciliation.copy(activities=listOf(unknown,verified)))
        assertEquals(Instant.parse("2026-10-15T13:00:00Z"),AdaptationTemporalValidity.until(withActivity,"UTC"))
        val onlyUnknown=withActivity.copy(reconciliation=withActivity.reconciliation.copy(activities=listOf(unknown)))
        val until=AdaptationTemporalValidity.until(onlyUnknown,"UTC")
        assertEquals(Instant.parse("2026-10-16T00:00:00Z"),until);assertTrue(until>capture.at)
    }
}
