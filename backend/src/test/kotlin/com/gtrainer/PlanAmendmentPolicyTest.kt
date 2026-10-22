package com.gtrainer

import java.time.*
import java.util.UUID
import kotlin.test.*

class PlanAmendmentPolicyTest {
    private val at=Instant.parse("2026-10-17T12:00:00Z")
    private val author=PlanAuthor("user","synthetic")
    private val sessionId="11111111-1111-4111-8111-111111111111"
    private val planId="22222222-2222-4222-8222-222222222222"
    private fun timed(start:String="2026-10-18T09:00",end:String="2026-10-18T10:00",zone:String="UTC",startOffset:String?=null,endOffset:String?=null)=
        PlanSchedule("timed",startsLocal=start,endsLocal=end,timeZone=zone,startOffset=startOffset,endOffset=endOffset)
    private fun session()=PlannedSession(sessionId,author,"run","synthetic session",timed(),
        listOf(PlannedTarget("duration","seconds","moving",1800.0,2400.0)),permissions=AmendmentPermissions(
            PlanMoveWindow("2026-10-19T09:00","2026-10-20T09:00"),DurationReductionBounds("moving",1200,3000),true))
    private fun input(sessions:List<PlannedSession> = listOf(session()),contexts:List<ContextLifecycleView> = emptyList(),commitments:List<ConfirmedCommitment> = emptyList(),
        policy:PlanPolicy?=PlanPolicy("UTC",7,0)):PlanAmendmentInputs {
        val plan=AuthoredPlan(PlanRef(planId,1),null,AuthoredPlanRequest("Synthetic",author,sessions,policy),"authenticated_user",at.toString())
        return PlanAmendmentInputs(PlanSelection(1,plan.reference),plan,contexts,commitments)
    }
    private fun context(kind:String="blocked_sport",value:String="run",from:String?=null,until:String?=null,
        category:String="restriction",sport:String?=null,activityId:String?=null,reviewId:String?=null,resolution:String="unresolved",due:String?=null,
        sourceChanged:Boolean=false,retired:Boolean=false,id:String=UUID.randomUUID().toString()):ContextLifecycleView {
        val c=AthleteContext(id,1,category,"user_report","synthetic","authenticated_user","2026-10-01",from,until,sport,activityId,reviewId,"synthetic context",retired,
            if(category=="restriction")kind else null,if(category=="restriction")value else null,if(kind=="maximum_duration")"minutes" else null)
        return projectContextLifecycle(c,ContextLifecycle(resolution,due),sourceChanged,LocalDate.parse("2026-10-17"))
    }
    private fun commitment(start:String="2026-10-19T09:30",end:String="2026-10-19T10:30",kind:String="commitment",zone:String="UTC",
        recurrence:CommitmentRecurrence?=null,exceptions:List<CommitmentException> = emptyList(),stale:Boolean=false):ConfirmedCommitment =
        ConfirmedCommitment(CommitmentEvidenceRef(UUID.randomUUID().toString(),1,UUID.randomUUID().toString(),1,null,null),
            CommitmentDefinition("synthetic",kind,zone,start,end,recurrence,exceptions),null,"synthetic","user_report","authenticated_user",at.toString(),stale,false,stale)
    private fun enumerate(i:PlanAmendmentInputs)=PlanAmendmentPolicy.enumerate(i,at)
    private fun check(i:PlanAmendmentInputs,after:PlannedSession,operation:String,before:PlannedSession=i.plan!!.definition.sessions.first())=
        PlanAmendmentPolicy.checkOperation(i,at,before,after,operation)

