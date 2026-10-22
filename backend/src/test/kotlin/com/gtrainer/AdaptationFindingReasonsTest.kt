package com.gtrainer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdaptationFindingReasonsTest {
    private val plan = PlanRef("11111111-1111-4111-8111-111111111111", 1)
    private val sessionId = "22222222-2222-4222-8222-222222222222"
    private fun finding(state: String, checks: List<PlannedTargetCheck> = emptyList()) = PlanSessionFinding(
        sessionId, "Run", "easy", PlanSchedule("date_only", date="2026-01-01", timeZone="UTC"),
        state, "synthetic", null, emptyList(), null, null, checks)
    private fun core(vararg findings: PlanSessionFinding) = PlanReconciliationCore(
        PlanSelection(1, plan), findings.toList(), emptyList(), emptyList(), "a".repeat(64))
    private fun target(status: String, targetIndex: Int = 0) = PlannedTargetCheck(
        plan, sessionId, targetIndex, PlannedTarget("duration", "seconds", "moving", 10.0, 20.0, null),
        null, null, null, status, "synthetic", 25.0, "seconds", "movingTime", "moving", "synthetic", null)
    private fun rule(id: String = "33333333-3333-4333-8333-333333333333", aggregation: String = "per_record") =
        ExtraWorkloadRule(id, 1, true, ExtraWorkloadConfiguration(aggregation, "duration", "seconds", "moving",
            "Run", "synthetic", null, "UTC", 7, "greater_than", 30.0), "2026-01-01T00:00:00Z")
    private fun activity(id: Char) = ReconciliationActivity(id.toString().repeat(64), 1, "b".repeat(64),
        "Run", "2026-01-01T10:00", "2026-01-01T10:00:00Z", "upstream_instant")
    private fun item(id: Char, status: String, reason: String = "configured_workload_exceeded", value: Double? = 40.0) =
        ExtraWorkloadItem(activity(id), status, reason, value, "movingTime", "seconds", "moving", "synthetic", null)
    private fun report(r: ExtraWorkloadRule = rule(), status: String = "triggered", items: List<ExtraWorkloadItem> = listOf(item('c', "triggered")),
        oldest: String? = "2026-01-01", newest: String? = "2026-01-07") =
        ExtraWorkloadReport(r, oldest, newest, status, 40.0, items, items.map { it.activity.activityId })

    @Test fun confirmedSkipIsActiveButUnmatchedAndAmbiguousAreNotSkips() {
        val observations = listOf("confirmed_skipped", "provisional_unmatched", "ambiguous", "needs_confirmation")
            .associateWith { AdaptationFindingReasons.reconcile(core(finding(it))).single() }
        assertEquals("active", observations.getValue("confirmed_skipped").condition)
        assertEquals("provisional", observations.getValue("provisional_unmatched").condition)
        assertEquals("needs_confirmation", observations.getValue("ambiguous").condition)
        assertEquals("needs_confirmation", observations.getValue("needs_confirmation").condition)
        assertEquals("clear", AdaptationFindingReasons.reconcile(core(finding("matched"))).single().condition)
    }

    @Test fun targetBoundariesAndUnsupportedStatesAreConservativeAndMatchGated() {
        val session = finding("matched", listOf(target("above_range"), target("below_range", 1), target("within_range", 2), target("unevaluable", 3)))
        assertEquals(listOf("active", "active", "clear", "unevaluable"), AdaptationFindingReasons.reconcile(core(session)).drop(1).map { it.condition })
        assertEquals("provisional", AdaptationFindingReasons.reconcile(core(finding("provisional_unmatched", listOf(target("above_range"))))).last().condition)
        assertEquals("clear", AdaptationFindingReasons.reconcile(core(finding("omitted", listOf(target("above_range"))))).last().condition)
    }

    @Test fun reconciliationIdentityIgnoresEvidenceAndInspectionWindowButDependenciesDoNot() {
        val first = AdaptationFindingReasons.reconcile(core(finding("matched"))).single()
        val changed = AdaptationFindingReasons.reconcile(core(finding("needs_confirmation"))).single()
        val otherWindow = AdaptationFindingReasons.reconcile(core(finding("matched"))).single()
        assertEquals(first.reasonId, changed.reasonId); assertEquals(first.reasonId, otherWindow.reasonId)
        assertNotEquals(first.dependencySha256, changed.dependencySha256)
    }

    @Test fun workloadLineageIsPerRuleAndActivityAndStableAcrossCorrectionsAndWindowRoll() {
        val first = AdaptationFindingReasons.workload(plan, report()).single()
        val corrected = AdaptationFindingReasons.workload(plan, report(items=listOf(item('c', "triggered", value=50.0)))).single()
        val rolled = AdaptationFindingReasons.workload(plan, report(oldest="2026-01-02", newest="2026-01-08")).single()
        val otherActivity = AdaptationFindingReasons.workload(plan, report(items=listOf(item('d', "triggered")))).single()
        val otherRule = AdaptationFindingReasons.workload(plan, report(rule("44444444-4444-4444-8444-444444444444"))).single()
        assertEquals(first.reasonId, corrected.reasonId); assertEquals(first.reasonId, rolled.reasonId)
        assertNotEquals(first.reasonId, otherActivity.reasonId); assertNotEquals(first.reasonId, otherRule.reasonId)
        assertNotEquals(first.dependencySha256, corrected.dependencySha256)
    }

    @Test fun cumulativeWorkloadUsesStableRuleLineageAcrossRollingReports() {
        val cumulative = rule(aggregation="cumulative")
        val first = AdaptationFindingReasons.workload(plan, report(cumulative)).single()
        val rolling = AdaptationFindingReasons.workload(plan, report(cumulative, oldest="2026-01-02", newest="2026-01-08")).single()
        assertEquals(first.reasonId, rolling.reasonId)
        assertNotEquals(first.dependencySha256, rolling.dependencySha256)
    }

    @Test fun disabledEmptyAndOutsideWindowAreNotPositiveClearAndMatchedIsClear() {
        val disabled = rule().copy(enabled=false)
        assertTrue(AdaptationFindingReasons.workload(plan, report(disabled)).isEmpty())
        assertEquals("provisional", AdaptationFindingReasons.workload(plan, report(rule(aggregation="cumulative"),status="clear", items=emptyList())).single().condition)
        assertEquals("provisional", AdaptationFindingReasons.workload(plan, report(items=listOf(item('c', "clear", "outside_configured_window", null)))).single().condition)
        assertEquals("clear", AdaptationFindingReasons.workload(plan, report(items=listOf(item('c', "clear", "matched_to_selected_plan", null)))).single().condition)
    }

    @Test fun projectedEpisodeClearsThenRetriggersButAbnormalCorrectionDoesNotReadmit() {
        val original = AdaptationFindingReasons.workload(plan, report()).single()
        val first = AdaptationReasons.project(null, original)
        val receipt = AdaptationAdmission(first.reasonId, first.episode, "55555555-5555-4555-8555-555555555555", "2026-01-01T00:00:00Z")
        val changedAbnormal = AdaptationReasons.project(first, AdaptationFindingReasons.workload(plan,
            report(items=listOf(item('c', "triggered", value=60.0)))).single())
        assertEquals(first.episode, changedAbnormal.episode)
        assertEquals(false, AdaptationReasons.requiresAdmission(changedAbnormal, listOf(receipt)))
        val clear = AdaptationReasons.project(changedAbnormal, AdaptationFindingReasons.workload(plan,
            report(status="clear", items=listOf(item('c', "clear", "within_configured_limit")))).single())
        val retrigger = AdaptationReasons.project(clear, original)
        assertTrue(AdaptationReasons.requiresAdmission(retrigger, listOf(receipt)))
    }

    @Test fun outputOrderIsDeterministic() {
        val a = finding("matched").copy(sessionId="33333333-3333-4333-8333-333333333333")
        val b = finding("confirmed_skipped")
        assertEquals(AdaptationFindingReasons.reconcile(core(a, b)), AdaptationFindingReasons.reconcile(core(b, a)))
    }

    @Test fun provenanceAddressesFollowCanonicalReasonsAndTargetOrderWithoutAdmission() {
        val session=finding("matched",listOf(target("below_range",1).copy(activityId="b".repeat(64)),
            target("above_range",0).copy(activityId="a".repeat(64))))
        val capture=core(session)
        val observations=AdaptationFindingReasons.reconcile(capture)
        val provenance=AdaptationFindingReasons.reconciliationProvenance(capture)
        assertEquals(observations.map { it.reasonId }.toSet(),provenance.keys)
        assertEquals(listOf("plan_session/$sessionId"),provenance[observations.first().reasonId])
        assertEquals(listOf("plan_session/$sessionId","planned_target/$sessionId/0","activity/${"a".repeat(64)}"),provenance[observations[1].reasonId])
        assertEquals(listOf("plan_session/$sessionId","planned_target/$sessionId/1","activity/${"b".repeat(64)}"),provenance[observations[2].reasonId])
    }

    @Test fun cumulativeClearingRequiresPositiveReconciliationOfEveryPriorContributor() {
        val initialReport=report(rule(aggregation="cumulative"))
        val observation=AdaptationFindingReasons.workload(plan,initialReport).single()
        val head=AdaptationReasons.project(null,observation)
        val admitted=AdaptationAdmission(head.reasonId,head.episode,"55555555-5555-4555-8555-555555555555","2026-01-01T00:00:00Z")
        val matched=item('c',"clear","matched_to_selected_plan",null)
        val clearedReport=initialReport.copy(status="clear",value=0.0,items=listOf(matched),supportingActivityIds=emptyList())
        val previous=setOf(matched.activity.activityId)
        assertEquals("provisional",AdaptationFindingReasons.workload(plan,clearedReport).single().condition)
        assertEquals("provisional",AdaptationFindingReasons.workload(plan,clearedReport,previous+"d".repeat(64)).single().condition)
        val clear=AdaptationFindingReasons.workload(plan,clearedReport,previous).single()
        assertEquals("clear",clear.condition)
        val retrigger=AdaptationReasons.project(AdaptationReasons.project(head,clear),observation)
        assertTrue(AdaptationReasons.requiresAdmission(retrigger,listOf(admitted)))
        val unavailable=clearedReport.copy(items=emptyList())
        assertEquals("provisional",AdaptationFindingReasons.workload(plan,unavailable,previous).single().condition)
    }
}
