package com.gtrainer

import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ScopedEvidencePreparationTest {
    private val author=PlanAuthor("user","Synthetic author")
    private fun id()=UUID.randomUUID().toString()
    private fun capture(request: ScopedEvidenceRequest,at: Instant,history: HistoryResponse=HistoryResponse(emptyList(),emptyList()),
        plan: AuthoredPlan?=null,commitments: List<ConfirmedCommitment> = emptyList()): CapturedScopedEvidence {
        val scope=ReviewScopeResolver.resolve(request.selection,at,"UTC",history.activities.associateBy(::scopedActivityId))
        return CapturedScopedEvidence(CapturedScopedRead(scope,history,emptyList(),emptyList()),emptyList(),emptyList(),
            commitments.associate { it.reference.intakeId to it.reference.intakeRevision },commitments,
            PlanSelection(1,plan?.reference),plan,0)
    }
    private fun packet(request: ScopedEvidenceRequest,captured: CapturedScopedEvidence)=ScopedEvidencePreparation.packet(request,captured,
        ScopedTrends.report(captured.read.scope,captured.read.history,captured.read.statuses))

    @Test fun `adaptation horizon projects commitments and timed sessions in its own zone`() {
        val at=Instant.parse("2020-06-10T02:00:00Z")
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="custom",oldest="2020-06-01",newest="2020-06-01",wellnessPolicy="off"),purpose="adaptation")
        val session=PlannedSession(id(),author,"run","Synthetic objective",PlanSchedule("timed",startsLocal="2020-06-09T20:00",
            endsLocal="2020-06-09T21:00",timeZone="America/Los_Angeles"))
        val plan=AuthoredPlan(PlanRef(id(),1),null,AuthoredPlanRequest("Synthetic plan",author,listOf(session),
            PlanPolicy("America/Los_Angeles",1,0)),"authenticated_user",at.toString())
        val commitment=ConfirmedCommitment(CommitmentEvidenceRef(id(),1,id(),1,null,null),
            CommitmentDefinition("Synthetic evening","commitment", "America/Los_Angeles","2020-06-09T20:00","2020-06-09T21:00"),
            null,"Synthetic author","user_report","authenticated_user",at.toString(),false,false,false)
        val result=packet(request,capture(request,at,plan=plan,commitments=listOf(commitment)))
        assertEquals(ScopedDateRange("2020-06-09","2020-06-09"),result.planningHorizon)
        assertEquals(session,result.plan.sessions.single())
        val future=result.commitments.single { it.timeZone=="America/Los_Angeles" }
        assertEquals(commitment.reference,future.occurrences.single().reference)
        assertEquals("2020-06-10T03:00:00Z",future.occurrences.single().startsUtc)
    }

    @Test fun `activity targets preserve correlated dates and sports for contextual plan projection`() {
        val at=Instant.parse("2020-06-10T12:00:00Z")
        val run=syntheticActivity().copy(sourceRecordId="run",sport="run",startLocal="2020-06-01T10:00:00",startInstant="2020-06-01T10:00:00Z")
        val ride=run.copy(sourceRecordId="ride",sport="ride",startLocal="2020-06-02T10:00:00",startInstant="2020-06-02T10:00:00Z")
        val sessions=listOf("run" to "2020-06-01","ride" to "2020-06-02","ride" to "2020-06-01","run" to "2020-06-02").map { (sport,date) ->
            PlannedSession(id(),author,sport,"Synthetic objective",PlanSchedule("date_only",date=date,timeZone="UTC"))
        }
        val plan=AuthoredPlan(PlanRef(id(),1),null,AuthoredPlanRequest("Synthetic plan",author,sessions),"authenticated_user",at.toString())
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(mode="activities",scope="activities",intent="compare",
            activityIds=listOf(scopedActivityId(run),scopedActivityId(ride))),purpose="activity_reflection")
        val result=packet(request,capture(request,at,HistoryResponse(listOf(run,ride),emptyList()),plan))
        assertEquals(sessions.take(2),result.plan.sessions)
    }

    @Test fun `optional annual detail is disclosed and mandatory overflow never truncates restrictions`() {
        val at=Instant.parse("2020-06-10T12:00:00Z")
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="custom",oldest="2020-06-01",newest="2020-06-01"),purpose="period_reflection")
        val observations=List(1000) { syntheticWellness().copy(sourceRecordId="wellness-$it",date="2020-06-01") }
        val result=packet(request,capture(request,at,HistoryResponse(emptyList(),observations)))
        val bounded=ScopedEvidenceBudget.bound(result)
        assertNotNull(bounded.packet)
        assertEquals(result.facts.wellness.size,bounded.omitted.wellnessObservationDetails)
        assertEquals(result.facts.aggregates,bounded.packet.facts.aggregates)
        assertTrue(bounded.packetBytes<=32768)
        val mandatory=List(10) { AthleteContext(id(),1,"restriction","user_report","Synthetic author","authenticated_user","2020-01-01",
            null,null,null,null,null,"M".repeat(4000),false,"blocked_activity","synthetic-$it",null) }
        val oversized=result.copy(facts=result.facts.copy(wellness=emptyList()),context=result.context.copy(
            evidence=result.context.evidence.copy(mandatoryRestrictions=mandatory)))
        val refused=ScopedEvidenceBudget.bound(oversized)
        assertNull(refused.packet)
        assertTrue("mandatory_or_explicit_subject_packet_overflow" in refused.blockedReasons)
        assertEquals(mandatory,oversized.context.evidence.mandatoryRestrictions)
    }

    @Test fun `synthetic leap year aggregates remain deterministic and never transfer full session arrays`() {
        val at=Instant.parse("2025-01-02T12:00:00Z")
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="calendar_year",anchorDate="2024-12-31",wellnessPolicy="off"),purpose="period_reflection")
        val activities=List(5000) { n ->
            val date=java.time.LocalDate.parse("2024-01-01").plusDays((n%366).toLong()).toString()
            syntheticActivity().copy(sourceRecordId="year-$n",startLocal="${date}T10:00:00",startInstant="${date}T10:00:00Z",
                movingTime=Measurement(600.0,"seconds"),elapsedTime=Measurement(660.0,"seconds"))
        }
        val a=capture(request,at,HistoryResponse(activities,emptyList()))
        val b=capture(request,at,HistoryResponse(activities.reversed(),emptyList()))
        val first=packet(request,a);val second=packet(request,b)
        assertEquals(first,second)
        assertEquals(ScopedEvidencePreparation.binding(first,a.read),ScopedEvidencePreparation.binding(second,b.read))
        assertEquals(366,first.facts.aggregates.first().periodDays)
        assertEquals(3_000_000.0,first.facts.aggregates.single { it.key=="movingTime" }.value)
        assertTrue(first.facts.sessions.isEmpty())
        assertTrue(ScopedEvidenceBudget.bound(first).packetBytes<=32768)
    }

    @Test fun `blocked baseline retains bounded day summaries and paged membership not admitted sample IDs`() {
        val at=Instant.parse("2020-06-10T12:00:00Z")
        val baseline=ScopedBaselineSelection("hrv",syntheticWellness().source,null,ScopedBaselineConfig("2020-06-02",1,1,1.0,1))
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="custom",oldest="2020-06-09",newest="2020-06-09"),
            purpose="period_reflection",baselines=listOf(baseline))
        val records=List(300) { syntheticWellness().copy(sourceRecordId="candidate-$it") }
        val captured=capture(request,at,HistoryResponse(emptyList(),records))
        val prepared=prepareScopedEvidence(request,captured,ScopedTrends.report(captured.read.scope,captured.read.history,captured.read.statuses))
        assertNull(prepared.bounded.packet)
        assertTrue(prepared.manifest.included.isEmpty())
        val day=prepared.manifest.baselines.single().days.single()
        assertEquals(300,day.candidateCount)
        assertTrue(day.samples.isEmpty());assertTrue(day.evidenceIds.isEmpty())
        assertEquals("authenticated_record_pages",day.candidateDetails)
        assertEquals(300,prepared.records.size)
        assertTrue(prepared.manifestJson.toByteArray().size<MAX_SCOPED_INSPECTION_BYTES)
        // The inspection ceiling is UTF-8 bytes, independent of the provider packet ceiling.
        val oversized=prepared.manifest.copy(missing=listOf("é".repeat(MAX_SCOPED_INSPECTION_BYTES/2)))
        assertFailsWith<ScopedEvidenceInspectionLimit> { encodeScopedInspectionManifest(oversized) }
    }
}