    @Test fun boundaryEnumerationIsDeterministicBoundedAndNeverAppliesAnything() {
        val i=input();val before=i.plan!!
        val a=enumerate(i);val b=enumerate(i)
        assertEquals(a,b);assertEquals(before,i.plan);assertEquals("available",a.status)
        assertEquals(5,a.candidates.size)
        assertEquals(2,a.candidates.count { it.operation=="move_session" })
        assertEquals(setOf(1200.0,1800.0),a.candidates.filter { it.operation=="reduce_duration" }.map { it.after.targets.single().minimum }.toSet())
        assertTrue(a.candidates.filter { it.operation=="reduce_duration" }.all { it.after.targets.single().minimum==it.after.targets.single().maximum && it.before.schedule==it.after.schedule })
        assertTrue(a.candidates.all { it.candidateId.matches(Regex("[a-f0-9]{64}")) })
        assertTrue(a.coverage.contains("not_exhaustive"));assertTrue(a.admission.contains("no_inference"))
        assertNotEquals(a.dependencySha256,enumerate(i.copy(selection=i.selection.copy(generation=3))).dependencySha256)
        val reversed=input(sessions=listOf(session().copy(sessionId="33333333-3333-4333-8333-333333333333",schedule=timed("2026-10-22T09:00","2026-10-22T10:00")),session()))
        assertEquals(enumerate(reversed).candidates,enumerate(reversed.copy(plan=reversed.plan!!.copy(definition=reversed.plan.definition.copy(sessions=reversed.plan.definition.sessions.reversed())))).candidates)
    }

    @Test fun independentCheckAcceptsInteriorAllowedMoveButRejectsInventedOrUnrelatedOperations() {
        val i=input();val before=session();val interior=before.copy(schedule=timed("2026-10-19T12:00","2026-10-19T13:00"))
        assertTrue(check(i,interior,"move_session").isEmpty())
        assertFalse(enumerate(i).candidates.any { it.after==interior })
        assertTrue(check(i,interior.copy(sport="ride"),"move_session").isNotEmpty())
        assertTrue(check(i,interior.copy(objective="new workout"),"move_session").isNotEmpty())
        assertTrue(check(i,interior.copy(sessionId=UUID.randomUUID().toString()),"move_session").isNotEmpty())
        assertTrue(check(i,before.copy(omitted=true),"add_workout").isNotEmpty())
        assertTrue(check(i,before,"move_session").isNotEmpty())
    }

    @Test fun reductionsCannotRaiseEitherEndpointOrChangeBasisOtherTargetsOrOccupancy() {
        val s=session();val i=input();val target=s.targets.single()
        fun changed(minimum:Double,maximum:Double)=s.copy(targets=listOf(target.copy(minimum=minimum,maximum=maximum)))
        assertTrue(check(i,changed(1500.0,2000.0),"reduce_duration").isEmpty())
        assertTrue(check(i,changed(1900.0,2000.0),"reduce_duration").isNotEmpty())
        assertTrue(check(i,changed(1500.0,2500.0),"reduce_duration").isNotEmpty())
        assertTrue(check(i,changed(1500.0,2000.0).copy(schedule=timed(end="2026-10-18T09:30")),"reduce_duration").isNotEmpty())
        assertTrue(check(i,s.copy(targets=s.targets+PlannedTarget("distance","metres","session_total",100.0,200.0)),"reduce_duration").isNotEmpty())
        val noPermission=s.copy(permissions=AmendmentPermissions())
        assertTrue(check(input(listOf(noPermission)),noPermission.copy(targets=listOf(target.copy(minimum=1500.0,maximum=2000.0))),"reduce_duration").isNotEmpty())
    }

    @Test fun absentPolicyMissingBasePermissionsPastAndWholeHorizonReturnNoExecutableSet() {
        assertEquals("no_candidates",enumerate(PlanAmendmentInputs(PlanSelection(0,null),null,emptyList(),emptyList())).status)
        assertEquals("needs_clarification",enumerate(input(policy=null)).status)
        assertTrue(enumerate(input(listOf(session().copy(permissions=AmendmentPermissions())))).candidates.isEmpty())
        assertTrue(enumerate(input(listOf(session().copy(schedule=timed("2026-10-17T09:00","2026-10-17T10:00"))))).candidates.isEmpty())
        val i=input();assertTrue(check(i,session().copy(schedule=timed("2026-10-24T09:00","2026-10-24T10:00")),"move_session").isNotEmpty())
        val long=session().copy(schedule=timed("2026-10-23T09:00","2026-10-24T01:00"),permissions=AmendmentPermissions(omit=true))
        assertTrue(enumerate(input(listOf(long))).issues.any { it.reason=="whole_session_outside_horizon" })
    }

