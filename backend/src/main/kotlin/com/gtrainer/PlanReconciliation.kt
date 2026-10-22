package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class PlanReconciliationQuery(val oldest: String, val newest: String, val timeZone: String) {
    init {
        ScopedDateRange(oldest,newest).range()
        require(timeZone=="UTC" || timeZone in ZoneId.getAvailableZoneIds())
    }
}

@Serializable
data class ReconciliationActivity(val activityId: String, val revision: Long?, val recordSha256: String,
    val sport: String, val startLocal: String, val startInstant: String?, val timeContext: String) {
    init {
        require(activityId.matches(Regex("[a-f0-9]{64}")) && recordSha256.matches(Regex("[a-f0-9]{64}")))
        require(revision==null || revision>0)
        require(sport.isNotBlank() && sport.length<=80 && timeContext.length in 1..80)
        java.time.LocalDateTime.parse(startLocal);startInstant?.let(Instant::parse)
    }
    override fun toString()="ReconciliationActivity([REDACTED])"
}

internal fun ReconciliationActivity.verifiedStart(): Instant? = startInstant?.takeIf {
    timeContext in setOf("upstream_instant","explicit_offset","named_zone")
}?.let(Instant::parse)

internal fun ReconciliationActivity.matchesRecord(record: ActivityRecord): Boolean =
    scopedActivityId(record)==activityId && record.sport==sport && record.startLocal==startLocal &&
        record.startInstant==startInstant && record.timeContext==timeContext &&
        runCatching { AnalysisClaims.hash(Json.encodeToString(record))==recordSha256 }.getOrDefault(false)

@Serializable
data class PlanMatchConfirmation(val confirmationId: String, val plan: PlanRef, val sessionId: String,
    val action: String, val activity: ReconciliationActivity?, val recordedBy: String, val recordedUtc: String) {
    override fun toString()="PlanMatchConfirmation([REDACTED])"
}

@Serializable
data class PlanSessionFinding(val sessionId: String, val sport: String, val objective: String,
    val schedule: PlanSchedule, val state: String, val basis: String, val activityId: String?,
    val candidateActivityIds: List<String>, val unmatchedAfter: String?, val confirmation: PlanMatchConfirmation?,
    val targetChecks: List<PlannedTargetCheck> = emptyList()) {
    override fun toString()="PlanSessionFinding([REDACTED])"
}

@Serializable
data class PlanActivityFinding(val activity: ReconciliationActivity, val state: String, val sessionIds: List<String>) {
    override fun toString()="PlanActivityFinding([REDACTED])"
}

@Serializable
data class PlanReconciliationView(val query: PlanReconciliationQuery, val asOf: String, val selection: PlanSelection,
    val digest: String, val supportSha256: String, val sessions: List<PlanSessionFinding>,
    val activities: List<PlanActivityFinding>, val coverage: List<CategoryStatus>,
    val policy: String = "one_to_one_exact_sport_verified_start_in_half_open_authored_interval_mutual_unique_v1",
    val limitation: String = "current_corrected_evidence_not_past_knowledge;bounded_complete_local_support_not_upstream_completeness;extra_relative_to_selected_plan_not_unintended") {
    override fun toString()="PlanReconciliationView([REDACTED])"
}

@Serializable
data class PlanReconciliationConfirmationRequest(val confirmationId: String, val plan: PlanRef, val sessionId: String,
    val action: String, val activityId: String? = null, val expectedSelectionGeneration: Long,
    val expectedConfirmationId: String? = null, val expectedDigest: String, val asOf: String,
    val query: PlanReconciliationQuery) {
    init {
        validateContextId(confirmationId); validateContextId(sessionId)
        expectedConfirmationId?.let(::validateContextId)
        require(action in setOf("match","skip","clear"))
        require((action=="match")== (activityId!=null))
        activityId?.let { require(Regex("[a-f0-9]{64}").matches(it)) }
        require(expectedSelectionGeneration>=0 && Regex("[a-f0-9]{64}").matches(expectedDigest))
        Instant.parse(asOf)
    }
    override fun toString()="PlanReconciliationConfirmationRequest([REDACTED])"
}
@Serializable data class PlanReconciliationReceipt(val confirmation: PlanMatchConfirmation, val replayed: Boolean)

data class CapturedPlanReconciliation(val selection: PlanSelection, val plan: AuthoredPlan?,
    val activities: List<ReconciliationActivity>, val confirmations: List<PlanMatchConfirmation>,
    val coverage: List<CategoryStatus>, val totalChanges: Long,
    val records: Map<String,ActivityRecord> = emptyMap(),val effortReports: List<ActivityEffortReport> = emptyList()) {
    override fun toString()="CapturedPlanReconciliation([REDACTED])"
}

interface PlanReconciliationRepository {
    fun capturePlanReconciliation(at: Instant): CapturedPlanReconciliation
    fun finishPlanReconciliation(capture: CapturedPlanReconciliation,view: PlanReconciliationView): PlanReconciliationView
    fun reconciliationReceipt(request: PlanReconciliationConfirmationRequest): PlanReconciliationReceipt?
    fun confirmPlanReconciliation(request: PlanReconciliationConfirmationRequest, capture: CapturedPlanReconciliation,
        activity: ReconciliationActivity?, clock: java.time.Clock): PlanReconciliationReceipt
}

