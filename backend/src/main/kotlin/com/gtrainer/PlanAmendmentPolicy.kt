package com.gtrainer

import java.time.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Local owner input, never a provider packet or permission receipt. */
@Serializable
data class PlanAmendmentInputs(val selection: PlanSelection,val plan: AuthoredPlan?,
    val contexts: List<ContextLifecycleView>,val commitments: List<ConfirmedCommitment>,
    val contextLinks: List<ContextIntakeLink> = emptyList(),val sourceHeads: Map<String,Int> = emptyMap()) {
    override fun toString()="PlanAmendmentInputs([REDACTED])"
}

data class CapturedPlanAmendment(val input: PlanAmendmentInputs,val at: Instant,val ownerId: String,
    val totalChanges: Long,val dataVersion: Long) {
    override fun toString()="CapturedPlanAmendment([REDACTED])"
}

@Serializable
data class AmendmentPolicyIssue(val sessionId: String?,val operation: String?,val status: String,val reason: String,
    val sources: List<String> = emptyList(),val attemptedStart: String? = null)

@Serializable
data class PlanAmendmentInspection(val policyVersion: String,val status: String,val basePlan: PlanRef?,
    val selectionGeneration: Long,val asOf: String,val validUntilUtc: String,val horizon: ScopedDateRange?,
    val dependencySha256: String,val candidateSetSha256: String,val candidates: List<AmendmentCandidateV1>,val issues: List<AmendmentPolicyIssue>,
    val coverage: String="authored_boundaries_only_not_exhaustive_no_candidate_is_not_no_change",
    val admission: String="factual_inspection_only_no_inference_proposal_or_plan_change",
    val limitation: String="mechanical_constraints_only_not_medical_suitability;duration_does_not_change_calendar_occupancy;provider_combined_budget_not_certified") {
    override fun toString()="PlanAmendmentInspection([REDACTED])"
}

interface PlanAmendmentRepository {
    fun capturePlanAmendment(at: Instant): CapturedPlanAmendment
    fun finishPlanAmendment(capture: CapturedPlanAmendment,report: PlanAmendmentInspection,clock: Clock): PlanAmendmentInspection
}

/** Pure boundary enumeration and an independent arbitrary-operation policy check. No model or writes. */
internal object PlanAmendmentPolicy {
    const val VERSION="authored_boundary_candidates_v1"
    private val json=ScopedEvidencePreparation.json
    private class Limit(val reason: String): RuntimeException()
    private class Budget {
        private var work=0L
        fun charge(amount: Long=1) { work+=amount;if(work>50_000) throw Limit("candidate_work_overflow") }
    }
    fun binding(input: PlanAmendmentInputs): String {
        val canonical=input.copy(contexts=input.contexts.sortedBy { it.context.contextId },
            commitments=input.commitments.sortedBy { it.reference.commitmentId },
            contextLinks=input.contextLinks.sortedWith(compareBy({it.contextId},{it.contextRevision},{it.intakeId})),sourceHeads=input.sourceHeads.toSortedMap())
        val bytes=json.encodeToString(canonical)
        if(bytes.toByteArray(Charsets.UTF_8).size>8*1024*1024) throw Limit("candidate_input_byte_overflow")
        return AnalysisClaims.hash(VERSION+"\n"+bytes)
    }

