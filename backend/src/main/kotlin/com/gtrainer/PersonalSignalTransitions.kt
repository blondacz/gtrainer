package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Content-free episode facts. Unknown history is not assumed recovered or previously unalerted. */
@Serializable
data class PersonalSignalEpisode(val known: Boolean=false,val episodeId: String?=null,
    val emitted: Boolean=false,val lastEmittedUtc: String?=null) {
    init {
        require(!emitted || known && episodeId!=null)
        episodeId?.let { require(it.matches(Regex("[a-f0-9]{64}")) && known) }
        lastEmittedUtc?.let(Instant::parse)
    }
}

/** Today's replaceable projection always starts from beforeDay, never from an earlier projection. */
@Serializable
data class PersonalSignalState(val ruleId: String,val ruleRevision: Int,val date: String,
    val beforeDay: PersonalSignalEpisode,val projected: PersonalSignalEpisode,
    val sourceRevision: Long) {
    init { validateContextId(ruleId);require(ruleRevision in 1..1000 && sourceRevision>=0);validateContextDate(date) }
    override fun toString()="PersonalSignalState([REDACTED])"
}

@Serializable
data class PersonalSignalTransition(val status: String,val state: PersonalSignalState?,val candidateEpisodeId: String?,
    val evaluatedUtc: String?=null,val candidateExpiresUtc: String?=null) {
    override fun toString()="PersonalSignalTransition([REDACTED])"
}

internal object PersonalSignalTransitions {
    /** No write, admission, queue submission or cooldown consumption occurs here. */
    fun advance(report: PersonalSignalReport,prior: PersonalSignalState?,sourceRevision: Long,at: Instant): PersonalSignalTransition =
        fold(report,prior,sourceRevision,at,null,null)

    /** Only a separately verified post-admission reset may seed unknown replay. Earlier
     * days supply persistence/baseline proof, never episode history. */
    fun reanchor(report: PersonalSignalReport,anchor: SignalResetAnchor,highWater: SignalAdmissionHighWater?,sourceRevision: Long,at: Instant): PersonalSignalTransition {
        require(SignalCheckpointContinuity.resetAnchor(report,highWater)==anchor)
        return fold(report,null,sourceRevision,at,anchor,highWater?.admittedUtc)
    }

    private fun fold(report: PersonalSignalReport,prior: PersonalSignalState?,sourceRevision: Long,at: Instant,
        anchor: SignalResetAnchor?,lastEmittedUtc: String?): PersonalSignalTransition {
        val config=report.rule.configuration
        if(!report.rule.enabled || config==null) return PersonalSignalTransition("disabled",prior,null)
        require(sourceRevision>=0 && Instant.parse(report.asOf)==at)
        val today=at.atZone(ZoneId.of(config.timeZone)).toLocalDate().toString()
        // Historical inspection cannot create a backlog or advance durable ownership.
        if(report.query.newest!=today || report.days.lastOrNull()?.date!=today)
            return PersonalSignalTransition("historical_inspection_only",prior,null)
        if(prior!=null && (prior.ruleId!=report.rule.ruleId || prior.ruleRevision!=report.rule.revision ||
            prior.sourceRevision!=sourceRevision || prior.date>today || prior.projected.lastEmittedUtc?.let { Instant.parse(it)>at }==true))
            return PersonalSignalTransition("needs_reevaluation",prior,null)
        // Do not reconstruct transitions preceding a captured state from a rolling display window.
        if(prior!=null && prior.date<report.query.oldest)
            return PersonalSignalTransition("needs_reevaluation",prior,null)
        val firstNewDate=if(prior!=null && prior.date<today) LocalDate.parse(prior.date).plusDays(1) else LocalDate.parse(today)
        if(prior!=null && LocalDate.parse(report.query.oldest)>firstNewDate.minusDays(
                maxOf(config.triggerPersistenceDays,config.resetPersistenceDays).toLong()-1))
            return PersonalSignalTransition("needs_reevaluation",prior,null)
        var episode=if(prior?.date==today) prior.beforeDay else prior?.projected ?: PersonalSignalEpisode(lastEmittedUtc=lastEmittedUtc)
        var beforeToday=episode
        for(day in report.days) {
            if(anchor!=null && day.date<anchor.resetSatisfiedOn) continue
            if(prior!=null && day.date<prior.date || prior!=null && day.date==prior.date && prior.date!=today) continue
            if(day.date==today) beforeToday=episode
            when(day.status) {
                "reset" -> episode=PersonalSignalEpisode(known=true,lastEmittedUtc=episode.lastEmittedUtc)
                "trigger" -> if(episode.known && episode.episodeId==null) {
                    episode=episode.copy(episodeId=AnalysisClaims.hash("${report.rule.ruleId}:${report.rule.revision}:${day.date}"),emitted=false)
                }
            }
        }
        // Acknowledged admission facts are irreversible under same-day projection replacement.
        if(prior?.date==today && prior.projected.emitted) {
            if(prior.projected.episodeId!=episode.episodeId) return PersonalSignalTransition("needs_reevaluation",prior,null)
            episode=episode.copy(emitted=true,lastEmittedUtc=prior.projected.lastEmittedUtc)
        }
        val next=PersonalSignalState(report.rule.ruleId,report.rule.revision,today,beforeToday,episode,sourceRevision)
        val status=when {
            !episode.known -> "unknown_prior_state"
            report.days.last().status!="trigger" -> "current_trigger_not_satisfied"
            episode.emitted -> "episode_already_emitted"
            episode.lastEmittedUtc?.let { at<Instant.parse(it).plusSeconds(config.cooldownSeconds) }==true -> "cooldown_wait"
            episode.episodeId!=null -> "admission_candidate"
            else -> "current_trigger_not_satisfied"
        }
        return PersonalSignalTransition(status,next,episode.episodeId?.takeIf { status=="admission_candidate" },at.toString(),
            LocalDate.parse(today).plusDays(1).atStartOfDay(ZoneId.of(config.timeZone)).toInstant().toString())
    }

    /** Only task 7.4's atomic reason admission may acknowledge; evaluation must not start cooldown. */
    fun acknowledge(transition: PersonalSignalTransition,at: Instant): PersonalSignalState {
        require(transition.status=="admission_candidate" && transition.candidateEpisodeId!=null)
        require(at>=Instant.parse(requireNotNull(transition.evaluatedUtc)) && at<Instant.parse(requireNotNull(transition.candidateExpiresUtc)))
        val state=requireNotNull(transition.state)
        require(state.projected.episodeId==transition.candidateEpisodeId && !state.projected.emitted)
        state.projected.lastEmittedUtc?.let { require(at>=Instant.parse(it)) }
        return state.copy(projected=state.projected.copy(emitted=true,lastEmittedUtc=at.toString()))
    }
}
