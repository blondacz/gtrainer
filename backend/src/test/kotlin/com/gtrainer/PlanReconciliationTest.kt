package com.gtrainer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.UUID

class PlanReconciliationTest {
    private val planRef = PlanRef("11111111-1111-1111-1111-111111111111", 1)
    private val sessionId = "22222222-2222-2222-2222-222222222222"
    private val author = PlanAuthor("user", "tester")
    private fun session(id: String = sessionId, start: String = "2026-03-01T10:00", end: String = "2026-03-01T11:00",
                        sport: String = "run", zone: String = "UTC") = PlannedSession(
        id, author, sport, "Training", PlanSchedule("timed", startsLocal = start, endsLocal = end, timeZone = zone)
    )
    private fun activity(id: String = "a".repeat(64), instant: String = "2026-03-01T10:00:00Z", sport: String = "run",
                         revision: Long = 1) = ReconciliationActivity(id, revision, "b".repeat(64), sport,
        instant.take(19), instant, "upstream_instant")
    private fun capture(sessions: List<PlannedSession> = listOf(session()), activities: List<ReconciliationActivity> = emptyList(),
                        confirmations: List<PlanMatchConfirmation> = emptyList(), selected: PlanRef? = planRef,
                        coverage: List<CategoryStatus> = emptyList()): CapturedPlanReconciliation {
        val plan = selected?.let { AuthoredPlan(it, null, AuthoredPlanRequest("Plan", author, sessions,
            PlanPolicy("UTC", 30, 3600)), "tester", "2026-01-01T00:00:00Z") }
        return CapturedPlanReconciliation(PlanSelection(1, selected), plan, activities, confirmations, coverage, 0)
    }
    private fun reconcile(c: CapturedPlanReconciliation, at: String = "2026-03-02T00:00:00Z",
                          oldest: String = "2026-03-01", newest: String = "2026-12-31") = PlanReconciler.reconcile(
        PlanReconciliationQuery(oldest, newest, "UTC"), Instant.parse(at), c)
    private fun finding(c: CapturedPlanReconciliation, at: String = "2026-03-02T00:00:00Z") = reconcile(c, at).sessions

    @Test fun completeCoreIsIndependentOfDisplayedRangeAndSharesInspectionMatching() {
        val second="33333333-3333-4333-8333-333333333333"
        val c=capture(listOf(session(),session(second,"2026-06-01T10:00","2026-06-01T11:00")),
            listOf(activity(),activity("c".repeat(64),"2026-06-01T10:15:00Z")))
        val at=Instant.parse("2026-06-02T00:00:00Z")
        val core=PlanReconciler.core(at,c)
        val visible=reconcile(c,at.toString(),"2026-03-01","2026-03-01")
        assertEquals(2,core.sessions.size);assertEquals(2,core.activities.size)
        assertEquals(listOf(core.sessions.first()),visible.sessions)
        assertEquals(listOf(core.activities.first()),visible.activities)
        assertEquals(core.supportSha256,visible.supportSha256)
        assertEquals(listOf("matched","matched"),core.sessions.map { it.state })
    }

    @Test fun exactSportAndStartInsideHalfOpenWindowMatch() {
        val result = finding(capture(activities = listOf(activity(instant = "2026-03-01T10:15:00Z"),
            activity("c".repeat(64), "2026-03-01T11:00:00Z"), activity("d".repeat(64), "2026-03-01T10:30:00Z", "ride"))))
        assertEquals("matched", result.single().state)
        assertEquals(listOf("a".repeat(64)), result.single().candidateActivityIds)
        assertEquals("a".repeat(64), result.single().activityId)
    }

    @Test fun competingActivitiesAndOverlappingSessionsAreAmbiguous() {
        val a = activity()
        val b = activity("c".repeat(64), "2026-03-01T10:30:00Z")
        assertEquals("ambiguous", finding(capture(activities = listOf(a, b))).single().state)
        val overlap = capture(listOf(session(), session("33333333-3333-3333-3333-333333333333", "2026-03-01T09:30", "2026-03-01T10:30")), listOf(a))
        val view = reconcile(overlap, oldest = "2026-03-01", newest = "2026-03-01")
        assertEquals(listOf("ambiguous", "ambiguous"), view.sessions.map { it.state })
    }