    fun enumerate(input: PlanAmendmentInputs,at: Instant): PlanAmendmentInspection {
        require(input.contexts.size<=1000 && input.commitments.size<=1000 && input.plan?.definition?.sessions.orEmpty().size<=1000)
        require(input.selection.selected==input.plan?.reference)
        val plan=input.plan;val policy=plan?.definition?.policy
        val horizon=policy?.horizon(at)?.let { ScopedDateRange(it.first.toString(),it.second.toString()) }
        val candidates=mutableListOf<AmendmentCandidateV1>();val issues=mutableListOf<AmendmentPolicyIssue>()
        var dependency=AnalysisClaims.hash(VERSION+"\nblocked_input_no_authority")
        var globalFailure=false
        try {
            dependency=binding(input)
            if(plan==null) issues+=issue(null,null,"rejected","base_plan_required")
            else if(policy==null) issues+=issue(null,null,"needs_clarification","plan_policy_required")
            else {
                val evaluator=Evaluator(input,at,Budget())
                for(before in plan.definition.sessions.sortedBy { it.sessionId }) {
                    evaluator.budget.charge()
                    if(before.omitted || !(before.permissions.move!=null || before.permissions.reduceDuration!=null || before.permissions.omit)) continue
                    if(at>=amendmentStartCutoff(before,policy)) {
                        issues+=issue(before,"all","rejected","original_session_not_future");continue
                    }
                    fun offer(operation: String,after: PlannedSession) {
                        if(before==after)return
                        val failures=evaluator.check(before,after,operation)
                        if(failures.isNotEmpty()) issues+=failures.map { it.copy(attemptedStart=if(operation=="move_session") after.schedule.date ?: after.schedule.startsLocal else null) } else {
                            val id=AnalysisClaims.hash(json.encodeToString(listOf(VERSION,json.encodeToString(plan.reference),
                                before.sessionId,operation,json.encodeToString(before),json.encodeToString(after))))
                            candidates+=AmendmentCandidateV1(id,before.sessionId,operation,before,after)
                            if(candidates.size>100) throw Limit("candidate_count_overflow")
                        }
                    }
                    before.permissions.move?.let { window ->
                        for(start in listOf(window.earliestStart,window.latestStart).distinct()) {
                            if(start==(before.schedule.date ?: before.schedule.startsLocal))continue
                            val moved=move(before.schedule,start)
                            if(moved==null) issues+=issue(before,"move_session","needs_clarification","destination_time_or_dst_occupancy_uncertain").copy(attemptedStart=start)
                            else offer("move_session",before.copy(schedule=moved))
                        }
                    }
                    before.permissions.reduceDuration?.let { permission ->
                        val target=before.targets.single { it.metric=="duration" && it.basis==permission.basis }
                        for(boundary in listOf(permission.minimumSeconds.toDouble(),target.minimum).distinct()) {
                            val reduced=target.copy(minimum=minOf(target.minimum,boundary),maximum=minOf(target.maximum,boundary))
                            offer("reduce_duration",before.copy(targets=before.targets.map { if(it==target) reduced else it }))
                        }
                    }
                    if(before.permissions.omit) offer("omit_session",before.copy(omitted=true))
                }
                if(json.encodeToString(candidates).toByteArray(Charsets.UTF_8).size>32*1024) throw Limit("candidate_byte_overflow")
            }
        } catch(limit: Limit) {
            candidates.clear();issues.clear();issues+=issue(null,null,"needs_clarification",limit.reason);globalFailure=true
        } catch(_: CommitmentProjectionLimit) {
            candidates.clear();issues.clear();issues+=issue(null,null,"needs_clarification","commitment_projection_overflow");globalFailure=true
        } catch(_: CommitmentTimeUncertain) {
            candidates.clear();issues.clear();issues+=issue(null,null,"needs_clarification","commitment_time_uncertain");globalFailure=true
        }
        val ordered=candidates.sortedWith(compareBy({it.sessionId},{it.operation},{json.encodeToString(it.after)}))
        val status=when {
            globalFailure -> "needs_clarification"
            ordered.isNotEmpty() -> "available"
            issues.any { it.status=="needs_clarification" } -> "needs_clarification"
            else -> "no_candidates"
        }
        return PlanAmendmentInspection(VERSION,status,plan?.reference,input.selection.generation,at.toString(),
            validUntil(input,at,ordered).toString(),horizon,dependency,AnalysisClaims.hash(json.encodeToString(ordered)),ordered,
            issues.distinct().sortedWith(compareBy({it.sessionId},{it.operation},{it.reason})))
    }

    /** Validates an arbitrary result, not membership in the enumerated boundary set. */
    fun checkOperation(input: PlanAmendmentInputs,at: Instant,before: PlannedSession,after: PlannedSession,operation: String): List<AmendmentPolicyIssue> =
        try { Evaluator(input,at,Budget()).check(before,after,operation) }
        catch(limit: Limit) { listOf(issue(before,operation,"needs_clarification",limit.reason)) }
        catch(_: CommitmentProjectionLimit) { listOf(issue(before,operation,"needs_clarification","commitment_projection_overflow")) }
        catch(_: CommitmentTimeUncertain) { listOf(issue(before,operation,"needs_clarification","commitment_time_uncertain")) }

