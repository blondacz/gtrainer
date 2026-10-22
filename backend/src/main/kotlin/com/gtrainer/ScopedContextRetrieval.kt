package com.gtrainer

import java.time.LocalDate
import java.util.BitSet

/** A single, correlated scope. Null sport intentionally means every sport. */
data class ContextRetrievalTarget(
    val oldest: LocalDate,
    val newest: LocalDate,
    val sport: String? = null,
    val activityId: String? = null,
    val reviewId: String? = null
) {
    init {
        require(!newest.isBefore(oldest))
        require(java.time.temporal.ChronoUnit.DAYS.between(oldest, newest) + 1 in 1..366)
        sport?.let { validateContextText(it,80,true) }
        activityId?.let { validateContextText(it,128,true) }
        reviewId?.let { validateContextText(it,128,true) }
    }
}

data class ScopedContextRetrievalQuery(
    val observationCutoff: LocalDate,
    val evidenceTargets: List<ContextRetrievalTarget>,
    val amendmentTargets: List<ContextRetrievalTarget> = emptyList(),
    val optionalLimit: Int = 20,
    val mandatoryLimit: Int? = null
) {
    init {
        require(evidenceTargets.size + amendmentTargets.size <= 1000)
        require(optionalLimit in 0..1000)
        require(mandatoryLimit == null || mandatoryLimit in 0..1000)
    }
}

@kotlinx.serialization.Serializable
data class ScopedContextSelection(
    val mandatoryRestrictions: List<AthleteContext>,
    val optionalContexts: List<ContextLifecycleView>,
    val mandatoryClarifications: List<ContextLifecycleView>,
    val conflicts: List<ContextRestrictionConflict>,
    val blockedReasons: List<String>,
    val blocked: Boolean,
    val omittedOptionalCount: Int
)

@kotlinx.serialization.Serializable
data class ScopedContextRetrievalResult(val evidence: ScopedContextSelection, val amendments: ScopedContextSelection)

private fun intersects(context: AthleteContext, target: ContextRetrievalTarget): Boolean {
    val from = context.applicableFrom?.let(LocalDate::parse)
    val until = context.applicableUntil?.let(LocalDate::parse)
    return (until == null || !until.isBefore(target.oldest)) && (from == null || !from.isAfter(target.newest))
}

private fun matches(context: AthleteContext, target: ContextRetrievalTarget) =
    (target.sport == null || context.sport == null || context.sport == target.sport) &&
        (context.activityId == null || context.activityId == target.activityId) &&
        (context.reviewId == null || context.reviewId == target.reviewId)

