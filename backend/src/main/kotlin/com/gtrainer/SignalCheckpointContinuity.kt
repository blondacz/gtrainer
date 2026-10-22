package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A complete candidate-set binding, not just selected values. Empty dates are bound by the interval. */
@Serializable
internal data class SignalCandidateBinding(val selector: PersonalSignalSelector,val oldest: String,val newest: String,
    val sha256: String,val format: String="signal_candidates_v1") {
    init {
        signalWatch(oldest,newest)
        require(sha256.matches(Regex("[a-f0-9]{64}")) && format=="signal_candidates_v1")
    }
    override fun toString()="SignalCandidateBinding([REDACTED])"
}

@Serializable
internal data class SignalResetAnchor(val resetStartedOn: String,val resetSatisfiedOn: String,val timeZone: String,
    val evaluator: String="personal_signal_v1") {
    init {
        validateContextDate(resetStartedOn);validateContextDate(resetSatisfiedOn)
        require(resetStartedOn<=resetSatisfiedOn && (timeZone=="UTC" || timeZone in ZoneId.getAvailableZoneIds()) && evaluator=="personal_signal_v1")
    }
    override fun toString()="SignalResetAnchor([REDACTED])"
}

/** The watch includes ALL causal support since the anchor. It may expand, never silently slide. */
@Serializable
internal data class SignalReplayCheckpoint(val state: PersonalSignalState,val anchor: SignalResetAnchor?,
    val binding: SignalCandidateBinding,val mutationFrontier: Long,val removalGeneration: Long) {
    init {
        require(mutationFrontier>=0 && removalGeneration>=0 && binding.newest==state.date)
        require(!state.projected.known && !state.beforeDay.known || anchor!=null)
        anchor?.let { require(it.resetStartedOn>=binding.oldest && it.resetSatisfiedOn<=state.date) }
        if(state.beforeDay.known) require(requireNotNull(anchor).resetSatisfiedOn<state.date)
    }
    override fun toString()="SignalReplayCheckpoint([REDACTED])"
}

/** Survives invalidated checkpoints, changed policy, purging source copies and queue-history eviction. */
@Serializable
internal data class SignalAdmissionHighWater(val ruleId: String,val episodeSequence: Long,val admittedUtc: String,
    val preservedCooldownUntilUtc: String,val evaluatedOn: String,val evaluationTimeZone: String) {
    init {
        validateContextId(ruleId);require(episodeSequence>0)
        require(Instant.parse(preservedCooldownUntilUtc)>=Instant.parse(admittedUtc))
        validateContextDate(evaluatedOn)
        require(evaluationTimeZone=="UTC" || evaluationTimeZone in ZoneId.getAvailableZoneIds())
        require(Instant.parse(admittedUtc).atZone(ZoneId.of(evaluationTimeZone)).toLocalDate().toString()==evaluatedOn)
    }
    fun cooldownUntil(current: PersonalSignalConfiguration): Instant = maxOf(Instant.parse(preservedCooldownUntilUtc),
        Instant.parse(admittedUtc).plusSeconds(current.cooldownSeconds))
    fun resetCanReanchor(anchor: SignalResetAnchor): Boolean {
        val resetStart=LocalDate.parse(anchor.resetStartedOn).atStartOfDay(ZoneId.of(anchor.timeZone)).toInstant()
        val admittedDayEnd=LocalDate.parse(evaluatedOn).plusDays(1).atStartOfDay(ZoneId.of(evaluationTimeZone)).toInstant()
        return resetStart>=admittedDayEnd
    }
    override fun toString()="SignalAdmissionHighWater([REDACTED])"
}

internal data class ValidatedSignalContinuation(val status: String,val state: PersonalSignalState?) {
    override fun toString()="ValidatedSignalContinuation([REDACTED])"
}

private fun signalWatch(oldest: String,newest: String) {
    validateContextDate(oldest);validateContextDate(newest)
    require(ChronoUnit.DAYS.between(LocalDate.parse(oldest),LocalDate.parse(newest)) in 0..731)
}

internal object SignalCheckpointContinuity {
    private val json=ScopedEvidencePreparation.json