    @Test fun uncitedSportRestrictionAtDestinationBlocksMoveButDoesNotBanExplicitOmission() {
        val i=input(contexts=listOf(context(from="2026-10-19",until="2026-10-20")))
        val report=enumerate(i)
        assertFalse(report.candidates.any { it.operation=="move_session" })
        assertTrue(report.issues.any { it.reason=="sport_prohibited" && it.sources.isNotEmpty() })
        assertTrue(report.candidates.any { it.operation=="omit_session" })
        assertTrue(enumerate(input(contexts=listOf(context(value="ride")))).candidates.isNotEmpty())
    }

    @Test fun unknownConstraintMeaningsAndScopedIdentityNeverBecomePermissions() {
        for(kind in listOf("allowed_sport","allowed_activity","blocked_activity","maximum_duration")) {
            val report=enumerate(input(contexts=listOf(context(kind,if(kind=="maximum_duration")"60" else "run"))))
            assertEquals("needs_clarification",report.status);assertTrue(report.candidates.isEmpty())
            assertTrue(report.issues.any { it.reason=="constraint_execution_meaning_unknown" })
        }
        for(c in listOf(context(activityId="unknown-record"),context(reviewId="unknown-review"))) {
            val report=enumerate(input(contexts=listOf(c)))
            assertTrue(report.candidates.isEmpty());assertTrue(report.issues.any { it.reason=="context_scope_identity_unknown" })
        }
        val uncertainSport=enumerate(input(contexts=listOf(context(sport="RUN"))))
        assertTrue(uncertainSport.candidates.isEmpty())
        assertTrue(uncertainSport.issues.any { it.reason=="context_sport_identity_needs_confirmation" })
    }

    @Test fun staleIllnessReviewDueRetiredAndConflictingRestrictionsRemainClarificationsEvenForOmission() {
        for(c in listOf(context(category="symptom",until="2026-10-16"),context(category="symptom"),context(due="2026-10-17"),context(retired=true),context(sourceChanged=true))) {
            val report=enumerate(input(contexts=listOf(c)));assertEquals("needs_clarification",report.status);assertTrue(report.candidates.isEmpty())
        }
        val conflict=enumerate(input(contexts=listOf(context(),context("allowed_sport"))))
        assertTrue(conflict.issues.any { it.reason=="restriction_conflict" });assertTrue(conflict.candidates.isEmpty())
        val maxima=enumerate(input(contexts=listOf(context("maximum_duration","30"),context("maximum_duration","60"))))
        assertTrue(maxima.issues.any { it.reason=="restriction_conflict" })
        assertTrue(enumerate(input(contexts=listOf(context(resolution="resolved")))).candidates.isNotEmpty())
    }

    @Test fun busyIntervalsUseHalfOpenOverlapAndReductionDoesNotShortenOccupancy() {
        val busy=commitment();val i=input(commitments=listOf(busy));val report=enumerate(i)
        assertFalse(report.candidates.any { it.operation=="move_session" && it.after.schedule.startsLocal=="2026-10-19T09:00" })
        assertTrue(report.candidates.any { it.operation=="move_session" && it.after.schedule.startsLocal=="2026-10-20T09:00" })
        val touching=input(commitments=listOf(commitment("2026-10-19T10:00","2026-10-19T11:00")))
        assertTrue(enumerate(touching).candidates.any { it.after.schedule.startsLocal=="2026-10-19T09:00" })
        val duringOriginal=enumerate(input(commitments=listOf(commitment("2026-10-18T09:45","2026-10-18T10:15"))))
        assertFalse(duringOriginal.candidates.any { it.operation=="reduce_duration" })
        assertTrue(duringOriginal.candidates.any { it.operation=="omit_session" })
    }

