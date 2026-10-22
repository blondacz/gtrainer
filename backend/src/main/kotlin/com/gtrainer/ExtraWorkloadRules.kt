package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** Workload is not heart-rate intensity, perceived effort or a cross-sport conversion. */
@Serializable
data class ExtraWorkloadConfiguration(val aggregation: String,val metric: String,val unit: String,val basis: String,
    val sport: String,val source: String,val upstreamOrigin: String?,val timeZone: String,val windowDays: Int,
    val comparator: String,val threshold: Double) {
    init {
        require(aggregation in setOf("per_record","cumulative"))
        require(when(metric) {
            "duration" -> unit=="seconds" && basis in setOf("moving","elapsed")
            "distance" -> unit=="metres" && basis=="session_total"
            "intervals_training_load" -> unit=="Intervals.icu load" && basis=="intervals_session_load" && source=="intervals.icu"
            else -> false
        })
        validateContextText(sport,80,true);require(source.matches(Regex("[a-zA-Z0-9_.-]{1,64}")))
        upstreamOrigin?.let { validateContextText(it,128,true) }
        require(timeZone=="UTC" || timeZone in ZoneId.getAvailableZoneIds())
        require(windowDays in 1..366 && comparator in setOf("greater_than","at_least") && threshold.isFinite() && threshold>=0)
    }
    override fun toString()="ExtraWorkloadConfiguration([REDACTED])"
}

@Serializable
data class ExtraWorkloadRule(val ruleId: String,val revision: Int,val enabled: Boolean,val configuration: ExtraWorkloadConfiguration?,
    val recordedUtc: String,val recordedBy: String="authenticated_user") {
    init { validateContextId(ruleId);require(revision in 1..1000 && (!enabled || configuration!=null));Instant.parse(recordedUtc);require(recordedBy=="authenticated_user") }
    override fun toString()="ExtraWorkloadRule([REDACTED])"
}

@Serializable
data class ExtraWorkloadRuleRequest(val operationId: String,val ruleId: String,val expectedRevision: Int,
    val enabled: Boolean=false,val configuration: ExtraWorkloadConfiguration?=null) {
    init { validateContextId(operationId);validateContextId(ruleId);require(expectedRevision in 0..999 && (!enabled || configuration!=null)) }
    override fun toString()="ExtraWorkloadRuleRequest([REDACTED])"
}
@Serializable data class ExtraWorkloadRuleReceipt(val rule: ExtraWorkloadRule,val replayed: Boolean)

@Serializable
data class ExtraWorkloadItem(val activity: ReconciliationActivity,val status: String,val reason: String,val value: Double?,
    val field: String?,val unit: String?,val basis: String?,val source: String?,val upstreamOrigin: String?) {
    override fun toString()="ExtraWorkloadItem([REDACTED])"
}

@Serializable
data class ExtraWorkloadReport(val rule: ExtraWorkloadRule,val oldest: String?,val newest: String?,val status: String,
    val value: Double?,val items: List<ExtraWorkloadItem>,val supportingActivityIds: List<String>,
    val admission: String="not_integrated",
    val temporalBasis: String="verified_start_in_configured_zone_window_through_captured_cutoff",
    val completeness: String="bounded_local_evidence_not_upstream_completeness") {
    override fun toString()="ExtraWorkloadReport([REDACTED])"
}

data class CapturedExtraWorkload(val rule: ExtraWorkloadRule,val at: Instant,val reconciliation: CapturedPlanReconciliation,
    val totalChanges: Long,val dataVersion: Long,val sourceRevision: Long,val ownerId: String) {
    override fun toString()="CapturedExtraWorkload([REDACTED])"
}

interface ExtraWorkloadRepository {
    fun workloadRules(): List<ExtraWorkloadRule>
    fun saveWorkloadRule(request: ExtraWorkloadRuleRequest,at: Instant): ExtraWorkloadRuleReceipt
    fun captureExtraWorkload(id: String,at: Instant): CapturedExtraWorkload
    fun finishExtraWorkload(capture: CapturedExtraWorkload,report: ExtraWorkloadReport): ExtraWorkloadReport
}