internal fun validatePlanConfirmation(request: PlanReconciliationConfirmationRequest,capture: CapturedPlanReconciliation,
    activity: ReconciliationActivity?,at: Instant) {
    if(capture.selection.generation!=request.expectedSelectionGeneration || capture.selection.selected!=request.plan || capture.plan?.reference!=request.plan) throw PlanConflict()
    val session=capture.plan.definition.sessions.singleOrNull { it.sessionId==request.sessionId } ?: throw PlanConflict()
    if(session.omitted && request.action!="clear") throw PlanConflict()
    val heads=capture.confirmations.filter { it.plan.planId==request.plan.planId }.associateBy { it.sessionId }
    if(heads[session.sessionId]?.confirmationId!=request.expectedConfirmationId) throw PlanConflict()
    if(Instant.parse(request.asOf)>at) throw PlanConflict()
    when(request.action) {
        "match" -> {
            if(activity==null || activity.activityId!=request.activityId || activity !in capture.activities || activity.sport!=session.sport ||
                activity.startInstant?.let { Instant.parse(it)>at }==true) throw PlanConflict()
            // Manual stable links may resolve unknown timing; never fabricate a measured instant.
            val otherSessions=capture.plan.definition.sessions.map { it.sessionId }.toSet()-session.sessionId
            if(heads.values.any { it.sessionId in otherSessions && it.action=="match" && it.activity?.activityId==activity.activityId }) throw PlanConflict()
        }
        "skip" -> {
            val policy=capture.plan.definition.policy ?: throw PlanConflict()
            if(at<unmatchedAfter(session,policy) || heads[session.sessionId]?.action=="match") throw PlanConflict()
            val start=session.schedule.startInstant();val end=session.schedule.endInstant()
            if(capture.activities.any { record -> record.sport==session.sport && record.verifiedStart()?.let { it>=start && it<end && it<=at }==true }) throw PlanConflict()
        }
        "clear" -> if(heads[session.sessionId]==null || heads[session.sessionId]?.action=="clear") throw PlanConflict()
    }
}

/** Pure conservative bipartite matching. Never infer links by sorting, nearest start, metrics or prose. */
internal data class PlanReconciliationCore(val selection: PlanSelection,val sessions: List<PlanSessionFinding>,
    val activities: List<PlanActivityFinding>,val coverage: List<CategoryStatus>,val supportSha256: String) {
    override fun toString()="PlanReconciliationCore([REDACTED])"
}