    @Test fun userConfirmedMatchReservesItsActivity() {
        val a = activity()
        val confirmed = PlanMatchConfirmation("44444444-4444-4444-4444-444444444444", planRef, sessionId, "match", a, "user", "2026-03-01T12:00:00Z")
        val c = capture(listOf(session(), session("33333333-3333-3333-3333-333333333333", "2026-03-01T09:30", "2026-03-01T10:30")), listOf(a), listOf(confirmed))
        assertEquals("matched", finding(c)[0].state)
        assertEquals("ambiguous", finding(c)[1].state)
    }

    @Test fun preBoundaryAbsenceAwaitsEvidenceAndLaterBecomesProvisional() {
        val c = capture()
        assertEquals("awaiting_evidence", finding(c, "2026-03-01T11:59:59Z").single().state)
        assertEquals("provisional_unmatched", finding(c, "2026-03-01T12:00:00Z").single().state)
    }

    @Test fun lateActivityRevisesProvisionalAbsenceToMatch() {
        val result = finding(capture(activities = listOf(activity())), "2026-03-02T00:00:00Z").single()
        assertEquals("matched", result.state)
        assertEquals("compatible_unique_evidence", result.basis)
    }

    @Test fun futureInstantIsNotObservedAndNeverCreatesAnExtra() {
        val result = reconcile(capture(activities = listOf(activity(instant = "2026-03-03T23:00:00Z"))), "2026-03-03T00:00:00Z")
        assertEquals("provisional_unmatched", result.sessions.single().state)
        assertEquals("needs_confirmation", result.activities.single().state)
    }

    @Test fun absentSelectedPlanRequiresBaseAndCoverageIsPreserved() {
        val coverage = listOf(CategoryStatus("activities", 0, null, null, "unknown", 0, 0, null, null))
        val result = reconcile(capture(activities = listOf(activity()), selected = null, coverage = coverage))
        assertEquals("base_plan_required", result.activities.single().state)
        assertEquals(coverage, result.coverage)
    }

    @Test fun dateOnlyGraceUsesLocalDayEndAcrossDst() {
        fun date(id: String, date: String) = PlannedSession(id, author, "run", "Training",
            PlanSchedule("date_only", date = date, timeZone = "America/New_York"))
        val spring = date(sessionId, "2026-03-08")
        val fall = date("33333333-3333-3333-3333-333333333333", "2026-11-01")
        assertEquals(23 * 3600L, java.time.Duration.between(spring.schedule.startInstant(), spring.schedule.endInstant()).seconds)
        assertEquals(25 * 3600L, java.time.Duration.between(fall.schedule.startInstant(), fall.schedule.endInstant()).seconds)
        val policy = PlanPolicy("America/New_York", 30, 3600)
        val plan = AuthoredPlan(planRef, null, AuthoredPlanRequest("Plan", author, listOf(spring, fall), policy), "tester", "2026-01-01T00:00:00Z")
        val c = CapturedPlanReconciliation(PlanSelection(1, planRef), plan, emptyList(), emptyList(), emptyList(), 0)
        val springBoundary = unmatchedAfter(spring, policy)
        val fallBoundary = unmatchedAfter(fall, policy)
        assertEquals("awaiting_evidence", reconcile(c, springBoundary.minusSeconds(1).toString(), "2026-03-08", "2026-03-08").sessions.single().state)
        assertEquals("provisional_unmatched", reconcile(c, springBoundary.toString(), "2026-03-08", "2026-03-08").sessions.single().state)
        assertEquals("awaiting_evidence", reconcile(c, fallBoundary.minusSeconds(1).toString(), "2026-11-01", "2026-11-01").sessions.single().state)
        assertEquals("provisional_unmatched", reconcile(c, fallBoundary.toString(), "2026-11-01", "2026-11-01").sessions.single().state)
    }

    @Test fun confirmationIsBoundToCurrentPlanRevisionAndActivityFingerprint() {
        val a = activity()
        val confirmation = PlanMatchConfirmation("44444444-4444-4444-4444-444444444444", planRef, sessionId, "match", a, "user", "2026-03-01T12:00:00Z")
        assertEquals("matched", finding(capture(activities = listOf(a), confirmations = listOf(confirmation))).single().state)
        val correctedRef = planRef.copy(revision = 2)
        assertEquals("needs_confirmation", finding(capture(activities = listOf(a), confirmations = listOf(confirmation), selected = correctedRef)).single().state)
        val changed = a.copy(recordSha256 = "c".repeat(64), revision = 2)
        assertEquals("needs_confirmation", finding(capture(activities = listOf(changed), confirmations = listOf(confirmation))).single().state)
    }

