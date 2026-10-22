package com.gtrainer

import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import java.util.UUID
import kotlin.test.*

class PlanAmendmentOwnerTest {
    private val at=Instant.parse("2026-10-15T12:00:00Z")
    private val clock=Clock.fixed(at,ZoneOffset.UTC)
    private fun fixture(block:(Path,HistoryStore)->Unit) {
        val dir=Files.createTempDirectory("plan-amendment-owner-");val path=dir.resolve("history.db")
        try { HistoryStore(path,clock).use { block(path,it) } }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
    private fun setup(store:HistoryStore,starts:String="2026-10-20T09:00",ends:String="2026-10-20T10:00"):AuthoredPlan {
        val author=PlanAuthor("user","synthetic")
        val plan=store.createPlan(AuthoredPlanRequest("synthetic",author,listOf(PlannedSession(UUID.randomUUID().toString(),author,"run","synthetic",
            PlanSchedule("timed",startsLocal=starts,endsLocal=ends,timeZone="UTC"),listOf(PlannedTarget("duration","seconds","moving",1800.0,2400.0)),
            permissions=AmendmentPermissions(PlanMoveWindow("2026-10-21T09:00","2026-10-22T09:00"),DurationReductionBounds("moving",1200,3000),true))),PlanPolicy("UTC",14,0)),at)
        store.selectPlan(PlanSelectionRequest(0,plan.reference));return plan
    }
    private fun context(text:String="synthetic",sport:String="ride")=AthleteContextRequest("restriction","user_report","synthetic","2026-10-01",sport=sport,content=text,restrictionKind="blocked_sport",restrictionValue=sport)
    private fun source(text:String="synthetic source")=ContextIntakeRequest(text,"user_report","synthetic","2026-10-01")
    private fun report(capture:CapturedPlanAmendment)=PlanAmendmentPolicy.enumerate(capture.input,capture.at)

    @Test fun repeatedInspectionAndReopenKeepExactCandidatesWithoutWritesOrAdmission()=fixture { path,store ->
        setup(store)
        val before=store.capturePlanAmendment(at);val plans=store.plans();val queue=store.reviewScheduleState()
        val calendar=PlanService(store,clock).calendar("2026-10-15","2026-10-28","UTC")
        val first=PlanService(store,clock).amendmentCandidates()
        assertEquals("available",first.status);assertEquals(5,first.candidates.size)
        repeat(3) { assertEquals(first,PlanService(store,clock).amendmentCandidates()) }
        assertEquals(plans,store.plans());assertEquals(queue,store.reviewScheduleState())
        assertEquals(calendar,PlanService(store,clock).calendar("2026-10-15","2026-10-28","UTC"))
        assertEquals(before.totalChanges,store.capturePlanAmendment(at).totalChanges)
        HistoryStore(path,clock).use { reopened -> assertEquals(first,PlanService(reopened,clock).amendmentCandidates()) }
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            for(table in listOf("scoped_evidence_snapshots","adaptation_results")) {
                db.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM $table").use { r -> r.next();assertEquals(0,r.getInt(1)) } }
            }
        }
    }

    @Test fun allOwnerMutationFamiliesFenceCapturedInspectionAndExactDependency() {
        for(change in listOf("plan","context","lifecycle","intake","commitment","selection")) fixture { _,store ->
            val plan=setup(store);val c=store.createContext(context());val intake=store.createIntake(source(),at)
            if(change!="context") store.associateIntake(intake.intakeId,ContextIntakeAssociation(1,c.contextId,1))
            val captured=store.capturePlanAmendment(at);val result=report(captured)
            assertTrue(result.candidates.isNotEmpty())
            when(change) {
                "plan" -> store.correctPlan(plan.reference.planId,PlanCorrection(1,1,plan.definition.copy(title="changed")),at)
                "context" -> store.correctContext(c.contextId,context("changed"))
                "lifecycle" -> store.updateContextLifecycle(c.contextId,ContextLifecycleUpdate(1,"resolved",null),LocalDate.parse("2026-10-15"))
                "intake" -> store.correctIntake(intake.intakeId,ContextIntakeCorrection(1,source("changed")),at)
                "commitment" -> store.confirmCommitment(CommitmentConfirmation(intake.intakeId,1,CommitmentDefinition("synthetic busy","commitment","UTC","2026-10-21T09:00","2026-10-21T10:00")),at)
                "selection" -> { store.selectPlan(PlanSelectionRequest(1,null));store.selectPlan(PlanSelectionRequest(2,plan.reference)) }
            }
            assertFailsWith<PlanConflict>(change) { store.finishPlanAmendment(captured,result,clock) }
            assertNotEquals(result.dependencySha256,report(store.capturePlanAmendment(at)).dependencySha256,change)
        }
    }