internal object PlanReconciler {
    private val json=Json { encodeDefaults=true }
    /** Complete selected-plan support, independent of any display range or review purpose. */
    fun core(at: Instant,capture: CapturedPlanReconciliation): PlanReconciliationCore {
        val plan=capture.plan
        val activities=capture.activities.associateBy { it.activityId }
        require(activities.size==capture.activities.size && activities.size<=50_000)
        val observed=activities.values.mapNotNull { activity -> activity.verifiedStart()?.takeIf { it<=at }?.let { it to activity } }
            .sortedWith(compareBy({it.first},{it.second.activityId}))
        val observedIds=observed.map { it.second.activityId }.toSet()
        val bySport=observed.groupBy { it.second.sport }
        val sessions=plan?.definition?.sessions.orEmpty()
        // Capture supplies append-sequence ordering, not timestamps or UUID ordering.
        val sessionIds=sessions.map { it.sessionId }.toSet()
        val latest=capture.confirmations.filter { it.plan.planId==plan?.reference?.planId && it.sessionId in sessionIds }
            .associateBy { it.sessionId }
        var edgeCount=0
        val candidates=sessions.associate { session ->
            val sameSport=bySport[session.sport].orEmpty()
            val start=session.schedule.startInstant(); val end=session.schedule.endInstant()
            // Binary search avoids sessions × all retained activities scans.
            var lower=0; var upper=sameSport.size
            while(lower<upper) { val middle=(lower+upper)/2; if(sameSport[middle].first<start) lower=middle+1 else upper=middle }
            var until=lower
            if(!session.omitted) while(until<sameSport.size && sameSport[until].first<end) {
                // Bound adjacency before allocating; no candidate/support truncation is permitted.
                if(++edgeCount>MAX_SCOPED_INSPECTION_BYTES/70) throw ScopedEvidenceInspectionLimit()
                until++
            }
            session.sessionId to (lower until until).map { sameSport[it].second.activityId }
        }
        val owners=mutableMapOf<String,MutableList<String>>()
        val reserved=mutableSetOf<String>()
        for(session in sessions) {
            val confirmation=latest[session.sessionId]
            if(confirmation!=null && confirmation.action!="clear") {
                confirmation.activity?.let { reserved.add(it.activityId) }
            } else if(!session.omitted) for(id in candidates.getValue(session.sessionId)) owners.getOrPut(id) { mutableListOf() }.add(session.sessionId)
        }
        val confirmedOwners=latest.values.filter { it.action=="match" }.groupBy { it.activity!!.activityId }
        val candidateOwners=mutableMapOf<String,MutableList<String>>()
        candidates.forEach { (session,ids) -> ids.forEach { candidateOwners.getOrPut(it) { mutableListOf() }.add(session) } }
        latest.values.mapNotNull { c -> c.activity?.activityId?.let { it to c.sessionId } }.forEach { (id,session) ->
            candidateOwners.getOrPut(id) { mutableListOf() }.add(session)
        }
        val findings=sessions.map { session ->
            val confirmation=latest[session.sessionId]
            val ids=candidates.getValue(session.sessionId)
            val boundary=plan!!.definition.policy?.let { unmatchedAfter(session,it).toString() }
            var state: String; var basis: String; var linked: String?=null
            when {
                session.omitted -> {state="omitted";basis="authored_omission"}
                confirmation!=null && confirmation.action!="clear" -> {
                    val current=confirmation.plan==plan.reference && (confirmation.activity==null ||
                        activities[confirmation.activity.activityId]==confirmation.activity &&
                            (confirmation.activity.verifiedStart()?.let { it<=at } ?: true))
                    when {
                        !current -> {state="needs_confirmation";basis="confirmation_evidence_changed"}
                        confirmation.action=="skip" && ids.isNotEmpty() -> {state="needs_confirmation";basis="recorded_evidence_contradicts_confirmed_skip"}
                        confirmation.action=="skip" -> {state="confirmed_skipped";basis="user_confirmation"}
                        confirmedOwners[confirmation.activity!!.activityId]!!.size!=1 -> {state="needs_confirmation";basis="confirmation_collision"}
                        else -> {state="matched";basis=if(confirmation.activity.verifiedStart()==null) "user_confirmation_timing_unverified" else "user_confirmation";linked=confirmation.activity.activityId}
                    }
                }
                ids.size==1 && ids.single() !in reserved && owners[ids.single()]?.size==1 -> {
                    state="matched";basis="compatible_unique_evidence";linked=ids.single()
                }
                ids.isNotEmpty() -> {state="ambiguous";basis="competing_candidates_or_confirmed_ownership"}
                boundary==null -> {state="timing_unavailable";basis="plan_policy_required"}
                at>=Instant.parse(boundary) -> {state="provisional_unmatched";basis="local_evidence_absent_not_proof_of_skip"}
                else -> {state="awaiting_evidence";basis="session_end_plus_grace_not_reached"}
            }
            val finding=PlanSessionFinding(session.sessionId,session.sport,session.objective,session.schedule,state,basis,linked,ids,boundary,confirmation)
            finding.copy(targetChecks=PlannedTargetChecks.evaluate(plan,session,finding,linked?.let(activities::get),linked?.let(capture.records::get),capture.effortReports))
        }
        val linkedOwners=findings.filter { it.activityId!=null }.groupBy { it.activityId!! }
        val activityFindings=activities.values.sortedBy { it.activityId }.map { activity ->
            val links=linkedOwners[activity.activityId].orEmpty().map { it.sessionId }
            val competitors=candidateOwners[activity.activityId].orEmpty()
            val historical=activity.activityId in observedIds
            PlanActivityFinding(activity,when {
                plan==null -> "base_plan_required"
                links.isNotEmpty() -> "matched"
                !historical -> "needs_confirmation"
                competitors.isNotEmpty() -> "needs_confirmation"
                else -> "extra_relative_to_selected_plan"
            },(links+competitors).distinct().sorted())
        }
        val support=AnalysisClaims.hash(json.encodeToString(capture.selection)+json.encodeToString(plan)+
            json.encodeToString(capture.activities)+json.encodeToString(capture.confirmations)+json.encodeToString(capture.coverage)+
            json.encodeToString(capture.effortReports)+"authored_inclusive_range_v1")
        return PlanReconciliationCore(capture.selection,findings,activityFindings,capture.coverage,support)
    }

    fun reconcile(query: PlanReconciliationQuery,at: Instant,capture: CapturedPlanReconciliation): PlanReconciliationView {
        val all=core(at,capture)
        val start=LocalDate.parse(query.oldest).atStartOfDay(ZoneId.of(query.timeZone)).toInstant()
        val end=LocalDate.parse(query.newest).plusDays(1).atStartOfDay(ZoneId.of(query.timeZone)).toInstant()
        val visible=all.sessions.filter { it.schedule.startInstant()<end && it.schedule.endInstant()>start }
        val referenced=visible.flatMap { it.candidateActivityIds+listOfNotNull(it.activityId,it.confirmation?.activity?.activityId) }.toSet()
        val activityFindings=all.activities.filter { finding -> finding.activity.activityId in referenced || finding.activity.let { activity ->
            activity.verifiedStart()?.let { it>=start && it<end } ?: (activity.startLocal.take(10) in query.oldest..query.newest)
        } }
        val view=PlanReconciliationView(query,at.toString(),all.selection,"",all.supportSha256,visible,activityFindings,all.coverage)
        val encoded=json.encodeToString(view)
        if(encoded.toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
        return view.copy(digest=AnalysisClaims.hash(encoded))
    }
}
