package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class ScopedAdditionalText(val content: String, val authorAttribution: String) {
    init { validateContextText(content,4000,true); validateContextText(authorAttribution,160,true) }
    override fun toString()="ScopedAdditionalText([REDACTED])"
}

/** Explicit inert preparation, not an execution request or persistent memory command. */
@Serializable
data class ScopedEvidenceRequest(
    val schemaVersion: Int = 2, val selection: ScopedReviewRequest,
    val purpose: String, val question: String? = null, val additionalText: ScopedAdditionalText? = null,
    val baselines: List<ScopedBaselineSelection> = emptyList(),
) {
    init {
        require(schemaVersion==2)
        require(purpose in setOf("morning","evening","activity_reflection","period_reflection","question","adaptation"))
        question?.let { validateContextText(it,2000,true) }
        require(purpose!="question" || question!=null)
        require(baselines.size<=20 && baselines.distinct().size==baselines.size)
        require(baselines.isEmpty() || (selection.wellnessPolicy ?: if(selection.mode=="period") "assessment" else "off")=="assessment")
    }
    override fun toString()="ScopedEvidenceRequest([REDACTED])"
}

@Serializable
data class ScopedSourceBinding(val intakeId: String, val linkedRevision: Int, val currentRevision: Int)

@Serializable
data class ScopedPlanEvidence(val selection: PlanSelection, val plan: PlanRef?, val author: PlanAuthor?,
    val title: String?, val policy: PlanPolicy?, val sessions: List<PlannedSession>) {
    override fun toString()="ScopedPlanEvidence([REDACTED])"
}

@Serializable
data class ScopedEvidencePacket(
    val profile: String = "scoped-descriptive-v1", val request: ScopedEvidenceRequest, val facts: ScopedEvidenceFacts,
    val context: ScopedContextRetrievalResult, val contextLinks: List<ContextIntakeLink>,
    val sourceBindings: List<ScopedSourceBinding>, val commitments: List<CommitmentCalendar>,
    val plan: ScopedPlanEvidence, val planningHorizon: ScopedDateRange?,
    val rulePolicy: String = "not_configured", val deterministicReasons: List<String> = emptyList(),
    val admission: String = "closed_not_qualified_not_execution_bound",
    val omitted: ScopedEvidenceOmissions = ScopedEvidenceOmissions(),
    val baselines: List<ScopedWellnessBaseline> = emptyList(),
    val latestAvailableWellness: ScopedLatestWellnessContext? = null,
    val adaptation: AdaptationFacts? = null,
) { override fun toString()="ScopedEvidencePacket([REDACTED])" }

/** Internal local inputs; never a provider DTO. totalChanges is a publication race fence, not retained evidence. */
internal data class CapturedScopedEvidence(
    val read: CapturedScopedRead, val contexts: List<ContextLifecycleView>, val links: List<ContextIntakeLink>,
    val sourceHeads: Map<String,Int>, val commitments: List<ConfirmedCommitment>,
    val selection: PlanSelection, val plan: AuthoredPlan?, val totalChanges: Long,
    val adaptationFacts: AdaptationFacts? = null,
    val adaptationReference: CapturedAdaptationReference? = null,
) { override fun toString()="CapturedScopedEvidence([REDACTED])" }

internal object ScopedEvidencePreparation {
    val json=Json { encodeDefaults=true }