    /** Capture all raw candidates, including incompatible identities and multiplicity, before selection. */
    fun bind(selector: PersonalSignalSelector,oldest: String,newest: String,samples: List<ScopedSignalSample>,at: Instant): SignalCandidateBinding {
        signalWatch(oldest,newest);require(samples.size<=50_000)
        samples.forEach { sample ->
            validateContextDate(sample.date);require(sample.value.isFinite() && sample.value>=0 && sample.evidenceId.isNotBlank() &&
                (sample.revision==null || sample.revision>0))
            sample.receivedUtc?.let { require(Instant.parse(it)<=at) }
        }
        val candidates=samples.filter { it.date in oldest..newest && it.identity.field==selector.field && it.identity.intermediary==selector.intermediary }
            .map { json.encodeToString(it) }.sorted()
        val encoded=json.encodeToString(listOf(json.encodeToString(selector),oldest,newest,json.encodeToString(candidates)))
        require(encoded.toByteArray(Charsets.UTF_8).size<=8*1024*1024) { "Signal candidate binding exceeds the byte budget" }
        return SignalCandidateBinding(selector,oldest,newest,AnalysisClaims.hash(encoded))
    }

    /** Only a matching complete causal watch may bridge an unrelated global source revision. */
    fun validate(checkpoint: SignalReplayCheckpoint,rule: PersonalSignalRule,currentSourceRevision: Long,
        mutations: SignalMutationCoverage,currentBinding: SignalCandidateBinding,at: Instant): ValidatedSignalContinuation {
        require(currentSourceRevision>=0)
        val config=rule.configuration
        val state=checkpoint.state
        fun refuse()=ValidatedSignalContinuation("needs_reevaluation",null) // Never expose an old known state for fallback.
        if(!rule.enabled || config==null || state.ruleId!=rule.ruleId || state.ruleRevision!=rule.revision ||
            checkpoint.binding.selector!=config.selector || checkpoint.anchor?.timeZone?.let { it!=config.timeZone }==true ||
            state.date>at.atZone(ZoneId.of(config.timeZone)).toLocalDate().toString() || currentSourceRevision<state.sourceRevision ||
            state.projected.lastEmittedUtc?.let { Instant.parse(it)>at }==true) return refuse()
        checkpoint.anchor?.let { anchor ->
            if(ChronoUnit.DAYS.between(LocalDate.parse(anchor.resetStartedOn),LocalDate.parse(anchor.resetSatisfiedOn))+1!=config.resetPersistenceDays.toLong() ||
                LocalDate.parse(checkpoint.binding.oldest)>LocalDate.parse(anchor.resetStartedOn).minusDays(config.baseline.trailingDays.toLong())) return refuse()
        }
        if(mutations.after!=checkpoint.mutationFrontier || mutations.expectedRemovalGeneration!=checkpoint.removalGeneration ||
            !mutations.complete() || mutations.affects(config.selector.field,config.selector.intermediary,
                checkpoint.binding.oldest,checkpoint.binding.newest) || currentBinding!=checkpoint.binding) return refuse()
        return ValidatedSignalContinuation("validated",state.copy(sourceRevision=currentSourceRevision))
    }

    /** A proven reset, including every persistence day, is required to establish a new causal anchor. */
    fun resetAnchor(report: PersonalSignalReport,highWater: SignalAdmissionHighWater?): SignalResetAnchor? {
        val config=report.rule.configuration ?: return null
        if(!report.rule.enabled) return null
        require(highWater==null || highWater.ruleId==report.rule.ruleId)
        return report.days.asReversed().firstNotNullOfOrNull { day ->
            if(day.status!="reset" || day.resetCount<config.resetPersistenceDays) return@firstNotNullOfOrNull null
            val first=LocalDate.parse(day.date).minusDays(config.resetPersistenceDays.toLong()-1).toString()
            if(first<report.query.oldest) return@firstNotNullOfOrNull null
            val anchor=SignalResetAnchor(first,day.date,config.timeZone)
            anchor.takeIf { highWater?.resetCanReanchor(it)!=false }
        }
    }
}
