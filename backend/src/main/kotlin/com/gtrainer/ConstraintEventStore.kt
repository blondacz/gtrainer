package com.gtrainer

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class ConstraintEnrollment(val family: AdaptationFamily, val baselineId: String,
    val baselineSequence: Long, val baselineDeletionGeneration: Long,
    val cursorSequence: Long, val cursorDeletionGeneration: Long, val available: Boolean, val eventCount: Long) {
    override fun toString() = "ConstraintEnrollment([REDACTED])"
}

internal data class CapturedConstraintEvent(val eventId: String, val sequence: Long, val proof: ConstraintEventProof) {
    override fun toString() = "CapturedConstraintEvent([REDACTED])"
}

internal data class CapturedConstraintChanges(val enrollment: ConstraintEnrollment?, val events: List<CapturedConstraintEvent>, val available: Boolean) {
    override fun toString() = "CapturedConstraintChanges([REDACTED])"
}

internal data class ProjectedConstraintChanges(val baselineId: String?, val available: Boolean, val eligibleEventIds: List<String>) {
    override fun toString() = "ProjectedConstraintChanges([REDACTED])"
}

internal object ConstraintChanges {
    fun project(capture: CapturedConstraintChanges?, plan: AuthoredPlan?, at: Instant): ProjectedConstraintChanges {
        if (capture == null || plan == null || !capture.available) return ProjectedConstraintChanges(capture?.enrollment?.baselineId, false, emptyList())
        val budget = ConstraintProofBudget()
        return try {
            val ids = capture.events.filter { event ->
                budget.work()
                event.proof.before?.relevant(plan, at, budget) == true || event.proof.after?.relevant(plan, at, budget) == true
            }.map { it.eventId }
            ProjectedConstraintChanges(capture.enrollment?.baselineId, true, ids)
        } catch (_: ConstraintProofLimit) {
            ProjectedConstraintChanges(capture.enrollment?.baselineId, false, emptyList())
        }
    }
}

/** SQL-only local continuity owner. No reason heads, admissions, queue writes or provider data. */
internal class ConstraintEventStore(private val connection: Connection) {
    private val json = ScopedEvidencePreparation.json

    fun hasAvailable(): Boolean = connection.createStatement().use { s ->
        s.executeQuery("SELECT 1 FROM constraint_family_enrollments WHERE available=1 LIMIT 1").use { it.next() }
    }

    private fun family(plan: AuthoredPlan): AdaptationFamily? = plan.definition.policy?.let {
        AdaptationFamily(plan.reference, it.horizonDays, it.adaptationTimeZone, plan.reference.planId)
    }

    /** Revisions define distinct families. Returning selection never overwrites an existing baseline. */
    fun enrollSelected(at: Instant) {
        val plans = PlanStore(connection)
        val plan = plans.selection().selected?.let { plans.get(it.planId, it.revision) } ?: return
        val family = family(plan) ?: return
        if (enrollment(family) != null) return
        val count = connection.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM constraint_family_enrollments").use { it.next(); it.getLong(1) } }
        require(count < 1000) { "Constraint enrollment capacity exceeded" }
        val frontier = ConstraintMutationStore(connection).frontier()
        val baselineId = UUID.randomUUID().toString()
        var manifest: String? = null
        var available = frontier.available
        if (available) try {
            val budget = ConstraintProofBudget()
            val canonical = ConstraintCanonicalProof.capture(connection, at, budget)
            manifest = json.encodeToString(canonical.inventory)
            val bytes = manifest.toByteArray(Charsets.UTF_8).size.toLong()
            budget.bytes(bytes)
            checkCapacity(0, bytes)
        } catch (_: ConstraintProofLimit) { available = false; manifest = null }
        connection.prepareStatement("INSERT INTO constraint_family_enrollments(family_key,family_json,baseline_id,baseline_sequence,baseline_deletion_generation,cursor_sequence,cursor_deletion_generation,available,baseline_proof_json,event_count) VALUES(?,?,?,?,?,?,?,?,?,0)").use { p ->
            p.setString(1, family.key()); p.setString(2, json.encodeToString(family)); p.setString(3, baselineId)
            p.setLong(4, frontier.sequence); p.setLong(5, frontier.deletionGeneration)
            p.setLong(6, frontier.sequence); p.setLong(7, frontier.deletionGeneration); p.setInt(8, if (available) 1 else 0); p.setString(9, manifest)
            p.executeUpdate()
        }
    }

    private fun enrollment(family: AdaptationFamily): ConstraintEnrollment? = connection.prepareStatement(
        "SELECT baseline_id,baseline_sequence,baseline_deletion_generation,cursor_sequence,cursor_deletion_generation,available,event_count FROM constraint_family_enrollments WHERE family_key=?").use { p ->
        p.setString(1, family.key()); p.executeQuery().use { r ->
            if (!r.next()) null else ConstraintEnrollment(family, r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getInt(6) == 1, r.getLong(7))
        }
    }

    /** Known proof loss is terminal. Opaque baseline/event identities and chronology survive. */
    fun invalidate() {
        connection.createStatement().use { s ->
            s.executeUpdate("UPDATE constraint_family_enrollments SET available=0,baseline_proof_json=NULL")
            s.executeUpdate("UPDATE constraint_material_events SET proof_json=NULL")
        }
    }