/** Pure new-profile projection over current canonical lifecycle views; never point-in-time replay. */
fun retrieveScopedAthleteContext(all: List<ContextLifecycleView>, query: ScopedContextRetrievalQuery): ScopedContextRetrievalResult {
    // Select canonical revision first: a newer post-cutoff revision must not resurrect an older one.
    val canonical = all.groupBy { it.context.contextId }.values.map { revisions -> revisions.maxBy { it.context.revision } }
        .filter { LocalDate.parse(it.context.observedOn) <= query.observationCutoff }
        .map { projectContextLifecycle(it.context,it.lifecycle,it.sourceChanged,query.observationCutoff) }
        .sortedBy { it.context.contextId }
    val comparator = compareByDescending<AthleteContext> { it.observedOn }.thenBy { it.contextId }.thenBy { it.revision }

    fun select(targets: List<ContextRetrievalTarget>, future: Boolean): ScopedContextSelection {
        fun activeRestriction(view: ContextLifecycleView) = view.context.category=="restriction" && !view.context.retired && view.lifecycle.resolution!="resolved"
        val relevant = canonical.filter { view -> targets.any { matches(view.context,it) && intersects(view.context,it) } }
        // Do not drop malformed mandatory restrictions: return them and block the model boundary.
        val mandatory = relevant.filter(::activeRestriction).map { it.context }.sortedWith(comparator)
        val clarifications = canonical.filter { view ->
            val c=view.context
            val unresolved=view.lifecycle.resolution!="resolved"
            val safety=c.category in setOf("symptom","restriction")
            if(future && !unresolved) false else targets.any { target ->
                if(!matches(c,target)) false else {
                    val crossesEnd=c.applicableUntil?.let { LocalDate.parse(it)<target.newest } == true
                    val couldHaveStarted=c.applicableFrom?.let { LocalDate.parse(it)<=target.newest } ?: true
                    val uncertainFuture=future && unresolved && safety && couldHaveStarted && (c.retired || crossesEnd ||
                        c.category=="symptom" && (c.applicableFrom==null || c.applicableUntil==null))
                    val relevantNow=intersects(c,target)
                    uncertainFuture || relevantNow && (view.sourceChanged || view.needsReview)
                }
            }
        }.sortedWith(compareByDescending<ContextLifecycleView> { it.context.observedOn }.thenBy { it.context.contextId })
        val targetConflicts = conflictsAtTargets(canonical.filter(::activeRestriction).map { it.context },targets)
        val unstructured = mandatory.any { it.restrictionKind==null || it.restrictionValue.isNullOrBlank() }
        val identityCount = (mandatory.map { it.contextId to it.revision } + clarifications.map { it.context.contextId to it.context.revision }).distinct().size
        val reasons = buildList {
            if (query.mandatoryLimit == null) add("mandatory_budget_unconfigured")
            else if (identityCount > query.mandatoryLimit) add("mandatory_context_overflow")
            if (unstructured) add("unstructured_restriction")
            if (targetConflicts.isNotEmpty()) add("restriction_conflict")
            if (clarifications.isNotEmpty()) add("context_needs_confirmation")
        }
        val mandatoryIds=(mandatory.map { it.contextId }+clarifications.map { it.context.contextId }).toSet()
        val optional = relevant.filter { it.context.contextId !in mandatoryIds }
            .sortedWith(compareByDescending<ContextLifecycleView> { it.context.observedOn }.thenBy { it.context.contextId })
        return ScopedContextSelection(mandatory, optional.take(query.optionalLimit), clarifications, targetConflicts, reasons, reasons.isNotEmpty(), (optional.size - query.optionalLimit).coerceAtLeast(0))
    }
    return ScopedContextRetrievalResult(select(query.evidenceTargets,false), select(query.amendmentTargets,true))
}

/** Each pair is emitted at most once, not once per target. Target membership stays correlated. */
private fun conflictsAtTargets(restrictions: List<AthleteContext>, targets: List<ContextRetrievalTarget>): List<ContextRestrictionConflict> {
    val memberships=restrictions.map { context -> BitSet(targets.size).also { bits ->
        targets.forEachIndexed { index,target -> if(matches(context,target) && intersects(context,target)) bits.set(index) }
    } }
    return buildList {
        for(i in restrictions.indices) for(j in i+1 until restrictions.size) {
            val first=restrictions[i]; val second=restrictions[j]
            val conflict=contextRestrictionConflicts(listOf(first,second)).singleOrNull() ?: continue
            val shared=(memberships[i].clone() as BitSet).also { it.and(memberships[j]) }
            val starts=maxOf(first.applicableFrom?.let(LocalDate::parse) ?: LocalDate.MIN,second.applicableFrom?.let(LocalDate::parse) ?: LocalDate.MIN)
            val ends=minOf(first.applicableUntil?.let(LocalDate::parse) ?: LocalDate.MAX,second.applicableUntil?.let(LocalDate::parse) ?: LocalDate.MAX)
            var index=shared.nextSetBit(0)
            while(index>=0) {
                val target=targets[index]
                if(target.oldest<=ends && target.newest>=starts) { add(conflict); break }
                index=shared.nextSetBit(index+1)
            }
        }
    }.sortedWith(compareBy({it.first.contextId},{it.second.contextId},{it.reason}))
}
