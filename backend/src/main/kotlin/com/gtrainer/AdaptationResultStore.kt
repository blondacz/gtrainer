package com.gtrainer

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.encodeToString

/** SQL only. Indexed factual snapshots outlive bounded queue history, never silently overwritten or pruned. */
internal class AdaptationResultStore(private val connection: Connection) {
    private val json=ScopedEvidencePreparation.json
    fun get(key: String): StoredAdaptationResult? = connection.prepareStatement(
        "SELECT work_json,snapshot_id,valid_until_utc FROM adaptation_results WHERE work_sha256=?").use { p ->
        require(key.matches(Regex("[a-f0-9]{64}")));p.setString(1,key)
        p.executeQuery().use { r ->
            if(!r.next()) null else {
                val work=json.decodeFromString<AdaptationWorkVersion>(r.getString(1));check(work.key()==key)
                val snapshot=ScopedEvidenceStore(connection).get(r.getString(2)).snapshot
                check(snapshot.state=="PREPARED" && snapshot.packet?.adaptation?.workKey==key)
                StoredAdaptationResult(work,snapshot,Instant.parse(r.getString(3)))
            }
        }
    }
    fun insert(work: AdaptationWorkVersion,snapshot: ScopedEvidenceSnapshot,validUntil: Instant) {
        require(snapshot.state=="PREPARED" && snapshot.packet?.adaptation?.workKey==work.key())
        connection.prepareStatement("INSERT INTO adaptation_results(work_sha256,family_sha256,snapshot_id,work_json,valid_until_utc) VALUES(?,?,?,?,?)").use { p ->
            p.setString(1,work.key());p.setString(2,work.family.key());p.setString(3,snapshot.snapshotId)
            p.setString(4,json.encodeToString(work));p.setString(5,validUntil.toString());p.executeUpdate()
        }
    }
    fun deletionBinding(): String {
        val rows=connection.createStatement().use { s -> s.executeQuery("SELECT work_sha256,snapshot_id,work_json,valid_until_utc FROM adaptation_results ORDER BY work_sha256 LIMIT 1001").use { r ->
            buildList { while(r.next()) { require(size<1000);add((1..4).map { r.getString(it) }) } }
        } }
        return AnalysisClaims.hash(json.encodeToString(rows))
    }
}
