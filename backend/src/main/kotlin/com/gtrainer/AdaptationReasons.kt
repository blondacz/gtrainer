package com.gtrainer

import kotlinx.serialization.Serializable

/** Material finding identity is separate from evidence validity and successful admission. */
@Serializable
data class AdaptationReasonObservation(val reasonId: String,val kind: String,val subjectId: String,
    val condition: String,val materialSha256: String,val dependencySha256: String) {
    init {
        require(kind in setOf("confirmed_skip","planned_range","extra_workload","personal_signal","changed_constraint","explicit_review"))
        require(condition in setOf("active","clear","unevaluable","needs_confirmation","provisional"))
        require(listOf(reasonId,subjectId,materialSha256,dependencySha256).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    override fun toString()="AdaptationReasonObservation([REDACTED])"
}

/** Minimal content-free current ledger head; admission timestamps/job IDs never enter evidence hashes. */
@Serializable
data class AdaptationReasonHead(val reasonId: String,val kind: String,val subjectId: String,val revision: Long,
    val episode: Long,val open: Boolean,val condition: String,val materialSha256: String,val dependencySha256: String) {
    init {
        AdaptationReasonObservation(reasonId,kind,subjectId,condition,materialSha256,dependencySha256)
        require(revision>0 && episode>=0 && (!open || episode>0))
    }
    override fun toString()="AdaptationReasonHead([REDACTED])"
}

@Serializable
data class AdaptationAdmission(val reasonId: String,val episode: Long,val jobId: String,val admittedUtc: String) {
    init { require(reasonId.matches(Regex("[a-f0-9]{64}")) && episode>0);validateContextId(jobId);java.time.Instant.parse(admittedUtc) }
    override fun toString()="AdaptationAdmission([REDACTED])"
}

internal object AdaptationReasons {
    /** Missing/ambiguous support retracts actionability, NOT the open episode's deduplication facts. */
    fun project(previous: AdaptationReasonHead?,observation: AdaptationReasonObservation): AdaptationReasonHead {
        if(previous!=null) require(previous.reasonId==observation.reasonId && previous.kind==observation.kind && previous.subjectId==observation.subjectId)
        val active=observation.condition=="active"
        // A changed canonical constraint is a distinct change event, not another reading of the same abnormal workout.
        val changedConstraint=observation.kind=="changed_constraint" && previous?.materialSha256!=observation.materialSha256
        val newEpisode=active && (previous?.open!=true || changedConstraint)
        val episode=(previous?.episode?:0).let { if(newEpisode) { check(it<Long.MAX_VALUE);it+1 } else it }
        val open=when(observation.condition) { "active" -> true;"clear" -> false;else -> previous?.open?:false }
        val next=AdaptationReasonHead(observation.reasonId,observation.kind,observation.subjectId,previous?.revision?:1,episode,open,
            observation.condition,observation.materialSha256,observation.dependencySha256)
        if(previous==null || next==previous) return next
        check(previous.revision<Long.MAX_VALUE)
        return next.copy(revision=previous.revision+1)
    }

    fun requiresAdmission(head: AdaptationReasonHead,admissions: List<AdaptationAdmission>): Boolean =
        head.condition=="active" && head.open && admissions.none { it.reasonId==head.reasonId && it.episode==head.episode }
}
