package com.gtrainer

import java.sql.Connection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class ConstraintSubjectRef(val kind: String, val id: String, val revision: Int, val present: Boolean) {
    init {
        require(kind in setOf("context", "intake", "commitment"))
        validateContextId(id)
        require(revision in 1..1000)
    }
    override fun toString() = "ConstraintSubjectRef([REDACTED])"
}

@Serializable
internal data class ConstraintMutationFrontier(val sequence: Long, val deletionGeneration: Long, val retainedFromSequence: Long,val available:Boolean=true) {
    init { require(sequence >= 0 && deletionGeneration >= 0 && retainedFromSequence >= 1 && retainedFromSequence <= if (sequence == Long.MAX_VALUE) Long.MAX_VALUE else sequence + 1) }
    override fun toString() = "ConstraintMutationFrontier([REDACTED])"
}

@Serializable
internal data class ConstraintMutationRecord(val sequence: Long, val refs: List<ConstraintSubjectRef>) {
    init { require(sequence > 0 && refs.size in 1..3000 && refs == refs.sortedWith(refOrder) && refs.distinctBy { it.kind to it.id }.size == refs.size) }
    override fun toString() = "ConstraintMutationRecord([REDACTED])"
}

internal data class ConstraintMutationCoverage(
    val after: Long, val expectedDeletionGeneration: Long, val frontier: ConstraintMutationFrontier,
    val records: List<ConstraintMutationRecord>
) {
    override fun toString() = "ConstraintMutationCoverage([REDACTED])"
    fun complete(): Boolean {
        if (!frontier.available || after < 0 || expectedDeletionGeneration < 0 || expectedDeletionGeneration != frontier.deletionGeneration || after > frontier.sequence) return false
        if (frontier.retainedFromSequence - 1 > after) return false
        val count = frontier.sequence - after
        if (count < 0 || count > 50_000) return false
        return records.size.toLong() == count && records.withIndex().all { (i, r) -> r.sequence == after + i + 1 }
    }
}

private val refOrder = compareBy<ConstraintSubjectRef>({ it.kind }, { it.id })

/** SQL-only ancestry helper. The caller exclusively owns transaction boundaries. */
internal class ConstraintMutationStore(private val connection: Connection) {
    private val json = ScopedEvidencePreparation.json

    fun frontier(): ConstraintMutationFrontier = connection.createStatement().use { s ->
        s.executeQuery("SELECT mutation_sequence,deletion_generation,retained_from_sequence,typeof(mutation_sequence),typeof(deletion_generation),typeof(retained_from_sequence),available,typeof(available) FROM constraint_observation_metadata WHERE singleton=1").use { r ->
            check(r.next()) { "Constraint mutation metadata is not seeded" }
            check((4..6).all { r.getString(it)=="integer" })
            check(r.getString(8)=="integer" && r.getInt(7) in 0..1)
            val result = ConstraintMutationFrontier(r.getLong(1), r.getLong(2), r.getLong(3),r.getInt(7)==1)
            check(!r.next())
            result
        }
    }

