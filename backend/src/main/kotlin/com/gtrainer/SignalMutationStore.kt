package com.gtrainer

import java.sql.Connection
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val signalMutationFields = setOf("sleepSecs", "hrv", "hrvSDNN")

@Serializable
internal data class SignalEvidenceMutation(
    val sequence: Long,
    val source: String,
    val identitySha256: String,
    val oldDate: String?,
    val newDate: String,
    val fields: List<String>
) {
    init {
        require(sequence > 0)
        require(source.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        require(identitySha256.matches(Regex("[0-9a-f]{64}")))
        oldDate?.let(::validateContextDate)
        validateContextDate(newDate)
        require(fields.isNotEmpty() && fields.size <= 3 && fields == fields.distinct().sorted() && fields.all { it in signalMutationFields })
    }

    override fun toString() = "SignalEvidenceMutation([REDACTED])"
}

internal data class SignalMutationCoverage(
    val after: Long,
    val through: Long,
    val oldestRetained: Long?,
    val mutations: List<SignalEvidenceMutation>,
    val removalGeneration: Long,
    val expectedRemovalGeneration: Long,
) {
    override fun toString()="SignalMutationCoverage([REDACTED])"
    fun complete(): Boolean {
        if(removalGeneration<0 || removalGeneration!=expectedRemovalGeneration) return false
        if (after < 0 || through < 0 || after > through) return false
        if (through == after) return mutations.isEmpty()
        if (oldestRetained != null && oldestRetained > after + 1) return false
        if (oldestRetained == null || oldestRetained > after + 1) return false
        if (through - after > 50_000) return false
        return mutations.size.toLong() == through - after &&
            mutations.withIndex().all { (index, mutation) -> mutation.sequence == after + index + 1 }
    }

    fun affects(field: String, source: String, oldest: String, newest: String): Boolean {
        require(field in signalMutationFields)
        require(source.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        validateContextDate(oldest); validateContextDate(newest)
        require(oldest <= newest)
        return !complete() || mutations.any { mutation ->
            mutation.source == source && field in mutation.fields &&
                (mutation.oldDate?.let { it in oldest..newest } == true || mutation.newDate in oldest..newest)
        }
    }
}

/** Append-only bounded mutation ancestry. Transaction and connection ownership stay with caller. */
internal class SignalMutationStore(private val connection: Connection) {
    private val json = ScopedEvidencePreparation.json

    fun seed() { connection.createStatement().use { it.executeUpdate("INSERT INTO signal_evidence_epoch(singleton,removal_generation) VALUES(1,0)") } }

    fun removalGeneration(): Long = connection.createStatement().use { s ->
        s.executeQuery("SELECT removal_generation FROM signal_evidence_epoch WHERE singleton=1").use { r -> check(r.next());r.getLong(1) }
    }

    fun remove() {
        check(removalGeneration()<Long.MAX_VALUE)
        connection.createStatement().use { s ->
            check(s.executeUpdate("UPDATE signal_evidence_epoch SET removal_generation=removal_generation+1 WHERE singleton=1")==1)
            s.executeUpdate("DELETE FROM signal_evidence_mutations")
        }
    }

    fun record(source: String, identitySha256: String, oldDate: String?, newDate: String, fields: Set<String>) {
        require(source.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        require(identitySha256.matches(Regex("[0-9a-f]{64}")))
        oldDate?.let(::validateContextDate); validateContextDate(newDate)
        require(fields.all { it in signalMutationFields })
        if (fields.isEmpty()) return
        val sorted = fields.sorted()
        connection.prepareStatement("INSERT INTO signal_evidence_mutations(source,identity_sha256,old_date,new_date,fields_json) VALUES(?,?,?,?,?)").use { s ->
            s.setString(1, source); s.setString(2, identitySha256); s.setString(3, oldDate); s.setString(4, newDate)
            s.setString(5, json.encodeToString(sorted)); s.executeUpdate()
        }
        connection.createStatement().use { it.executeUpdate("DELETE FROM signal_evidence_mutations WHERE sequence <= (SELECT max(sequence) - 50000 FROM signal_evidence_mutations)") }
    }

    fun capture(after: Long,expectedRemovalGeneration: Long): SignalMutationCoverage {
        require(after >= 0 && expectedRemovalGeneration>=0)
        val removalGeneration=removalGeneration()
        val (through, oldest) = connection.createStatement().use { s ->
            // AUTOINCREMENT's durable frontier survives deletion. Emptying the ring cannot
            // masquerade as an untouched history or reuse an earlier continuation frontier.
            s.executeQuery("SELECT coalesce((SELECT seq FROM sqlite_sequence WHERE name='signal_evidence_mutations'),0),min(sequence) FROM signal_evidence_mutations").use { r ->
                r.next(); r.getLong(1) to r.getLong(2).let { if (r.wasNull()) null else it }
            }
        }
        val rows = connection.prepareStatement("SELECT sequence,source,identity_sha256,old_date,new_date,fields_json FROM signal_evidence_mutations WHERE sequence>? ORDER BY sequence LIMIT 50001").use { s ->
            s.setLong(1, after); s.executeQuery().use { r -> buildList {
                while (r.next()) {
                    val fields = json.decodeFromString<List<String>>(r.getString(6))
                    add(SignalEvidenceMutation(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), fields))
                }
            } }
        }
        require(rows.size<=50_000) { "Signal mutation coverage exceeds the record budget" }
        return SignalMutationCoverage(after, through, oldest, rows,removalGeneration,expectedRemovalGeneration)
    }
}