    fun packet(request: ScopedEvidenceRequest, captured: CapturedScopedEvidence, report: ScopedTrendReport): ScopedEvidencePacket {
        captured.adaptationReference?.requireCurrent()
        val scope=captured.read.scope
        require(request.selection==scope.request)
        val today=Instant.parse(scope.asOf).atZone(ZoneId.of(scope.timeZone)).toLocalDate()
        val future=request.purpose in setOf("morning","evening","adaptation")
        val horizon=if(future) captured.plan?.definition?.policy?.horizon(Instant.parse(scope.asOf))?.let {
            ScopedDateRange(it.first.toString(),it.second.toString()).also { r -> r.range() }
        } else null
        val evidenceTargets=if(scope.request.mode=="activities") report.sessions.map {
            val date=LocalDate.parse(it.record.startLocal.take(10))
            ContextRetrievalTarget(date,date,it.record.sport,it.activityId)
        } else (listOfNotNull(scope.evidenceRange)+if(scope.request.intent=="compare") listOfNotNull(
            scope.comparison.comparablePrevious,scope.comparison.comparisonFull,scope.comparison.priorCalendarFull) else emptyList())
            .distinct().map { ContextRetrievalTarget(LocalDate.parse(it.oldest),LocalDate.parse(it.newest),scope.request.sport) }
        // Keep the combined target count bounded, including all explicitly selected subjects.
        val amendmentTargets=horizon?.let { listOf(ContextRetrievalTarget(LocalDate.parse(it.oldest),LocalDate.parse(it.newest))) } ?: emptyList()
        val contexts=ScopedContextRetrievalResult(
            retrieveScopedAthleteContext(captured.contexts,ScopedContextRetrievalQuery(today,
                evidenceTargets,optionalLimit=20,mandatoryLimit=1000)).evidence,
            retrieveScopedAthleteContext(captured.contexts,ScopedContextRetrievalQuery(today,
                emptyList(),amendmentTargets,optionalLimit=20,mandatoryLimit=1000)).amendments)
        val usedContexts=(listOf(contexts.evidence,contexts.amendments).flatMap {
            it.mandatoryRestrictions+it.mandatoryClarifications.map { view -> view.context }+it.optionalContexts.map { view -> view.context }
        }).map { it.contextId to it.revision }.toSet()
        val links=captured.links.filter { it.contextId to it.contextRevision in usedContexts }
        // Project each distinct range separately, never across intervening selected-activity dates.
        val evidenceDates=evidenceTargets.map { ScopedDateRange(it.oldest.toString(),it.newest.toString()) to scope.timeZone }
        val futureDates=horizon?.let { listOf(it to requireNotNull(captured.plan?.definition?.policy).adaptationTimeZone) }.orEmpty()
        val dates=(evidenceDates+futureDates).distinct().groupBy { it.second }.flatMap { (zone,entries) ->
            entries.map { it.first }.sortedWith(compareBy({it.oldest},{it.newest}))
                .fold(mutableListOf<ScopedDateRange>()) { ranges,next ->
                    val previous=ranges.lastOrNull()
                    if(previous!=null && next.oldest<=previous.newest &&
                        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(previous.oldest),LocalDate.parse(maxOf(previous.newest,next.newest)))<366)
                        ranges[ranges.lastIndex]=ScopedDateRange(previous.oldest,maxOf(previous.newest,next.newest))
                    else ranges.add(next)
                    ranges
                }.map { it to zone }
        }
        var projectionWork=0
        val seenOccurrences=mutableSetOf<String>()
        val calendars=dates.map { (range,zone) ->
            projectCommitments(captured.commitments,range.oldest,range.newest,zone) {
                if(++projectionWork>50_000) throw CommitmentProjectionLimit()
            }.let { calendar -> calendar.copy(occurrences=calendar.occurrences.filter { seenOccurrences.add(it.occurrenceId) }) }
        }
        val usedCommitments=calendars.flatMap { c -> c.occurrences.map { it.reference }+c.needsReconfirmation }
        val sources=(links.map { it.intakeId to it.intakeRevision }+usedCommitments.map { it.intakeId to it.intakeRevision }).distinct()
            .sortedWith(compareBy({it.first},{it.second})).map { (id,revision) -> ScopedSourceBinding(id,revision,captured.sourceHeads.getValue(id)) }
        val sessions=captured.plan?.definition?.sessions.orEmpty().filter { session ->
            fun overlaps(range: ScopedDateRange,zone: String): Boolean {
                val starts=LocalDate.parse(range.oldest).atStartOfDay(ZoneId.of(zone)).toInstant()
                val ends=LocalDate.parse(range.newest).plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant()
                return starts<ends && session.schedule.startInstant()<ends && session.schedule.endInstant()>starts
            }
            evidenceTargets.any { target ->
                (target.sport==null || target.sport==session.sport) &&
                    overlaps(ScopedDateRange(target.oldest.toString(),target.newest.toString()),scope.timeZone)
            } || futureDates.any { (range,zone) -> overlaps(range,zone) }
        }
        var baselineWork=0
        return ScopedEvidencePacket(request=request,facts=ScopedReviewEvidence.assemble(captured.read,report),context=contexts,
            contextLinks=links,sourceBindings=sources,commitments=calendars,
            plan=ScopedPlanEvidence(captured.selection,captured.plan?.reference,captured.plan?.definition?.author,
                captured.plan?.definition?.title,captured.plan?.definition?.policy,sessions),planningHorizon=horizon,
            latestAvailableWellness=if(request.purpose in setOf("morning","evening") && scope.wellnessPolicy=="assessment") ScopedLatestWellness.assemble(captured.read) else null,
            adaptation=captured.adaptationFacts,
            baselines=request.baselines.map { selection ->
                val unit=if(selection.field=="sleepSecs") "seconds" else "ms"
                // Selection is not verification. The current adapter supplies no verified method or basis.
                val identity=ScopedSignalIdentity(selection.field,unit,selection.source,selection.upstreamOrigin)
                val range=selection.range()
                val records=captured.read.history.wellness.filter { it.source==selection.source && it.date>=range.oldest && it.date<=range.newest }
                    .map { record ->
                        if(++baselineWork>50_000) throw TrendSizeLimit()
                        record.copy(measurements=record.measurements.filterKeys { it==selection.field })
                    }
                val samples=ScopedWellnessEvidence.raw(records,captured.read.provenance).filter {
                    it.field==selection.field && it.upstreamSource==selection.upstreamOrigin
                }.map { observation ->
                    val receipt=observation.provenance.singleOrNull()
                    val actual=ScopedSignalIdentity(observation.field,observation.unit,observation.source,observation.upstreamSource)
                    ScopedSignalSample(actual,observation.sourceDate,observation.value,observation.evidenceId,receipt?.receivedUtc,receipt?.revision)
                }
                ScopedWellnessEvidence.baseline(identity,selection.configuration,samples)
            })
    }

    fun digest(packet: ScopedEvidencePacket)=AnalysisClaims.hash(json.encodeToString(packet))

    /** Internal membership/revision binding. Display-only wellness never contributes to packet consent identity. */
    fun binding(packet: ScopedEvidencePacket,captured: CapturedScopedRead): String {
        val eligible=membership(packet.request,captured,includeDisplayOnly=false)
        val records=eligible.copy(activities=eligible.activities.sortedWith(compareBy({it.startLocal},{it.source},{it.sourceRecordId})))
        val keys=records.activities.map { Triple("activities",it.source,it.sourceRecordId) }.toSet()+
            records.wellness.map { Triple("wellness",it.source,it.sourceRecordId) }.toSet()
        val receipts=captured.provenance.filter { Triple(it.category,it.source,it.sourceRecordId) in keys }
            .sortedWith(compareBy({it.category},{it.source},{it.sourceRecordId}))
        return AnalysisClaims.hash(json.encodeToString(listOf(digest(packet),
            AnalysisClaims.hash(json.encodeToString(records)),AnalysisClaims.hash(json.encodeToString(receipts)))))
    }

    fun membership(request: ScopedEvidenceRequest,captured: CapturedScopedRead,includeDisplayOnly: Boolean): HistoryResponse {
        val selected=ScopedReviewEvidence.membership(captured,includeDisplayOnly)
        val baselines=captured.history.wellness.filter { record -> request.baselines.any { it.matches(record) } }
        val latest=if(request.purpose in setOf("morning","evening") && captured.scope.wellnessPolicy=="assessment") {
            val ids=ScopedLatestWellness.assemble(captured).days.flatMap { it.observations }.map { it.source to it.sourceRecordId }.toSet()
            captured.history.wellness.filter { it.source to it.sourceRecordId in ids }
        } else emptyList()
        return selected.copy(wellness=(selected.wellness+baselines+latest).distinctBy { it.source to it.sourceRecordId }
            .sortedWith(compareBy({it.date},{it.source},{it.sourceRecordId})))
    }
}