    fun inventory(): List<ConstraintSubjectRef> = connection.createStatement().use { s ->
        s.executeQuery("SELECT kind,identity,revision,present FROM constraint_observation_inventory ORDER BY kind,identity LIMIT 100001").use { r ->
            buildList {
                while (r.next()) add(ConstraintSubjectRef(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4).also { check(it in 0..1) } == 1))
            }.also { require(it.size <= 100_000) }
        }
    }

    fun append(refs: List<ConstraintSubjectRef>): ConstraintMutationFrontier {
        if (refs.isEmpty()) return frontier()
        require(refs.size <= 3000)
        val sorted = refs.sortedWith(refOrder)
        require(sorted.distinctBy { it.kind to it.id }.size == sorted.size)
        val encoded = json.encodeToString(sorted)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 512 * 1024)
        val old = frontier()
        if(!old.available) {
            // Terminal unavailability never blocks source deletion or pretends to record
            // complete ancestry. Maintain only already-known opaque inventory refs.
            connection.prepareStatement("UPDATE constraint_observation_inventory SET revision=max(revision,?),present=? WHERE kind=? AND identity=?").use { p ->
                sorted.forEach { ref -> p.setInt(1,ref.revision);p.setInt(2,if(ref.present) 1 else 0);p.setString(3,ref.kind);p.setString(4,ref.id);p.addBatch() };p.executeBatch()
            }
            return old
        }
        check(old.sequence < Long.MAX_VALUE) { "Constraint mutation sequence exhausted" }
        val currentCount = connection.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM constraint_observation_inventory").use { r -> r.next(); r.getLong(1) } }
        require(currentCount <= 100_000)
        val missing = sorted.count { ref -> connection.prepareStatement("SELECT 1 FROM constraint_observation_inventory WHERE kind=? AND identity=?").use { p -> p.setString(1, ref.kind); p.setString(2, ref.id); p.executeQuery().use { !it.next() } } }
        require(currentCount + missing <= 100_000) { "Constraint inventory capacity exceeded" }
        for (ref in sorted) {
            val existing = connection.prepareStatement("SELECT revision FROM constraint_observation_inventory WHERE kind=? AND identity=?").use { p -> p.setString(1, ref.kind); p.setString(2, ref.id); p.executeQuery().use { r -> if (r.next()) r.getInt(1) else null } }
            require(existing == null || ref.revision >= existing) { "Constraint revision rewind" }
        }
        connection.prepareStatement("UPDATE constraint_observation_metadata SET mutation_sequence=? WHERE singleton=1 AND mutation_sequence=? AND deletion_generation=? AND retained_from_sequence=? AND available=1").use { p ->
            p.setLong(1, old.sequence + 1); p.setLong(2, old.sequence); p.setLong(3, old.deletionGeneration); p.setLong(4, old.retainedFromSequence)
            check(p.executeUpdate() == 1) { "Constraint mutation frontier changed" }
        }
        connection.prepareStatement("INSERT INTO constraint_observation_mutations(sequence,refs_json) VALUES(?,?)").use { p -> p.setLong(1, old.sequence + 1); p.setString(2, encoded); p.executeUpdate() }
        connection.prepareStatement("INSERT INTO constraint_observation_inventory(kind,identity,revision,present) VALUES(?,?,?,?) ON CONFLICT(kind,identity) DO UPDATE SET revision=excluded.revision,present=excluded.present").use { p ->
            sorted.forEach { ref -> p.setString(1, ref.kind); p.setString(2, ref.id); p.setInt(3, ref.revision); p.setInt(4, if (ref.present) 1 else 0); p.addBatch() }; p.executeBatch()
        }
        val retained = if (old.sequence + 1 > 50_000) old.sequence + 1 - 50_000 + 1 else old.retainedFromSequence
        if (retained > old.retainedFromSequence) {
            connection.prepareStatement("UPDATE constraint_observation_metadata SET retained_from_sequence=? WHERE singleton=1 AND mutation_sequence=?").use { p -> p.setLong(1, retained); p.setLong(2, old.sequence + 1); check(p.executeUpdate() == 1) }
            connection.prepareStatement("DELETE FROM constraint_observation_mutations WHERE sequence<?").use { p -> p.setLong(1, retained); p.executeUpdate() }
        }
        return frontier()
    }

    fun invalidateForDeletion(): ConstraintMutationFrontier {
        val old = frontier()
        if(!old.available || old.sequence==Long.MAX_VALUE || old.deletionGeneration==Long.MAX_VALUE) {
            connection.createStatement().use { s ->
                s.executeUpdate("UPDATE constraint_observation_metadata SET available=0 WHERE singleton=1")
                s.executeUpdate("DELETE FROM constraint_observation_mutations")
            }
            return frontier()
        }
        val retained = if (old.sequence == Long.MAX_VALUE) Long.MAX_VALUE else old.sequence + 1
        connection.prepareStatement("UPDATE constraint_observation_metadata SET deletion_generation=?,retained_from_sequence=? WHERE singleton=1 AND mutation_sequence=? AND deletion_generation=? AND retained_from_sequence=? AND available=1").use { p ->
            p.setLong(1, old.deletionGeneration + 1); p.setLong(2, retained); p.setLong(3, old.sequence); p.setLong(4, old.deletionGeneration); p.setLong(5, old.retainedFromSequence)
            check(p.executeUpdate() == 1) { "Constraint mutation frontier changed" }
        }
        connection.createStatement().use { it.executeUpdate("DELETE FROM constraint_observation_mutations") }
        return frontier()
    }

    fun capture(after: Long, expectedDeletionGeneration: Long): ConstraintMutationCoverage {
        require(after >= 0 && expectedDeletionGeneration >= 0)
        val f = frontier()
        if(!f.available) return ConstraintMutationCoverage(after,expectedDeletionGeneration,f,emptyList())
        var bytes=0L;var references=0L
        val rows = connection.prepareStatement("SELECT sequence,refs_json,length(CAST(refs_json AS BLOB)) FROM constraint_observation_mutations WHERE sequence>? ORDER BY sequence LIMIT 50001").use { p ->
            p.setLong(1, after); p.executeQuery().use { r -> buildList { while (r.next()) {
                val rowBytes=r.getLong(3)
                bytes+=rowBytes;require(rowBytes<=512*1024 && bytes<=8*1024*1024) { "Constraint mutation capture exceeds byte budget" }
                val encoded=r.getString(2)
                val record=ConstraintMutationRecord(r.getLong(1),json.decodeFromString(encoded))
                references+=record.refs.size;require(references<=50_000) { "Constraint mutation capture exceeds reference budget" }
                add(record)
            } } }
        }
        require(rows.size <= 50_000) { "Constraint mutation coverage exceeds record budget" }
        return ConstraintMutationCoverage(after, expectedDeletionGeneration, f, rows)
    }
}
