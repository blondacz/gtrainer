package com.gtrainer

import java.time.Instant
import kotlinx.serialization.encodeToString

internal data class CapturedSignalAdaptation(val root: CapturedAdaptationEvidence,val replay: List<CapturedSignalReplay>,val schedule: ReviewSchedulerState,
    val revisions: ScopedRoutingRevisions) {
    override fun toString()="CapturedSignalAdaptation([REDACTED])"
}

/** Prepared only from one coherent owner capture. No acknowledgment occurs during preparation. */
internal class PreparedSignalAdaptation private constructor(val capture: CapturedSignalAdaptation,val signals: List<PreparedSignalReplay>,
    val observations: List<AdaptationReasonObservation>,val heads: List<AdaptationReasonHead>,val work: AdaptationWorkVersion?,val intent: ScopedQueueIntent?,
    val candidates: Set<String>,val validUntil: Instant,val projection: PreparedAdaptationProjection,val supports: List<AdaptationReasonSupport>) {
    companion object {
        private val json=ScopedEvidencePreparation.json
        private fun hash(parts: List<String>)=AnalysisClaims.hash(json.encodeToString(parts))
        fun reasonId(ruleId: String,sequence: Long)=hash(listOf("personal_signal",ruleId,sequence.toString()))

        fun validUntil(root: CapturedAdaptationEvidence,signals: List<PreparedSignalReplay>): Instant {
            val deadlines=signals.filter { it.status=="cooldown_wait" }.mapNotNull { signal ->
                val config=signal.capture.signal.rule.configuration ?: return@mapNotNull null
                signal.capture.stored.highWater?.cooldownUntil(config)?.takeIf { it>root.at }
            }
            return (deadlines+AdaptationTemporalValidity.until(root,root.timeZone)).min()
        }

        fun prepare(capture: CapturedSignalAdaptation): PreparedSignalAdaptation {
            val root=capture.root
            require(capture.replay.size<=20 && capture.replay.map { it.signal }==root.signals)
            val projection=AdaptationProjection.prepare(root)
            val signals=capture.replay.map(SignalReplay::prepare)
            val candidates=mutableSetOf<String>()
            val signalObservations=signals.flatMap { prepared ->
                val rule=prepared.capture.signal.rule
                val water=prepared.capture.stored.highWater
                val oldSequence=water?.episodeSequence ?: 0
                val candidate=prepared.transition.candidateEpisodeId!=null && oldSequence<Long.MAX_VALUE && projection.family!=null && projection.constraintChanges.available
                val sequence=if(candidate) oldSequence+1 else oldSequence
                if(sequence==0L) emptyList() else {
                    fun observation(seq: Long,condition: String): AdaptationReasonObservation {
                        val id=reasonId(rule.ruleId,seq)
                        val dependency=hash(listOf(json.encodeToString(rule),json.encodeToString(prepared.report.days),
                            json.encodeToString(prepared.report.support),json.encodeToString(prepared.checkpoint?.binding)))
                        return AdaptationReasonObservation(id,"personal_signal",hash(listOf("signal_subject",rule.ruleId,seq.toString())),condition,
                            hash(listOf("signal_episode",rule.ruleId,seq.toString())),dependency)
                    }
                    val condition=when {
                        candidate -> "active"
                        prepared.checkpoint==null -> "unevaluable"
                        prepared.report.days.lastOrNull()?.status=="reset" -> "clear"
                        prepared.report.days.lastOrNull()?.status=="trigger" && prepared.transition.state?.projected?.emitted==true -> "active"
                        else -> "provisional"
                    }
                    val current=observation(sequence,condition)
                    if(candidate) candidates+=current.reasonId
                    listOfNotNull(current,if(candidate && oldSequence>0) observation(oldSequence,"clear") else null)
                }
            }.sortedBy { it.reasonId }
            val supports=AdaptationSupports.project(root,projection)
            val current=projection.observations+signalObservations
            val present=current.map { it.reasonId }.toSet()
            val retained=supports.map { it.reasonId }.toSet()
            val missing=root.reasonHeads.filter { it.reasonId in retained && it.reasonId !in present }.map { previous ->
                AdaptationReasonObservation(previous.reasonId,previous.kind,previous.subjectId,"unevaluable",previous.materialSha256,
                    hash(listOf("missing_current_support",previous.reasonId)))
            }
            val observations=(current+missing).sortedBy { it.reasonId }
            require(observations.map { it.reasonId }.distinct().size==observations.size)
            if(projection.constraintChanges.available) candidates+=projection.observations.filter { it.condition=="active" }.map { it.reasonId }
            val old=root.reasonHeads.associateBy { it.reasonId }
            val heads=observations.map { AdaptationReasons.project(old[it.reasonId],it) }
            val family=projection.family
            val active=heads.filter { it.condition=="active" }
            require(active.size<=1000) { "Adaptation reason set exceeds the queue payload budget" }
            val work=if(family==null || !projection.constraintChanges.available) null else AdaptationWorkVersion(family,requireNotNull(projection.horizon),
                heads.filter { it.kind!="personal_signal" || it.condition!="clear" }.map { AdaptationReasonRef(it.reasonId,it.revision,it.dependencySha256) },
                signals.map { AdaptationPolicyRef("personal_signal",it.capture.signal.rule.ruleId,it.capture.signal.rule.revision) }+
                    projection.workload.map { AdaptationPolicyRef("extra_workload",it.rule.ruleId,it.rule.revision) },
                AdaptationDependencies.digest(root,projection,signals))
            val intent=work?.takeIf { active.isNotEmpty() }?.let {
                ScopedQueueIntent(ScopedQueueIntent.PROFILE,"adaptation",capture.schedule.version,
                    ReviewScopeResolver.resolve(ScopedReviewRequest(scope="day",wellnessPolicy="assessment"),root.at,family!!.timeZone),
                    root.sourceRevision,capture.revisions.planReason,adaptation=
                        AdaptationQueuePayload(it.family,it.key(),root.reconciliation.selection.generation,active.map { head -> head.reasonId }.sorted(),it))
            }
            return PreparedSignalAdaptation(capture,signals,observations,heads,work,intent,candidates,validUntil(root,signals),projection,supports)
        }
    }
    override fun toString()="PreparedSignalAdaptation([REDACTED])"
}

internal data class SignalAdaptationAdmission(val admittedReasonIds: List<String>,val jobId: String?,val signalStatuses: List<String>) {
    override fun toString()="SignalAdaptationAdmission([REDACTED])"
}