    @Test fun recurrenceExceptionsAndReschedulesIntoHorizonAreNotIgnored() {
        val daily=commitment("2026-10-01T09:30","2026-10-01T10:30",recurrence=CommitmentRecurrence("daily",1,"2026-10-23"),
            exceptions=listOf(CommitmentException("2026-10-19","cancel")))
        val report=enumerate(input(commitments=listOf(daily)))
        assertTrue(report.candidates.any { it.operation=="move_session" && it.after.schedule.startsLocal=="2026-10-19T09:00" })
        assertFalse(report.candidates.any { it.operation=="move_session" && it.after.schedule.startsLocal=="2026-10-20T09:00" })
        val moved=commitment("2026-09-01T09:00","2026-09-01T10:00",exceptions=listOf(CommitmentException("2026-09-01","reschedule","2026-10-19T09:00","2026-10-19T10:00")))
        assertFalse(enumerate(input(commitments=listOf(moved))).candidates.any { it.after.schedule.startsLocal=="2026-10-19T09:00" })
    }

    @Test fun availabilityAndStaleCommitmentSourcesAreExplicitUnknownsNotBusyTimeOrOverrides() {
        for(c in listOf(commitment(kind="availability"),commitment(stale=true))) {
            val report=enumerate(input(commitments=listOf(c)))
            assertEquals("needs_clarification",report.status);assertTrue(report.candidates.isEmpty())
            assertTrue(report.issues.any { it.reason in setOf("availability_execution_meaning_unknown","commitment_needs_confirmation") })
        }
    }

    @Test fun dateOnlyUsesEntireCivilDayAndEarlierCrossZoneCutoff() {
        val s=session().copy(schedule=PlanSchedule("date_only",date="2026-10-18",timeZone="UTC"),permissions=AmendmentPermissions(PlanMoveWindow("2026-10-19","2026-10-20"),omit=true))
        val report=enumerate(input(listOf(s),commitments=listOf(commitment("2026-10-19T23:59","2026-10-20T00:01"))))
        assertFalse(report.candidates.any { it.operation=="move_session" })
        val eastern=s.copy(schedule=PlanSchedule("date_only",date="2026-10-18",timeZone="America/Los_Angeles"),permissions=AmendmentPermissions(omit=true))
        val i=input(listOf(eastern))
        assertTrue(PlanAmendmentPolicy.enumerate(i,Instant.parse("2026-10-18T01:00:00Z")).candidates.isEmpty())
    }

    @Test fun timedMovesPreserveBothSpansAndNeverChooseGapOrOverlapOffsets() {
        val s=session().copy(schedule=timed("2026-10-24T01:30","2026-10-24T03:30","Europe/Prague"),permissions=AmendmentPermissions(PlanMoveWindow("2026-10-25T01:30","2026-10-26T01:30")))
        val i=input(listOf(s),policy=PlanPolicy("UTC",14,0));val report=enumerate(i)
        assertFalse(report.candidates.any { it.after.schedule.startsLocal=="2026-10-25T01:30" })
        assertTrue(report.candidates.any { it.after.schedule.startsLocal=="2026-10-26T01:30" })
        assertTrue(report.issues.any { it.reason=="destination_time_or_dst_occupancy_uncertain" })
        val overlap=s.copy(permissions=AmendmentPermissions(PlanMoveWindow("2026-10-25T02:30","2026-10-25T02:30")))
        assertTrue(enumerate(input(listOf(overlap),policy=PlanPolicy("UTC",14,0))).candidates.isEmpty())
        val explicit=s.copy(schedule=timed("2026-10-25T01:30","2026-10-25T03:30","Europe/Prague"))
        assertTrue(PlanAmendmentPolicy.checkOperation(i,at,s,explicit,"move_session").isNotEmpty())
        val spring=s.copy(schedule=timed("2026-03-28T01:30","2026-03-28T03:30","Europe/Prague"),permissions=AmendmentPermissions(PlanMoveWindow("2026-03-29T02:30","2026-03-29T02:30")))
        assertTrue(PlanAmendmentPolicy.enumerate(input(listOf(spring)),Instant.parse("2026-03-27T12:00:00Z")).candidates.isEmpty())
    }