    @Test fun externalConnectionMutationAndDifferentOwnerCannotPublishOldCapture()=fixture { path,store ->
        setup(store);val capture=store.capturePlanAmendment(at);val result=report(capture)
        HistoryStore(path,clock).use { other ->
            assertFailsWith<PlanConflict> { other.finishPlanAmendment(capture,result,clock) }
            other.createContext(context())
        }
        assertEquals(capture.totalChanges,store.capturePlanAmendment(at).totalChanges)
        assertFailsWith<PlanConflict> { store.finishPlanAmendment(capture,result,clock) }
    }

    @Test fun monotonicClockExclusiveSessionDeadlineAndForgedDeadlineFailClosed()=fixture { _,store ->
        setup(store,"2026-10-15T13:00","2026-10-15T14:00")
        val capture=store.capturePlanAmendment(at);val result=report(capture);val deadline=Instant.parse(result.validUntilUtc)
        assertEquals(Instant.parse("2026-10-15T13:00:00Z"),deadline)
        assertEquals(result,store.finishPlanAmendment(capture,result,Clock.fixed(deadline.minusNanos(1),ZoneOffset.UTC)))
        assertFailsWith<PlanConflict> { store.finishPlanAmendment(capture,result,Clock.fixed(at.minusNanos(1),ZoneOffset.UTC)) }
        assertFailsWith<PlanConflict> { store.finishPlanAmendment(capture,result,Clock.fixed(deadline,ZoneOffset.UTC)) }
        assertFailsWith<PlanConflict> { store.finishPlanAmendment(capture,result.copy(validUntilUtc=deadline.plusSeconds(3600).toString()),clock) }
    }

    @Test fun midnightChangesHorizonAndInvalidatesInspectionWithoutMutation()=fixture { _,store ->
        setup(store);val capture=store.capturePlanAmendment(at);val result=report(capture)
        assertEquals("2026-10-16T00:00:00Z",result.validUntilUtc)
        val later=Instant.parse(result.validUntilUtc)
        assertFailsWith<PlanConflict> { store.finishPlanAmendment(capture,result,Clock.fixed(later,ZoneOffset.UTC)) }
        val next=PlanService(store,Clock.fixed(later,ZoneOffset.UTC)).amendmentCandidates()
        assertNotEquals(result.horizon,next.horizon)
        assertEquals(capture.totalChanges,store.capturePlanAmendment(later).totalChanges)
    }

    @Test fun realUncitedDestinationRestrictionCommitmentAndStaleIllnessAreNotBypassed()=fixture { _,store ->
        setup(store);val plans=store.plans();val intake=store.createIntake(source(),at)
        store.confirmCommitment(CommitmentConfirmation(intake.intakeId,1,CommitmentDefinition("busy","commitment","UTC","2026-10-21T09:30","2026-10-21T10:30")),at)
        store.createContext(context(sport="run").copy(applicableFrom="2026-10-22",applicableUntil="2026-10-22"))
        val constrained=PlanService(store,clock).amendmentCandidates()
        assertFalse(constrained.candidates.any { it.operation=="move_session" })
        assertTrue(constrained.issues.any { it.reason=="commitment_overlap" });assertTrue(constrained.issues.any { it.reason=="sport_prohibited" })
        store.createContext(AthleteContextRequest("symptom","user_report","synthetic","2026-10-01",applicableUntil="2026-10-14",content="synthetic unresolved illness"))
        val unclear=PlanService(store,clock).amendmentCandidates()
        assertEquals("needs_clarification",unclear.status);assertTrue(unclear.candidates.isEmpty())
        assertEquals(plans,store.plans())
    }
}
