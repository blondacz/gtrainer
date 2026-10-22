package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Serializable
internal data class AdaptationConstraintFingerprint(
    val kind: String,
    val id: String,
    val revision: Int,
    val relevant: Boolean,
    val materialSha256: String,
    val dependencySha256: String,
) {
    init {
        require(kind in setOf("context", "commitment"))
        validateContextId(id)
        require(revision in 1..1000)
        require(Regex("[0-9a-f]{64}").matches(materialSha256))
        require(Regex("[0-9a-f]{64}").matches(dependencySha256))
    }
    override fun toString() = "AdaptationConstraintFingerprint([REDACTED])"
}

internal object AdaptationConstraintFingerprints {
    private val json = ScopedEvidencePreparation.json
    private fun digest(value: String) = AnalysisClaims.hash(value)

    @Serializable private data class ContextMaterial(
        val category: String, val observedOn: String, val applicableFrom: String?, val applicableUntil: String?,
        val sport: String?, val activityId: String?, val reviewId: String?, val retired: Boolean,
        val restrictionKind: String?, val restrictionValue: String?, val restrictionUnit: String?,
        val resolution: String, val reviewDueOn: String?,
        val sourceChanged: Boolean,
    )

    @Serializable private data class ContextDependency(val context: AthleteContext, val lifecycle: ContextLifecycle,
        val sourceChanged: Boolean, val relevant: Boolean, val state: String, val needsReview: Boolean, val reasonFlags: List<String>)
    @Serializable private data class CommitmentMaterial(
        val kind: String, val timeZone: String, val startsLocal: String, val endsLocal: String,
        val recurrence: CommitmentRecurrence?, val exceptions: List<CommitmentException>,
        val sourceChanged: Boolean, val contextChanged: Boolean, val needsReconfirmation: Boolean,
    )

    /** Canonical operational fields only. Eligibility and clock-derived state are not events. */
    fun material(view: ContextLifecycleView): String? {
        val c = view.context
        if (c.category !in setOf("restriction", "symptom")) return null
        val value = c.restrictionValue?.let { raw ->
            if (c.restrictionKind == "maximum_duration") runCatching { BigDecimal(raw.trim()).stripTrailingZeros().toString() }.getOrElse { raw.trim() }
            else raw.trim().lowercase()
        }
        return digest(json.encodeToString(ContextMaterial(c.category, c.observedOn, c.applicableFrom, c.applicableUntil, c.sport,
            c.activityId, c.reviewId, c.retired, c.restrictionKind, value, c.restrictionUnit,
            view.lifecycle.resolution, view.lifecycle.reviewDueOn, view.sourceChanged)))
    }

    fun material(item: ConfirmedCommitment): String {
        val d = item.definition
        return digest(json.encodeToString(CommitmentMaterial(d.kind, d.timeZone, d.startsLocal, d.endsLocal, d.recurrence,
            d.exceptions.sortedBy { it.originalDate }, item.sourceChanged, item.contextChanged, item.needsReconfirmation)))
    }

    /** Eligible safety projection, NOT a complete canonical-presence inventory or deletion proof. */
    fun project(plan: AuthoredPlan, at: Instant, contexts: List<ContextLifecycleView>, commitments: List<ConfirmedCommitment>,
        onCommitmentWork: () -> Unit = {}): List<AdaptationConstraintFingerprint> {
        val policy = plan.definition.policy ?: return emptyList()
        require(contexts.size <= 1000 && commitments.size <= 1000)
        val (first, last) = policy.horizon(at)
        val zone = policy.adaptationTimeZone
        val zoneId=java.time.ZoneId.of(zone)
        val horizonStart = first.atStartOfDay(zoneId).toInstant()
        val horizonEnd = last.plusDays(1).atStartOfDay(zoneId).toInstant()
        val targets = plan.definition.sessions.asSequence().filter { session ->
            !session.omitted && session.schedule.startInstant() < horizonEnd && session.schedule.endInstant() > horizonStart && session.schedule.startInstant() > at
        }.map { session ->
            ContextRetrievalTarget(maxOf(horizonStart,session.schedule.startInstant()).atZone(zoneId).toLocalDate(),
                minOf(horizonEnd,session.schedule.endInstant()).minusNanos(1).atZone(zoneId).toLocalDate(),session.sport)
        }.toList()
        val canonicalContexts = contexts.groupBy { it.context.contextId }.values.map { rows -> rows.maxBy { it.context.revision } }
        require(canonicalContexts.size <= 1000)
        val asOf = at.atZone(java.time.ZoneId.of(zone)).toLocalDate()
        val projected = canonicalContexts.map { view ->
            projectContextLifecycle(view.context, view.lifecycle, view.sourceChanged, asOf)
        }
        val selected = retrieveScopedAthleteContext(projected, ScopedContextRetrievalQuery(
            observationCutoff = asOf, evidenceTargets = emptyList(), amendmentTargets = targets,
            optionalLimit = 1000, mandatoryLimit = 1000,
        )).amendments
        val selectedIds = (selected.mandatoryRestrictions.map { it.contextId } + selected.optionalContexts.map { it.context.contextId } +
            selected.mandatoryClarifications.map { it.context.contextId }).toSet()
        val cutoffCanonical = projected.filter { LocalDate.parse(it.context.observedOn) <= asOf }
        val out = cutoffCanonical.filter { it.context.category in setOf("restriction", "symptom") }.map { view ->
            val c = view.context
            val relevant = c.contextId in selectedIds
            AdaptationConstraintFingerprint("context", c.contextId, c.revision, relevant,
                requireNotNull(material(view)), digest(json.encodeToString(ContextDependency(c, view.lifecycle, view.sourceChanged, relevant,
                    view.state,view.needsReview,view.reasonFlags.sorted()))))
        }
        val canonicalCommitments = commitments.groupBy { it.reference.commitmentId }.values.map { rows ->
            rows.maxWith(compareBy<ConfirmedCommitment> { it.reference.revision }.thenBy { it.reference.intakeRevision })
        }
        require(canonicalCommitments.size <= 1000)
        val calendar = projectCommitments(canonicalCommitments, first.toString(), last.toString(), zone, onCommitmentWork)
        val relevantCommitments = calendar.occurrences.map { it.reference.commitmentId }.toSet()
        val commitmentFingerprints = canonicalCommitments.map { item ->
            val relevant = item.reference.commitmentId in relevantCommitments
            AdaptationConstraintFingerprint("commitment", item.reference.commitmentId, item.reference.revision, relevant,
                material(item), digest(json.encodeToString(listOf(json.encodeToString(item),relevant.toString()))))
        }
        return (out + commitmentFingerprints).sortedWith(compareBy<AdaptationConstraintFingerprint> { it.kind }.thenBy { it.id })
    }
}