internal object ExtraWorkloadRules {
    fun evaluate(rule: ExtraWorkloadRule,at: Instant,core: PlanReconciliationCore,records: Map<String,ActivityRecord>,
        retainedSubjects: Set<String> = emptySet()): ExtraWorkloadReport {
        require(records.size<=50_000 && retainedSubjects.size<=50_000 && core.activities.size<=50_000)
        val config=rule.configuration
        if(config==null || !rule.enabled) return ExtraWorkloadReport(rule,null,null,"disabled",null,emptyList(),emptyList())
        val zone=ZoneId.of(config.timeZone);val newest=at.atZone(zone).toLocalDate()
        val oldest=newest.minusDays(config.windowDays.toLong()-1);require(oldest.year in 1..9999)
        val start=oldest.atStartOfDay(zone).toInstant()
        val items=core.activities.sortedBy { it.activity.activityId }.mapNotNull { finding ->
            val identity=finding.activity;val record=records[identity.activityId]
            if(identity.sport!=config.sport || record?.source?.let { it!=config.source }==true) return@mapNotNull null
            val instant=identity.verifiedStart()
            val inWindow=instant?.let { it>=start && it<=at }
            // Unknown instants cannot be excluded by a guessed timezone mapping.
            if(inWindow==false && identity.activityId !in retainedSubjects) return@mapNotNull null
            fun item(status: String,reason: String,reading: SupportedActivityMetricReading?=null)=ExtraWorkloadItem(identity,status,reason,
                reading?.value,reading?.field,reading?.unit,reading?.basis,record?.source,reading?.upstreamOrigin)
            if(record==null || !identity.matchesRecord(record))
                return@mapNotNull item("unevaluable","activity_evidence_unavailable")
            if(finding.state=="matched") return@mapNotNull item("clear","matched_to_selected_plan")
            if(instant!=null && instant>at) return@mapNotNull item("needs_confirmation","recording_not_verified_past")
            if(inWindow==false) return@mapNotNull item("clear","outside_configured_window")
            if(inWindow==null || finding.state=="needs_confirmation") return@mapNotNull item("needs_confirmation","timing_or_match_unresolved")
            if(finding.state=="base_plan_required") return@mapNotNull item("unevaluable","base_plan_required")
            if(finding.state!="extra_relative_to_selected_plan") return@mapNotNull item("needs_confirmation","match_unresolved")
            val reading=SupportedActivityMetrics.read(config.metric,config.unit,config.basis,record)
            if(reading.reason!="assessed") return@mapNotNull item("unevaluable",reading.reason,reading)
            if(reading.upstreamOrigin!=config.upstreamOrigin) return@mapNotNull item("unevaluable","origin_mismatch",reading.copy(value=null))
            val value=requireNotNull(reading.value)
            if(config.aggregation=="cumulative") return@mapNotNull item("contributing","compatible_extra_evidence",reading)
            val triggered=if(config.comparator=="at_least") value>=config.threshold else value>config.threshold
            item(if(triggered) "triggered" else "clear",if(triggered) "configured_workload_exceeded" else "within_configured_limit",reading)
        }
        val supporting=items.filter { it.reason in setOf("configured_workload_exceeded","within_configured_limit","compatible_extra_evidence") }
        val unresolved=items.any { it.status in setOf("unevaluable","needs_confirmation") }
        val total=supporting.fold(0.0) { value,item -> value+requireNotNull(item.value) }
        val status=when {
            core.selection.selected==null -> "base_plan_required"
            config.aggregation=="per_record" -> if(items.any { it.status=="triggered" }) "triggered" else if(unresolved) "unevaluable" else "clear"
            unresolved || !total.isFinite() -> "unevaluable"
            supporting.isNotEmpty() && (if(config.comparator=="at_least") total>=config.threshold else total>config.threshold) -> "triggered"
            else -> "clear"
        }
        return ExtraWorkloadReport(rule,oldest.toString(),newest.toString(),status,
            if(config.aggregation=="cumulative" && !unresolved && total.isFinite()) total else null,items,supporting.map { it.activity.activityId })
    }
}
