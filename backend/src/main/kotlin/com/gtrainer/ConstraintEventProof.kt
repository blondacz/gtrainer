package com.gtrainer

import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal class ConstraintProofLimit : IllegalStateException("Constraint proof budget exceeded")

internal class ConstraintProofBudget {
    private var work = 0L
    private var bytes = 0L
    fun work(count: Long = 1) {
        if (count < 0 || count > 50_000 - work) throw ConstraintProofLimit()
        work += count
    }
    fun bytes(count: Long) {
        if (count < 0 || count > 8 * 1024 * 1024 - bytes) throw ConstraintProofLimit()
        bytes += count
    }
}

/** Absence of an association is explicit, not reconstructed against today's source heads. */
@Serializable
internal data class ConstraintAssociation(val contextId: String, val contextRevision: Int,
    val intakeId: String?, val intakeRevision: Int?, val observedIntakeHead: Int?) {
    override fun toString() = "ConstraintAssociation([REDACTED])"
}

/** Purgeable local proof. No intake text, context prose, labels or attribution are copied. */
@Serializable
internal data class ConstraintEndpoint(val ref: ConstraintSubjectRef,
    val context: ContextLifecycleView? = null, val commitment: ConfirmedCommitment? = null,
    val heads: List<ConstraintSubjectRef>, val associations: List<ConstraintAssociation>,
    val materialSha256: String?, val dependencySha256: String) {
    init { require(ref.present && ((context != null) != (commitment != null))) }
    override fun toString() = "ConstraintEndpoint([REDACTED])"

    fun relevant(plan: AuthoredPlan, at: Instant, budget: ConstraintProofBudget): Boolean {
        budget.work(plan.definition.sessions.size.toLong() + 1)
        // Each endpoint contains one subject, so retrieval's context-by-target work is bounded
        // by the charged session count. Commitment recurrence charges every candidate too.
        return AdaptationConstraintFingerprints.project(plan, at, listOfNotNull(context), listOfNotNull(commitment),
            onCommitmentWork = { budget.work() }).any { it.relevant }
    }
}

@Serializable
internal data class ConstraintEventProof(val before: ConstraintEndpoint?, val after: ConstraintEndpoint?,
    val capturedAt: String, val version: String = "canonical_constraint_event_v1") {
    init {
        require(before != null || after != null)
        require(before?.materialSha256 != after?.materialSha256)
        require(version == "canonical_constraint_event_v1")
        Instant.parse(capturedAt)
    }
    override fun toString() = "ConstraintEventProof([REDACTED])"
}

internal data class ConstraintCanonicalCapture(val endpoints: Map<Pair<String, String>, ConstraintEndpoint>,
    val inventory: List<ConstraintSubjectRef>) {
    override fun toString() = "ConstraintCanonicalCapture([REDACTED])"
}

/** Capture dynamic provenance once, inside the same owner transaction as its mutation. */
internal object ConstraintCanonicalProof {
    fun capture(connection: Connection, at: Instant, budget: ConstraintProofBudget): ConstraintCanonicalCapture {
        val json = ScopedEvidencePreparation.json
        val observed = ConstraintObservationHeads.capture(connection)
        val inventory = observed.values.map { it.ref }.sortedWith(compareBy({ it.kind }, { it.id }))
        budget.work(inventory.size.toLong())
        val byKind = inventory.associateBy { it.kind to it.id }
        val links = connection.createStatement().use { s ->
            s.executeQuery("SELECT l.context_id,l.context_revision,l.intake_id,l.intake_revision FROM context_intake_links l JOIN " +
                "(SELECT context_id,max(revision) revision FROM athlete_context GROUP BY context_id) h " +
                "ON l.context_id=h.context_id AND l.context_revision=h.revision ORDER BY l.context_id LIMIT 1001").use { r ->
                buildMap { while (r.next()) {
                    if (size >= 1000) throw ConstraintProofLimit()
                    val intake = r.getString(3)
                    put(r.getString(1), ConstraintAssociation(r.getString(1), r.getInt(2), intake, r.getInt(4), byKind.getValue("intake" to intake).revision))
                } }
            }
        }
        fun association(id: String): ConstraintAssociation = links[id] ?: ConstraintAssociation(id, byKind.getValue("context" to id).revision, null, null, null)
        fun dependencies(ref: ConstraintSubjectRef, linkedContext: String? = null, intake: String? = null): Pair<List<ConstraintSubjectRef>, List<ConstraintAssociation>> {
            val contextId = if (ref.kind == "context") ref.id else linkedContext
            val associations = contextId?.let { listOf(association(it)) }.orEmpty()
            val heads = (listOf(ref) + listOfNotNull(contextId?.let { byKind.getValue("context" to it) },
                intake?.let { byKind.getValue("intake" to it) }) + associations.mapNotNull { a -> a.intakeId?.let { byKind.getValue("intake" to it) } })
                .distinct().sortedWith(compareBy({ it.kind }, { it.id }))
            return heads to associations
        }
        val result = linkedMapOf<Pair<String, String>, ConstraintEndpoint>()
        fun add(endpoint: ConstraintEndpoint) {
            budget.bytes(json.encodeToString(endpoint).toByteArray(Charsets.UTF_8).size.toLong())
            result[endpoint.ref.kind to endpoint.ref.id] = endpoint
        }
        val lifecycle = ContextLifecycleStore(connection)
        ContextRows(connection).latest().forEach { context ->
            val view = lifecycle.view(context, at.atZone(ZoneOffset.UTC).toLocalDate())
            val ref = byKind.getValue("context" to context.contextId)
            val (heads, associations) = dependencies(ref)
            val dependency = AnalysisClaims.hash(json.encodeToString(view) + json.encodeToString(heads) + json.encodeToString(associations))
            val minimal = view.copy(context = context.copy(content = "", authorAttribution = "", enteredBy = "", sourceCategory = ""),
                state = "current", needsReview = false, reasonFlags = emptyList())
            add(ConstraintEndpoint(ref, context = minimal, heads = heads, associations = associations,
                materialSha256 = AdaptationConstraintFingerprints.material(view), dependencySha256 = dependency))
        }
        CommitmentStore(connection).list().forEach { commitment ->
            val ref = byKind.getValue("commitment" to commitment.reference.commitmentId)
            val (heads, associations) = dependencies(ref, commitment.reference.contextId, commitment.reference.intakeId)
            val dependency = AnalysisClaims.hash(json.encodeToString(commitment) + json.encodeToString(heads) + json.encodeToString(associations))
            add(ConstraintEndpoint(ref, commitment = commitment.copy(definition = commitment.definition.copy(label = "Constraint"),
                authorAttribution = "", sourceCategory = "", enteredBy = ""), heads = heads, associations = associations,
                materialSha256 = AdaptationConstraintFingerprints.material(commitment), dependencySha256 = dependency))
        }
        return ConstraintCanonicalCapture(result, inventory)
    }
}