    fun observe(before: ConstraintCanonicalCapture, after: ConstraintCanonicalCapture, previous: ConstraintMutationFrontier,
        current: ConstraintMutationFrontier, at: Instant, budget: ConstraintProofBudget) {
        if (!current.available || !previous.available || previous.deletionGeneration != current.deletionGeneration) { invalidate(); return }
        val families = connection.createStatement().use { s ->
            s.executeQuery("SELECT family_key,cursor_sequence,cursor_deletion_generation,event_count," +
                "(SELECT count(proof_json) FROM constraint_material_events e WHERE e.family_key=f.family_key) FROM constraint_family_enrollments f WHERE available=1 ORDER BY family_key LIMIT 1001").use { r ->
                buildList { while (r.next()) {
                    budget.work()
                    if (size >= 1000) throw ConstraintProofLimit()
                    if (r.getLong(2) != previous.sequence || r.getLong(3) != previous.deletionGeneration || r.getLong(4) != r.getLong(5)) { invalidate(); return }
                    add(r.getString(1))
                } }
            }
        }
        val proofs = (before.endpoints.keys + after.endpoints.keys).sortedWith(compareBy({ it.first }, { it.second })).mapNotNull { key ->
            budget.work()
            val old = before.endpoints[key]; val new = after.endpoints[key]
            if (old?.materialSha256 == new?.materialSha256) null else {
                key to json.encodeToString(ConstraintEventProof(old, new, at.toString()))
            }
        }
        val bytes = proofs.sumOf { it.second.toByteArray(Charsets.UTF_8).size.toLong() } * families.size
        budget.work(proofs.size.toLong() * families.size); budget.bytes(bytes)
        checkCapacity(proofs.size.toLong() * families.size, bytes)
        connection.prepareStatement("INSERT INTO constraint_material_events(event_id,family_key,mutation_sequence,subject_kind,subject_id,proof_json) VALUES(?,?,?,?,?,?)").use { p ->
            families.forEach { family -> proofs.forEach { (key, proof) ->
                p.setString(1, AnalysisClaims.hash("$family:${current.sequence}:${key.first}:${key.second}"))
                p.setString(2, family); p.setLong(3, current.sequence); p.setString(4, key.first); p.setString(5, key.second); p.setString(6, proof); p.addBatch()
            } }
            p.executeBatch()
        }
        connection.prepareStatement("UPDATE constraint_family_enrollments SET cursor_sequence=?,cursor_deletion_generation=?,event_count=event_count+? WHERE available=1").use { p ->
            p.setLong(1, current.sequence); p.setLong(2, current.deletionGeneration); p.setLong(3, proofs.size.toLong()); p.executeUpdate()
        }
    }

    private fun checkCapacity(newRows: Long, newBytes: Long) {
        connection.createStatement().use { s ->
            s.executeQuery("SELECT (SELECT count(*) FROM constraint_material_events)," +
                "(SELECT coalesce(sum(length(CAST(proof_json AS BLOB))),0) FROM constraint_material_events)+" +
                "(SELECT coalesce(sum(length(CAST(baseline_proof_json AS BLOB))),0) FROM constraint_family_enrollments)").use { r ->
                check(r.next())
                if (r.getLong(1) + newRows > 50_000 || r.getLong(2) + newBytes > 8 * 1024 * 1024) throw ConstraintProofLimit()
            }
        }
    }

    fun capture(plan: AuthoredPlan?, frontier: ConstraintMutationFrontier): CapturedConstraintChanges {
        val family = plan?.let(::family) ?: return CapturedConstraintChanges(null, emptyList(), false)
        val enrollment = enrollment(family) ?: return CapturedConstraintChanges(null, emptyList(), false)
        if (!enrollment.available || !frontier.available || enrollment.cursorSequence != frontier.sequence ||
            enrollment.cursorDeletionGeneration != frontier.deletionGeneration) return CapturedConstraintChanges(enrollment, emptyList(), false)
        val budget = ConstraintProofBudget()
        val events = try {
            connection.prepareStatement("SELECT event_id,mutation_sequence,proof_json,length(CAST(proof_json AS BLOB)) FROM constraint_material_events WHERE family_key=? ORDER BY mutation_sequence,event_id LIMIT 50001").use { p ->
                p.setString(1, family.key()); p.executeQuery().use { r -> buildList<CapturedConstraintEvent> { while (r.next()) {
                    budget.work(); budget.bytes(r.getLong(4))
                    val proof = r.getString(3) ?: return CapturedConstraintChanges(enrollment, emptyList(), false)
                    add(CapturedConstraintEvent(r.getString(1), r.getLong(2), json.decodeFromString(proof)))
                } } }
            }
        } catch (_: ConstraintProofLimit) { return CapturedConstraintChanges(enrollment, emptyList(), false) }
        return CapturedConstraintChanges(enrollment, events, events.size.toLong() == enrollment.eventCount)
    }

    /** Preview binds every purgeable proof version; deletion conservatively invalidates all families. */
    fun deletionBinding(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (sql in listOf("SELECT * FROM constraint_family_enrollments ORDER BY family_key", "SELECT * FROM constraint_material_events ORDER BY event_id")) {
            connection.createStatement().use { s -> s.executeQuery(sql).use { r -> while (r.next()) {
                for (column in 1..r.metaData.columnCount) {
                    val value = r.getString(column)
                    digest.update((value?.let { "${it.length}:$it" } ?: "-1:").toByteArray(Charsets.UTF_8))
                }
                digest.update(10.toByte())
            } } }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
