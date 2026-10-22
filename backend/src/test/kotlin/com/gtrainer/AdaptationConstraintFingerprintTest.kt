package com.gtrainer

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptationConstraintFingerprintTest {
    private val planId = "11111111-1111-4111-8111-111111111111"
    private val contextId = "22222222-2222-4222-8222-222222222222"
    private val commitmentId = "33333333-3333-4333-8333-333333333333"
    private val instant = Instant.parse("2026-10-16T12:00:00Z")

    private fun plan() = AuthoredPlan(PlanRef(planId, 1), null, AuthoredPlanRequest("Plan",
        PlanAuthor("user", "athlete"), listOf(PlannedSession("44444444-4444-4444-8444-444444444444",
            PlanAuthor("user", "athlete"), "running", "session", PlanSchedule("date_only", date = "2026-10-17", timeZone = "UTC"))),
        PlanPolicy("UTC", 7, 0)), "owner", "2026-10-16T00:00:00Z")

    private fun context(content: String, revision: Int = 1, observed: String = "2026-10-15") = AthleteContext(
        contextId, revision, "restriction", "user_report", "author", "entered", observed,
        content = content, restrictionKind = "blocked_sport", restrictionValue = " RUNNING ")

    private fun view(value: AthleteContext) = ContextLifecycleView(value, ContextLifecycle(), "current", false, false, emptyList())

    private fun commitment(label: String) = ConfirmedCommitment(
        CommitmentEvidenceRef(commitmentId, 1, "55555555-5555-4555-8555-555555555555", 1, null, null),
        CommitmentDefinition(label, "commitment", "UTC", "2026-10-17T10:00", "2026-10-17T11:00"),
        null, "author", "user_report", "owner", "2026-10-16T00:00:00Z", false, false, false)

    @Test fun textAndLabelOnlyEditsChangeDependencyNotMaterial() {
        val first = AdaptationConstraintFingerprints.project(plan(), instant, listOf(view(context("original"))), listOf(commitment("race")))
        val changed = AdaptationConstraintFingerprints.project(plan(), instant, listOf(view(context("edited"))), listOf(commitment("renamed")))
        assertEquals(first.map { it.materialSha256 }, changed.map { it.materialSha256 })
        assertNotEquals(first.map { it.dependencySha256 }, changed.map { it.dependencySha256 })
        assertTrue(first.all { it.relevant })
    }

    @Test fun commitmentOccurrenceRemainsRelevantWithoutFutureSessionTargets() {
        val empty = plan().let { it.copy(definition = it.definition.copy(sessions = emptyList())) }
        val projected = AdaptationConstraintFingerprints.project(empty, instant, listOf(view(context("note"))), listOf(commitment("race")))
        assertTrue(projected.single { it.kind == "commitment" }.relevant)
        assertFalse(projected.single { it.kind == "context" }.relevant)
    }

    @Test fun relevantSafetyValueChangesMaterialAndNewerPostCutoffRevisionDoesNotResurrect() {
        val one = context("note", 1)
        val original = AdaptationConstraintFingerprints.project(plan(), instant, listOf(view(one)), emptyList()).single()
        val edited = AdaptationConstraintFingerprints.project(plan(), instant, listOf(view(one.copy(restrictionValue = "cycling"))), emptyList()).single()
        assertNotEquals(original.materialSha256, edited.materialSha256)

        val staleOnly = AdaptationConstraintFingerprints.project(plan(), instant,
            listOf(view(one), view(one.copy(revision = 2, observedOn = "2026-10-17"))), emptyList())
        assertTrue(staleOnly.isEmpty())
    }

    @Test fun onlyScheduleSportAndOccurrenceMembershipDriveRelevanceAndOrderIsStable() {
        val restriction = view(context("x"))
        val first = AdaptationConstraintFingerprints.project(plan(), instant, listOf(restriction), listOf(commitment("x")))
        val reverse = AdaptationConstraintFingerprints.project(plan(), instant, listOf(restriction), listOf(commitment("x")).reversed())
        assertEquals(first, reverse)
        assertFalse(AdaptationConstraintFingerprints.project(plan(), instant,
            listOf(view(context("x").copy(sport = "cycling"))), emptyList()).single().relevant)
        assertFalse(AdaptationConstraintFingerprints.project(plan(), instant, emptyList(),
            listOf(commitment("x").copy(definition = commitment("x").definition.copy(startsLocal = "2026-10-30T10:00", endsLocal = "2026-10-30T11:00")))).single().relevant)
    }

    @Test fun clockDrivenReviewDueAndUnresolvedExpiryAreNotRecovery() {
        val symptom=context("symptom").copy(category="symptom",restrictionKind=null,restrictionValue=null,applicableUntil="2026-10-15")
        val original=view(symptom).copy(lifecycle=ContextLifecycle(reviewDueOn="2026-10-17"))
        val future=plan().let { it.copy(definition=it.definition.copy(sessions=it.definition.sessions.map { s -> s.copy(schedule=s.schedule.copy(date="2026-10-18")) })) }
        val before=AdaptationConstraintFingerprints.project(future,instant,listOf(original),emptyList()).single()
        val after=AdaptationConstraintFingerprints.project(future,Instant.parse("2026-10-17T00:00:00Z"),listOf(original),emptyList()).single()
        assertTrue(before.relevant);assertTrue(after.relevant)
        assertEquals(before.materialSha256,after.materialSha256) // Midnight is not an authored canonical change.
        assertNotEquals(before.dependencySha256,after.dependencySha256) // Reproject even an owner-provided stale view.
        val resolved=AdaptationConstraintFingerprints.project(future,instant,listOf(original.copy(lifecycle=ContextLifecycle("resolved"))),emptyList()).single()
        assertNotEquals(before.materialSha256,resolved.materialSha256)
    }

    @Test fun authoredZoneOutsidePolicyDateStillUsesOverlappingAbsoluteInterval() {
        val base=plan()
        val session=base.definition.sessions.single().copy(schedule=PlanSchedule("timed",startsLocal="2026-10-18T00:30",endsLocal="2026-10-18T01:30",timeZone="Pacific/Kiritimati"))
        val zoned=base.copy(definition=base.definition.copy(sessions=listOf(session),policy=PlanPolicy("UTC",1,0)))
        val at=Instant.parse("2026-10-17T00:00:00Z")
        val restriction=view(context("x").copy(applicableFrom="2026-10-17",applicableUntil="2026-10-17"))
        assertTrue(AdaptationConstraintFingerprints.project(zoned,at,listOf(restriction),emptyList()).single().relevant)
        assertFalse(AdaptationConstraintFingerprints.project(zoned,Instant.parse("2026-10-19T00:00:00Z"),listOf(restriction),emptyList()).single().relevant)
    }

    @Test fun equivalentNumericRestrictionsAndRecurrenceExceptionsAreCanonical() {
        val max=context("limit").copy(restrictionKind="maximum_duration",restrictionValue="60.0",restrictionUnit="minutes")
        val first=AdaptationConstraintFingerprints.project(plan(),instant,listOf(view(max)),emptyList()).single()
        val second=AdaptationConstraintFingerprints.project(plan(),instant,listOf(view(max.copy(restrictionValue="60"))),emptyList()).single()
        assertEquals(first.materialSha256,second.materialSha256)
        assertNotEquals(first.dependencySha256,second.dependencySha256)
        val recurring=commitment("weekly").let { it.copy(definition=it.definition.copy(recurrence=CommitmentRecurrence("weekly"))) }
        val cancelled=recurring.copy(definition=recurring.definition.copy(exceptions=listOf(CommitmentException("2026-10-17","cancel"))))
        val original=AdaptationConstraintFingerprints.project(plan(),instant,emptyList(),listOf(recurring)).single()
        val changed=AdaptationConstraintFingerprints.project(plan(),instant,emptyList(),listOf(cancelled)).single()
        assertTrue(original.relevant);assertFalse(changed.relevant)
        assertNotEquals(original.materialSha256,changed.materialSha256)
    }

    @Test fun horizonMembershipChangesEligibilityButNotCanonicalMaterial() {
        val base=plan().let { it.copy(definition=it.definition.copy(sessions=listOf(it.definition.sessions.single().copy(
            schedule=PlanSchedule("date_only",date="2026-10-18",timeZone="UTC"))),policy=PlanPolicy("UTC",2,0))) }
        val future=view(context("future").copy(applicableFrom="2026-10-18",applicableUntil="2026-10-18"))
        val scheduled=commitment("future").let { it.copy(definition=it.definition.copy(startsLocal="2026-10-18T10:00",endsLocal="2026-10-18T11:00")) }
        // Ensure a future session is available on both horizons, but only the second
        // horizon reaches this constraint/commitment; no policy or authored revision changed.
        val nearer=base.definition.sessions.single().copy(sessionId="66666666-6666-4666-8666-666666666666",schedule=PlanSchedule("timed",startsLocal="2026-10-17T18:00",endsLocal="2026-10-17T19:00",timeZone="UTC"))
        val authored=base.copy(definition=base.definition.copy(sessions=base.definition.sessions+nearer))
        val first=AdaptationConstraintFingerprints.project(authored,instant,listOf(future),listOf(scheduled))
        val second=AdaptationConstraintFingerprints.project(authored,Instant.parse("2026-10-17T12:00:00Z"),listOf(future),listOf(scheduled))
        assertTrue(first.none { it.relevant });assertTrue(second.all { it.relevant })
        assertEquals(first.map { it.materialSha256 },second.map { it.materialSha256 })
        assertNotEquals(first.map { it.dependencySha256 },second.map { it.dependencySha256 })
    }
}