    /** Independent operation checks first, then the combined result. No valid subset is salvaged. */
    fun checkWholeResult(input: PlanAmendmentInputs,at: Instant,selected: List<AmendmentCandidateV1>): List<AmendmentPolicyIssue> {
        if(selected.size>100 || selected.map { it.sessionId }.distinct().size!=selected.size ||
            selected.any { it.sessionId!=it.before.sessionId || it.sessionId!=it.after.sessionId })
            return listOf(issue(null,null,"rejected","invalid_whole_selection"))
        return try {
            val budget=Budget();val evaluator=Evaluator(input,at,budget)
            val failures=selected.flatMap { evaluator.check(it.before,it.after,it.operation) }
            if(failures.isNotEmpty()) failures.distinct() else {
                val changes=selected.associate { it.sessionId to it.after }
                val result=input.plan?.definition?.sessions.orEmpty().map { changes[it.sessionId] ?: it }
                buildList {
                    for(candidate in selected) {
                        val after=candidate.after
                        if(after.omitted)continue
                        val start=after.schedule.startInstant();val end=after.schedule.endInstant()
                        for(other in result) {
                            budget.charge()
                            if(other.sessionId!=after.sessionId && !other.omitted && other.schedule.startInstant()<end && other.schedule.endInstant()>start)
                                add(issue(after,candidate.operation,"rejected","combined_plan_session_overlap",listOf("plan_session/${other.sessionId}")))
                        }
                    }
                }.distinct()
            }
        } catch(limit: Limit) { listOf(issue(null,null,"needs_clarification",limit.reason)) }
        catch(_: CommitmentProjectionLimit) { listOf(issue(null,null,"needs_clarification","commitment_projection_overflow")) }
        catch(_: CommitmentTimeUncertain) { listOf(issue(null,null,"needs_clarification","commitment_time_uncertain")) }
    }

    private fun issue(session: PlannedSession?,operation: String?,status: String,reason: String,sources: List<String> = emptyList())=
        AmendmentPolicyIssue(session?.sessionId,operation,status,reason,sources.sorted())

    private fun move(schedule: PlanSchedule,start: String): PlanSchedule? = try {
        if(schedule.kind=="date_only") schedule.copy(date=start)
        else {
            val from=LocalDateTime.parse(schedule.startsLocal!!);val to=LocalDateTime.parse(schedule.endsLocal!!)
            val newStart=LocalDateTime.parse(start);val newEnd=newStart.plus(Duration.between(from,to))
            val zone=ZoneId.of(schedule.timeZone)
            val startOffset=zone.rules.getValidOffsets(newStart).singleOrNull()
            val endOffset=zone.rules.getValidOffsets(newEnd).singleOrNull()
            if(startOffset==null || endOffset==null) null
            else PlanSchedule("timed",startsLocal=start,endsLocal=newEnd.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")),
                timeZone=schedule.timeZone,startOffset=startOffset.id,endOffset=endOffset.id).takeIf {
                    Duration.between(it.startInstant(),it.endInstant())==Duration.between(schedule.startInstant(),schedule.endInstant())
                }
        }
    } catch(_: IllegalArgumentException) { null } catch(_: DateTimeException) { null }

