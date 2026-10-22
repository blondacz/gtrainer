package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

@Serializable
data class ContextLifecycle(val resolution: String = "unresolved", val reviewDueOn: String? = null) {
    init { require(resolution in setOf("unresolved", "resolved")); reviewDueOn?.let(::validateContextDate) }
}

@Serializable
data class ContextLifecycleUpdate(val expectedContextRevision: Int, val resolution: String, val reviewDueOn: String?) {
    init { require(expectedContextRevision in 1..999); ContextLifecycle(resolution,reviewDueOn) }
}

@Serializable
data class ContextLifecycleView(val context: AthleteContext, val lifecycle: ContextLifecycle, val state: String,
    val needsReview: Boolean, val sourceChanged: Boolean, val reasonFlags: List<String>) {
    override fun toString() = "ContextLifecycleView([REDACTED])"
}

@Serializable
data class ContextLifecycleReport(val evaluatedOn: String, val items: List<ContextLifecycleView>,
    val interpretationBasis: String = "current_canonical_revisions_not_point_in_time_replay") {
    override fun toString() = "ContextLifecycleReport([REDACTED])"
}

internal fun projectContextLifecycle(context: AthleteContext, lifecycle: ContextLifecycle, sourceChanged: Boolean,
    asOf: LocalDate): ContextLifecycleView {
    val today=asOf.toString()
    val ended=context.applicableUntil?.let { it < today } == true
    val future=context.observedOn > today || context.applicableFrom?.let { it > today } == true
    val safety=context.category in setOf("restriction","symptom")
    val due=lifecycle.reviewDueOn?.let { it <= today } == true
    val unresolved=lifecycle.resolution == "unresolved"
    val needsReview=sourceChanged || unresolved && (due || safety && (ended || context.retired))
    val flags=buildList {
        if (ended) add("applicability_ended")
        if (context.retired) add("retired_not_resolution")
        if (future) add("not_current_at_evaluation")
        if (due && unresolved) add("review_due_not_recovery")
        if (sourceChanged) add("source_changed_needs_reconfirmation")
        if (unresolved && safety && (ended || context.retired)) add("unresolved_safety_applicability_needs_clarification")
    }
    val state=when {
        !unresolved -> "resolved"
        context.retired || ended -> "historical"
        future -> "upcoming"
        needsReview -> "needs_review"
        else -> "current"
    }
    return ContextLifecycleView(context,lifecycle,state,needsReview,sourceChanged,flags)
}

interface ContextLifecycleRepository {
    fun contextLifecycle(asOf: LocalDate): ContextLifecycleReport
    fun updateContextLifecycle(id: String, request: ContextLifecycleUpdate, asOf: LocalDate): ContextLifecycleView
}

class ContextLifecycleService(private val repository: ContextLifecycleRepository, private val clock: Clock = Clock.systemUTC()) {
    fun list(asOf: String?): ContextLifecycleReport {
        asOf?.let(::validateContextDate)
        return repository.contextLifecycle(asOf?.let(LocalDate::parse) ?: LocalDate.now(clock))
    }
    fun update(id: String, request: ContextLifecycleUpdate) = repository.updateContextLifecycle(id,request,LocalDate.now(clock))
    fun retrieve(query: ScopedContextRetrievalQuery) = retrieveScopedAthleteContext(repository.contextLifecycle(query.observationCutoff).items,query)
}

class ContextRevisionConflict : IllegalStateException("Context revision changed")
