package com.gtrainer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.encodeToString

/** Adapter-owned evidence authority, never configured by a selector or public request. */
internal fun interface SignalEvidenceAdapter {
    fun adapt(samples: List<ScopedSignalSample>): List<ScopedSignalSample>
}

internal data class StoredSignalReplay(val generation: Long,val checkpoint: SignalReplayCheckpoint?,val highWater: SignalAdmissionHighWater?) {
    override fun toString()="StoredSignalReplay([REDACTED])"
}

internal data class CapturedSignalReplay(val signal: CapturedPersonalSignal,val stored: StoredSignalReplay,
    val watchSamples: List<ScopedSignalSample>,val mutations: SignalMutationCoverage,val oldBinding: SignalCandidateBinding?,val capturedOldest: String,
    val history: HistoryResponse = HistoryResponse(emptyList(),emptyList()),val provenance: List<ScopedImportProvenance> = emptyList()) {
    override fun toString()="CapturedSignalReplay([REDACTED])"
}

internal data class PreparedSignalReplay(val capture: CapturedSignalReplay,val report: PersonalSignalReport,
    val transition: PersonalSignalTransition,val checkpoint: SignalReplayCheckpoint?,val status: String) {
    override fun toString()="PreparedSignalReplay([REDACTED])"
}

internal object SignalReplay {
    fun prepare(capture: CapturedSignalReplay): PreparedSignalReplay {
        val signal=capture.signal
        val rule=signal.rule
        val config=rule.configuration
        val report=PersonalSignalRules.evaluate(rule,signal.query,signal.at,signal.samples)
        fun unknown(status: String): PreparedSignalReplay {
            val episode=PersonalSignalEpisode(lastEmittedUtc=capture.stored.highWater?.admittedUtc)
            val state=PersonalSignalState(rule.ruleId,rule.revision,signal.query.newest,episode,episode,signal.sourceRevision)
            return PreparedSignalReplay(capture,report,PersonalSignalTransition(status,state,null),null,status)
        }
        if(!rule.enabled || config==null) return unknown("disabled")
        val today=signal.at.atZone(ZoneId.of(config.timeZone)).toLocalDate().toString()
        if(signal.query.newest!=today) return unknown("historical_inspection_only")
        val water=capture.stored.highWater
        if(water?.let { signal.at<Instant.parse(it.admittedUtc) }==true) return unknown("clock_before_admission")
        val old=capture.stored.checkpoint
        val continued=if(old!=null && capture.oldBinding!=null)
            SignalCheckpointContinuity.validate(old,rule,signal.sourceRevision,capture.mutations,capture.oldBinding,signal.at).state else null
        var transition=continued?.let { PersonalSignalTransitions.advance(report,it,signal.sourceRevision,signal.at) }
        var anchor=old?.anchor?.takeIf { continued!=null && transition?.status!="needs_reevaluation" }
        var first=if(anchor!=null) {
            val firstNew=if(continued!!.date<today) LocalDate.parse(continued.date).plusDays(1) else LocalDate.parse(today)
            minOf(LocalDate.parse(old!!.binding.oldest),firstNew.minusDays((maxOf(config.triggerPersistenceDays,config.resetPersistenceDays)-1).toLong()+config.baseline.trailingDays))
        } else null
        // Fresh verified reset is the only way to replace missing/oversized causal proof.
        // In particular, an old pre-admission reset cannot recreate an unalerted episode.
        if(anchor==null || first==null || ChronoUnit.DAYS.between(first,LocalDate.parse(today))>731) {
            anchor=SignalCheckpointContinuity.resetAnchor(report,water) ?: return unknown(
                if(old!=null && ChronoUnit.DAYS.between(LocalDate.parse(old.binding.oldest),LocalDate.parse(today))>731) "causal_watch_unavailable" else "unknown_prior_state")
            transition=PersonalSignalTransitions.reanchor(report,anchor,water,signal.sourceRevision,signal.at)
            first=LocalDate.parse(anchor.resetStartedOn).minusDays(config.baseline.trailingDays.toLong())
        }
        val state=transition?.state ?: return unknown("needs_reevaluation")
        if(first.year !in 1..9999 || first.toString()<capture.capturedOldest || ChronoUnit.DAYS.between(first,LocalDate.parse(today)) !in 0..731) return unknown("causal_watch_unavailable")
        val binding=try { SignalCheckpointContinuity.bind(config.selector,first.toString(),today,capture.watchSamples,signal.at) }
            catch(_: IllegalArgumentException) { return unknown("causal_watch_unavailable") }
        var finalTransition=requireNotNull(transition)
        if(finalTransition.candidateEpisodeId!=null && water!=null && signal.at<water.cooldownUntil(config))
            finalTransition=finalTransition.copy(status="cooldown_wait",candidateEpisodeId=null)
        val checkpoint=SignalReplayCheckpoint(state,anchor,binding,capture.mutations.through,capture.mutations.removalGeneration)
        require(ScopedEvidencePreparation.json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8).size<=8*1024*1024)
        return PreparedSignalReplay(capture,report,finalTransition,checkpoint,finalTransition.status)
    }
}
