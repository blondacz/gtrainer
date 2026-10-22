package com.gtrainer

import kotlinx.serialization.encodeToString
import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal data class StoredScopedEvidence(val request: ScopedEvidenceRequest, val bindingSha256: String,
    val snapshot: ScopedEvidenceSnapshot, val totalChanges: Long = 0) {
    override fun toString()="StoredScopedEvidence([REDACTED])"
}

/** SQL only. HistoryStore owns connection, synchronization and the complete publication transaction. */
internal class ScopedEvidenceStore(private val connection: Connection) {
    private val json=ScopedEvidencePreparation.json

    fun insert(prepared: PreparedScopedEvidence, at: Instant): ScopedEvidenceSnapshot {
        connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM scoped_evidence_snapshots").use {
            it.next(); require(it.getInt(1)<1000) { "Evidence snapshot capacity reached" }
        } }
        val id=UUID.randomUUID().toString()
        val packetJson=prepared.packetJson
        val packetHash=packetJson?.let(AnalysisClaims::hash)
        val manifestJson=prepared.manifestJson
        connection.prepareStatement("INSERT INTO scoped_evidence_snapshots(snapshot_id,request_json,binding_sha256,packet_sha256,packet_json,manifest_json,created_utc) VALUES(?,?,?,?,?,?,?)").use {
            it.setString(1,id);it.setString(2,prepared.requestJson);it.setString(3,prepared.bindingSha256)
            it.setString(4,packetHash);it.setString(5,packetJson);it.setString(6,manifestJson);it.setString(7,at.toString());it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO scoped_evidence_dependencies(snapshot_id,kind,identity,revision) VALUES(?,?,?,?)").use { statement ->
            prepared.manifest.dependencies.forEach { ref ->
                statement.setString(1,id);statement.setString(2,ref.kind);statement.setString(3,ref.identity);statement.setInt(4,ref.revision);statement.addBatch()
            };statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO scoped_evidence_records(snapshot_id,category,source,source_id,revision,record_sha256,roles_json) VALUES(?,?,?,?,?,?,?)").use { statement ->
            prepared.records.forEach { record ->
                statement.setString(1,id);statement.setString(2,record.category);statement.setString(3,record.source);statement.setString(4,record.sourceRecordId)
                statement.setObject(5,record.revision);statement.setString(6,record.recordSha256);statement.setString(7,record.rolesJson);statement.addBatch()
            }
            statement.executeBatch()
        }
        return ScopedEvidenceSnapshot(id,at.toString(),packetHash,if(prepared.bounded.packet==null) "BLOCKED" else "PREPARED",
            prepared.bounded.packet,prepared.manifest)
    }

    fun get(id: String): StoredScopedEvidence {
        validateContextId(id)
        return connection.prepareStatement("SELECT request_json,binding_sha256,packet_sha256,packet_json,manifest_json,created_utc FROM scoped_evidence_snapshots WHERE snapshot_id=?").use {
            it.setString(1,id);it.executeQuery().use { row ->
                if(!row.next()) throw ScopedEvidenceNotFound()
                val request=json.decodeFromString<ScopedEvidenceRequest>(row.getString(1))
                val encoded=row.getString(4)
                val packet=encoded?.let { bytes -> json.decodeFromString<ScopedEvidencePacket>(bytes) }
                val digest=row.getString(3)
                check(if(encoded==null) digest==null else AnalysisClaims.hash(encoded)==digest)
                val manifest=json.decodeFromString<ScopedEvidenceManifest>(row.getString(5))
                check(manifest.scope.request==request.selection && (packet==null || packet.request==request))
                StoredScopedEvidence(request,row.getString(2),ScopedEvidenceSnapshot(id,row.getString(6),digest,
                    if(packet==null) "BLOCKED" else "PREPARED",packet,manifest))
            }
        }
    }

    fun records(id: String,offset: Int,limit: Int): ScopedEvidenceRecords {
        validateContextId(id);require(offset in 0..50_000 && limit in 1..50)
        get(id) // A purged snapshot cannot be inspected through orphan membership.
        val rows=connection.prepareStatement("SELECT category,source,source_id,revision,record_sha256,roles_json FROM scoped_evidence_records " +
            "WHERE snapshot_id=? ORDER BY category,source,source_id LIMIT ? OFFSET ?").use { statement ->
            statement.setString(1,id);statement.setInt(2,limit+1);statement.setInt(3,offset)
            statement.executeQuery().use { members -> buildList { while(members.next()) {
                val category=members.getString(1);check(category in setOf("activities","wellness"))
                val source=members.getString(2);val sourceId=members.getString(3)
                val revision=(members.getObject(4) as Number?)?.toLong()
                val digest=members.getString(5)
                val roles=json.decodeFromString<List<String>>(members.getString(6))
                val current=connection.prepareStatement("SELECT record_json FROM $category WHERE source=? AND source_id=?").use {
                    it.setString(1,source);it.setString(2,sourceId);it.executeQuery().use { row -> if(row.next()) row.getString(1) else null }
                }
                val activity=if(category=="activities") current?.let { json.decodeFromString<ActivityRecord>(it) } else null
                val wellness=if(category=="wellness") current?.let { json.decodeFromString<WellnessRecord>(it) } else null
                val encoded=activity?.let { json.encodeToString(it) } ?: wellness?.let { json.encodeToString(it) }
                val currentRevision=if(encoded==null) null else ScopedHistoryReads.provenance(connection,
                    HistoryResponse(listOfNotNull(activity),listOfNotNull(wellness))).single().revision
                val status=if(encoded==null) "unavailable" else if(AnalysisClaims.hash(encoded)==digest && currentRevision==revision) "unchanged" else "changed_since_snapshot"
                add(ScopedEvidenceRecord(category,source,sourceId,revision,status,
                    activity.takeIf { status=="unchanged" },wellness.takeIf { status=="unchanged" },roles))
            } } }
        }
        return ScopedEvidenceRecords(id,rows.take(limit),if(rows.size>limit) offset+limit else null)
    }

    fun membership(id: String): List<PreparedScopedRecord> = connection.prepareStatement(
        "SELECT category,source,source_id,revision,record_sha256,roles_json FROM scoped_evidence_records WHERE snapshot_id=? ORDER BY category,source,source_id LIMIT 50001").use { p ->
        p.setString(1,id);p.executeQuery().use { r -> buildList { while(r.next()) {
            require(size<50_000)
            add(PreparedScopedRecord(r.getString(1),r.getString(2),r.getString(3),(r.getObject(4) as Number?)?.toLong(),r.getString(5),r.getString(6)))
        } } }
    }
}