    fun validUntil(input: PlanAmendmentInputs,at: Instant,candidates: List<AmendmentCandidateV1>): Instant {
        val policy=input.plan?.definition?.policy ?: return at.plusSeconds(86400)
        val midnight=at.atZone(ZoneId.of(policy.adaptationTimeZone)).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of(policy.adaptationTimeZone)).toInstant()
        return (listOf(midnight)+(requireNotNull(input.plan).definition.sessions+candidates.map { it.after }).map { amendmentStartCutoff(it,policy) }.filter { it>at }).min()
    }

    private class Evaluator(val input: PlanAmendmentInputs,val at: Instant,val budget: Budget) {
        private val plan=input.plan
        private val policy=plan?.definition?.policy
        private val zone=policy?.let { ZoneId.of(it.adaptationTimeZone) }
        private val dates=policy?.horizon(at)
        private val sessionsById=plan?.definition?.sessions.orEmpty().associateBy { it.sessionId }
        private val calendar=dates?.let { projectCommitments(input.commitments,it.first.toString(),it.second.toString(),zone!!.id,{budget.charge()}) }

        fun check(before: PlannedSession,after: PlannedSession,operation: String): List<AmendmentPolicyIssue> {
            budget.charge()
            fun denied(reason: String)=listOf(issue(before,operation,"rejected",reason))
            if(plan==null || input.selection.selected!=plan.reference || sessionsById[before.sessionId]!=before) return denied("exact_base_session_required")
            if(policy==null) return listOf(issue(before,operation,"needs_clarification","plan_policy_required"))
            if(before.omitted || at>=amendmentStartCutoff(before,policy) || at>=amendmentStartCutoff(after,policy)) return denied("session_not_future")
            if(before==after) return denied("no_operation")
            val shape=when(operation) {
                "omit_session" -> before.permissions.omit && after==before.copy(omitted=true)
                "move_session" -> {
                    val permission=before.permissions.move;val start=after.schedule.date ?: after.schedule.startsLocal!!
                    permission!=null && after==before.copy(schedule=after.schedule) && before.schedule!=after.schedule &&
                        after.schedule.kind==before.schedule.kind && after.schedule.timeZone==before.schedule.timeZone &&
                        start>=permission.earliestStart && start<=permission.latestStart
                }
                "reduce_duration" -> {
                    val permission=before.permissions.reduceDuration
                    val index=before.targets.indexOfFirst { it.metric=="duration" && it.basis==permission?.basis }
                    if(permission==null || index<0 || after.targets.size!=before.targets.size || after!=before.copy(targets=after.targets)) false
                    else {
                        val old=before.targets[index];val target=after.targets[index]
                        after.targets.withIndex().all { (i,t) -> i==index || t==before.targets[i] } &&
                            target==old.copy(minimum=target.minimum,maximum=target.maximum) &&
                            target.minimum>=permission.minimumSeconds && target.maximum<=permission.maximumSeconds &&
                            target.minimum<=old.minimum && target.maximum<=old.maximum && (target.minimum<old.minimum || target.maximum<old.maximum)
                    }
                }
                else -> false
            }
            if(!shape) return denied("operation_not_permitted_or_unrelated_edit")
            if(operation=="move_session" && after.schedule.kind=="timed") {
                val a=before.schedule;val b=after.schedule;val z=ZoneId.of(b.timeZone)
                if(Duration.between(LocalDateTime.parse(a.startsLocal),LocalDateTime.parse(a.endsLocal))!=Duration.between(LocalDateTime.parse(b.startsLocal),LocalDateTime.parse(b.endsLocal)) ||
                    Duration.between(a.startInstant(),a.endInstant())!=Duration.between(b.startInstant(),b.endInstant()) ||
                    z.rules.getValidOffsets(LocalDateTime.parse(b.startsLocal)).size!=1 || z.rules.getValidOffsets(LocalDateTime.parse(b.endsLocal)).size!=1)
                    return listOf(issue(before,operation,"needs_clarification","destination_time_or_dst_occupancy_uncertain"))
            }
            val horizonStart=dates!!.first.atStartOfDay(zone).toInstant();val horizonEnd=dates.second.plusDays(1).atStartOfDay(zone).toInstant()
            if(listOf(before,after).any { it.schedule.startInstant()<horizonStart || it.schedule.endInstant()>horizonEnd }) return denied("whole_session_outside_horizon")
            val start=after.schedule.startInstant();val end=after.schedule.endInstant()
            val target=ContextRetrievalTarget(start.atZone(zone).toLocalDate(),end.minusNanos(1).atZone(zone).toLocalDate(),after.sport)
            // A planned session has no verified imported activity/review scope. Broaden only for
            // conservative relevance selection, then explicitly block those unknown identities.
            val safety=input.contexts.filter { it.context.category in setOf("restriction","symptom") }
            budget.charge(input.contexts.size.toLong()+safety.size.toLong()*(safety.size-1)/2)
            val broad=safety.map { view ->
                val c=view.context
                val uncertainSport=c.sport!=null && c.sport!=after.sport && c.sport.equals(after.sport,true)
                view.copy(context=c.copy(activityId=null,reviewId=null,sport=if(uncertainSport) after.sport else c.sport))
            }
            val selection=retrieveScopedAthleteContext(broad,ScopedContextRetrievalQuery(at.atZone(zone).toLocalDate(),emptyList(),listOf(target),1000,1000)).amendments
            val out=mutableListOf<AmendmentPolicyIssue>()
            fun unclear(reason: String,sources: List<String>) { out+=issue(before,operation,"needs_clarification",reason,sources) }
            // Shared retrieval owns observation/applicability dates and lifecycle. Even a
            // known symptom interval supplies no mechanically executable training permission.
            val symptoms=(selection.optionalContexts+selection.mandatoryClarifications).filter {
                it.context.category=="symptom" && it.lifecycle.resolution!="resolved"
            }
            val selectedIds=(selection.mandatoryRestrictions.map { it.contextId }+selection.mandatoryClarifications.map { it.context.contextId }+
                symptoms.map { it.context.contextId }).toSet()
            val contextRefs=safety.associate { it.context.contextId to "context/${it.context.contextId}/${it.context.revision}" }
            selection.blockedReasons.forEach { reason -> unclear(reason,selectedIds.sorted().map { contextRefs.getValue(it) }) }
            val relevant=safety.filter { view -> view.context.contextId in selectedIds }
            relevant.filter { it.context.sport!=null && it.context.sport!=after.sport && it.context.sport.equals(after.sport,true) }
                .forEach { unclear("context_sport_identity_needs_confirmation",listOf(contextRefs.getValue(it.context.contextId))) }
            relevant.filter { it.context.activityId!=null || it.context.reviewId!=null }.forEach { unclear("context_scope_identity_unknown",listOf(contextRefs.getValue(it.context.contextId))) }
            symptoms.forEach { unclear("unresolved_symptom_applicability",listOf(contextRefs.getValue(it.context.contextId))) }
            for(restriction in selection.mandatoryRestrictions) {
                val source=listOf(contextRefs.getValue(restriction.contextId))
                when(restriction.restrictionKind) {
                    "blocked_sport" -> if(!after.omitted && restriction.restrictionValue.equals(after.sport,true)) out+=issue(before,operation,"rejected","sport_prohibited",source)
                    "allowed_sport","allowed_activity","blocked_activity","maximum_duration" -> unclear("constraint_execution_meaning_unknown",source)
                    else -> unclear("unstructured_restriction",source)
                }
            }
            val commitments=requireNotNull(calendar)
            budget.charge(input.commitments.size.toLong())
            input.commitments.filter { it.needsReconfirmation || it.sourceChanged || it.contextChanged }.forEach { unclear("commitment_needs_confirmation",listOf("commitment/${it.reference.commitmentId}/${it.reference.revision}")) }
            for(item in commitments.occurrences) {
                budget.charge()
                if(item.kind=="availability") unclear("availability_execution_meaning_unknown",listOf("commitment/${item.reference.commitmentId}/${item.reference.revision}"))
                else if(!after.omitted && Instant.parse(item.startsUtc)<end && Instant.parse(item.endsUtc)>start)
                    out+=issue(before,operation,"rejected","commitment_overlap",listOf("commitment/${item.reference.commitmentId}/${item.reference.revision}"))
            }
            if(!after.omitted) for(other in plan.definition.sessions) {
                budget.charge()
                if(other.sessionId!=before.sessionId && !other.omitted && other.schedule.startInstant()<end && other.schedule.endInstant()>start)
                    out+=issue(before,operation,"rejected","plan_session_overlap",listOf("plan_session/${other.sessionId}"))
            }
            return out.distinct()
        }
    }
}
