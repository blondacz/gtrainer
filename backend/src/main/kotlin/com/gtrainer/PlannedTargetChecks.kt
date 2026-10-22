package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
data class PlannedTargetCheck(
    val plan: PlanRef,
    val sessionId: String,
    val targetIndex: Int,
    val target: PlannedTarget,
    val activityId: String?,
    val activityRevision: Long?,
    val recordSha256: String?,
    val status: String,
    val reason: String,
    val actualValue: Double?,
    val actualUnit: String?,
    val actualField: String?,
    val actualBasis: String?,
    val source: String?,
    val upstreamOrigin: String?,
    val effortReport: ActivityEffortReport? = null,
    val policy: String = "authored_inclusive_range_v1"
) { override fun toString() = "PlannedTargetCheck([REDACTED])" }

internal object PlannedTargetChecks {
    fun evaluate(plan: AuthoredPlan, session: PlannedSession, finding: PlanSessionFinding,
                 identity: ReconciliationActivity?, record: ActivityRecord?,
                 reports: List<ActivityEffortReport>): List<PlannedTargetCheck> = session.targets.mapIndexed { index, target ->
        fun result(status: String, reason: String, value: Double? = null, unit: String? = null,
                   field: String? = null, basis: String? = null, source: String? = null,
                   origin: String? = null, report: ActivityEffortReport? = null) =
            PlannedTargetCheck(plan.reference, session.sessionId, index, target, identity?.activityId,
                identity?.revision, identity?.recordSha256, status, reason, value?.takeIf { it.isFinite() },
                unit, field, basis, source, origin, report)

        if (finding.state != "matched") return@mapIndexed result("unevaluable",
            if (finding.state == "needs_confirmation") "confirmation_evidence_changed" else "match_unresolved")
        if (identity == null || record == null || finding.activityId != identity.activityId ||
            // Nonfinite test/unsupported measurements cannot be canonical serialized evidence.
            !identity.matchesRecord(record) ||
            finding.confirmation?.activity?.let { it.activityId != identity.activityId || it.recordSha256 != identity.recordSha256 } == true)
            return@mapIndexed result("unevaluable", "activity_evidence_unavailable")
        if (target.metric == "reported_effort") {
            val matching = reports.filter { it.activityId == identity.activityId && it.scale.scaleId == target.effortScale?.scaleId }
            val heads = matching.groupBy { it.reportId }.map { (_,history) -> history.maxBy { it.revision } }
            val report = heads.singleOrNull() ?: return@mapIndexed result("unevaluable", if (heads.size > 1) "ambiguous_effort_report" else "measurement_missing")
            if (report.withdrawn) return@mapIndexed result("unevaluable", "effort_report_withdrawn", report=report)
            if (report.detached) return@mapIndexed result("unevaluable", "effort_report_detached", report=report)
            if (report.activityRevision != identity.revision || report.activityRecordSha256 != identity.recordSha256)
                return@mapIndexed result("unevaluable", "activity_evidence_unavailable", report=report)
            if (report.scale != target.effortScale) return@mapIndexed result("unevaluable", "effort_scale_mismatch", report=report)
            val value=report.value
            return@mapIndexed result(if(value < target.minimum) "below_range" else if(value > target.maximum) "above_range" else "within_range",
                "assessed", value, "scale_points", "reportedEffort", "reported", report.recordedBy, report=report)
        }
        val reading = SupportedActivityMetrics.read(target.metric, target.unit, target.basis, record)
        if (reading.reason != "assessed") return@mapIndexed result("unevaluable", reading.reason,
            reading.value, reading.unit, reading.field, reading.basis, reading.source, reading.upstreamOrigin)
        val value = reading.value!!
        result(if(value < target.minimum) "below_range" else if(value > target.maximum) "above_range" else "within_range",
            "assessed", value, reading.unit, reading.field, reading.basis, reading.source, reading.upstreamOrigin)
    }
}