    @Test fun collisionsAndNoopBoundariesDoNotManufactureAlternatives() {
        val s=session().copy(permissions=AmendmentPermissions(PlanMoveWindow("2026-10-18T09:00","2026-10-18T09:00")))
        assertEquals("no_candidates",enumerate(input(listOf(s))).status)
        val other=session().copy(sessionId=UUID.randomUUID().toString(),schedule=timed("2026-10-19T09:30","2026-10-19T10:30"),permissions=AmendmentPermissions())
        assertFalse(enumerate(input(listOf(session(),other))).candidates.any { it.after.schedule.startsLocal=="2026-10-19T09:00" })
    }

    @Test fun candidateWorkAndByteOverflowExposeNoPartialSet() {
        val many=(1..101).map { n -> session().copy(sessionId=UUID.nameUUIDFromBytes("s$n".toByteArray()).toString(),permissions=AmendmentPermissions(omit=true)) }
        val count=enumerate(input(many));assertTrue(count.candidates.isEmpty());assertEquals("candidate_count_overflow",count.issues.single().reason)
        val work=enumerate(input(contexts=(1..325).map { context(value="ride") }))
        assertTrue(work.candidates.isEmpty());assertEquals("candidate_work_overflow",work.issues.single().reason)
        val bytes=enumerate(input(many.take(5).map { it.copy(notes="x".repeat(4000)) }))
        assertTrue(bytes.candidates.isEmpty());assertEquals("candidate_byte_overflow",bytes.issues.single().reason)
    }

    @Test fun individuallyValidMovesCannotCollideInTheCombinedResult() {
        val first=session().copy(permissions=AmendmentPermissions(PlanMoveWindow("2026-10-21T09:00","2026-10-21T09:00")))
        val second=first.copy(sessionId=UUID.randomUUID().toString(),schedule=timed("2026-10-19T09:00","2026-10-19T10:00"))
        val i=input(listOf(first,second));val candidates=enumerate(i).candidates
        assertEquals(2,candidates.size)
        candidates.forEach { assertTrue(check(i,it.after,it.operation,it.before).isEmpty()) }
        val issues=PlanAmendmentPolicy.checkWholeResult(i,at,candidates)
        assertTrue(issues.any { it.reason=="combined_plan_session_overlap" })
        assertTrue(PlanAmendmentPolicy.checkWholeResult(i,at,candidates.take(1)).isEmpty())
        assertEquals(listOf(first,second),i.plan!!.definition.sessions)
    }

    @Test fun wholeResultRetainsNoSwapPolicyAndDoesNotAcceptMultipleOperationsForOneSession() {
        val i=input();val candidates=enumerate(i).candidates
        assertEquals("invalid_whole_selection",PlanAmendmentPolicy.checkWholeResult(i,at,candidates.take(2)).single().reason)
        val first=session();val other=first.copy(sessionId=UUID.randomUUID().toString(),schedule=timed("2026-10-19T09:00","2026-10-19T10:00"))
        val original=input(listOf(first,other))
        val move=AmendmentCandidateV1("a".repeat(64),first.sessionId,"move_session",first,first.copy(schedule=other.schedule))
        val omit=AmendmentCandidateV1("b".repeat(64),other.sessionId,"omit_session",other,other.copy(omitted=true))
        assertTrue(PlanAmendmentPolicy.checkWholeResult(original,at,listOf(move,omit)).any { it.reason=="plan_session_overlap" })
    }
}