    @Test fun userMatchRemainsReservedWhenEvidenceBecomesUnverified() {
        val a = activity()
        val confirmation = PlanMatchConfirmation("44444444-4444-4444-4444-444444444444", planRef, sessionId, "match", a, "user", "2026-03-01T12:00:00Z")
        val unverifiable = a.copy(startInstant = null, timeContext = "local_time_only")
        val sessions = listOf(session(), session("33333333-3333-3333-3333-333333333333", "2026-03-01T09:30", "2026-03-01T10:30"))
        val result = finding(capture(sessions, listOf(unverifiable), listOf(confirmation)))
        assertEquals("needs_confirmation", result[0].state)
        assertEquals("confirmation_evidence_changed",result[0].basis)
        assertEquals("provisional_unmatched", result[1].state)
        val reconfirmed=confirmation.copy(activity=unverifiable)
        val renewed=finding(capture(sessions,listOf(unverifiable),listOf(reconfirmed)))
        assertEquals("matched",renewed[0].state)
        assertEquals("user_confirmation_timing_unverified",renewed[0].basis)
    }

    @Test fun unknownActivityTimeNeedsConfirmationRatherThanExtra() {
        val unknown = activity().copy(startInstant = null, timeContext = "local_time_only")
        assertEquals("needs_confirmation", reconcile(capture(activities = listOf(unknown))).activities.single().state)
    }

    @Test fun confirmedSkipIsNeverInferredAndContradictoryLateActivityNeedsConfirmation() {
        val skip = PlanMatchConfirmation("55555555-5555-5555-5555-555555555555", planRef, sessionId, "skip", null, "user", "2026-03-01T12:00:00Z")
        assertEquals("confirmed_skipped", finding(capture(confirmations = listOf(skip))).single().state)
        val late = activity()
        val result = finding(capture(activities = listOf(late), confirmations = listOf(skip))).single()
        assertEquals("needs_confirmation", result.state)
        assertEquals("recorded_evidence_contradicts_confirmed_skip", result.basis)
    }

    @Test fun viewportDoesNotRemoveCompetitorsAndVerifiedVisibilityUsesQueryZoneNotSourceDate() {
        val earlier=session(start="2026-02-28T23:00",end="2026-03-02T01:00")
        val narrower=session("33333333-3333-3333-3333-333333333333",start="2026-03-01T10:00",end="2026-03-01T11:00")
        val shifted=activity().copy(startLocal="2026-03-02T00:00:00")
        val view=reconcile(capture(listOf(earlier,narrower),listOf(shifted)),oldest="2026-03-01",newest="2026-03-01")
        assertEquals(listOf("ambiguous","ambiguous"),view.sessions.map { it.state })
        assertEquals(1,view.activities.size)
        val noSessions=reconcile(capture(emptyList(),listOf(shifted)),oldest="2026-03-01",newest="2026-03-01")
        assertEquals(1,noSessions.activities.size)
        assertEquals("extra_relative_to_selected_plan",noSessions.activities.single().state)
    }

    @Test fun removedSessionConfirmationDoesNotOwnActivityInTheSelectedRevision() {
        val a=activity()
        val old=PlanMatchConfirmation("44444444-4444-4444-4444-444444444444",planRef,"33333333-3333-3333-3333-333333333333","match",a,"authenticated_user","2026-03-01T12:00:00Z")
        val result=reconcile(capture(activities=listOf(a),confirmations=listOf(old),selected=planRef.copy(revision=2)))
        assertEquals("matched",result.sessions.single().state)
        assertEquals("compatible_unique_evidence",result.sessions.single().basis)
    }

    @Test fun denseCandidateOverflowRefusesRatherThanAllocatingOrTruncatingAllEdges() {
        val manySessions=List(40) { session(UUID.randomUUID().toString()) }
        val manyActivities=List(1000) { activity(AnalysisClaims.hash("synthetic-$it")) }
        assertFailsWith<ScopedEvidenceInspectionLimit> { reconcile(capture(manySessions,manyActivities)) }
    }
}
