package com.gtrainer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** SQL only: HistoryStore owns the connection, monitor and complete confirmation transaction. */
internal class CommitmentStore(private val connection: Connection) {
    private val json=Json { encodeDefaults=true }
    private val select="SELECT c.commitment_id,c.revision,c.intake_id,c.intake_revision,c.context_id,c.context_revision," +
        "c.event_id,c.definition_json,c.entered_by,c.confirmed_utc,i.author_attribution,i.source_category " +
        "FROM commitment_revisions c JOIN context_intakes i ON c.intake_id=i.intake_id AND c.intake_revision=i.revision "

    private fun record(rows: ResultSet): ConfirmedCommitment {
        val id=rows.getString(1)
        val intakeId=rows.getString(3)
        val intakeRevision=rows.getInt(4)
        val contextId=rows.getString(5)
        val contextRevision=rows.getObject(6)?.let { (it as Number).toInt() }
        val sourceChanged=ContextIntakeStore(connection).currentRevision(intakeId)!=intakeRevision
        val contextChanged=if(contextId==null) false else {
            val current=ContextRows(connection).current(contextId)
            current.revision!=contextRevision || ContextLifecycleStore(connection).view(current,
                Instant.parse(rows.getString(10)).atZone(ZoneOffset.UTC).toLocalDate()).sourceChanged
        }
        return ConfirmedCommitment(CommitmentEvidenceRef(id,rows.getInt(2),intakeId,intakeRevision,contextId,contextRevision),
            json.decodeFromString(rows.getString(8)),rows.getString(7),rows.getString(11),rows.getString(12),
            rows.getString(9),rows.getString(10),sourceChanged,contextChanged,sourceChanged||contextChanged)
    }

    fun list(): List<ConfirmedCommitment> = connection.createStatement().use { s ->
        s.executeQuery(select+"WHERE (c.commitment_id,c.revision) IN " +
            "(SELECT commitment_id,max(revision) FROM commitment_revisions GROUP BY commitment_id) ORDER BY c.commitment_id LIMIT 1001").use { rows ->
            buildList { while(rows.next()) { require(size<1000); add(record(rows)) } }
        }
    }

    fun history(id: String): List<ConfirmedCommitment> {
        validateContextId(id)
        return connection.prepareStatement(select+"WHERE c.commitment_id=? ORDER BY c.revision DESC LIMIT 1001").use {
            it.setString(1,id); it.executeQuery().use { rows -> buildList { while(rows.next()) { require(size<1000); add(record(rows)) } } }
        }.also { if(it.isEmpty()) throw CommitmentNotFound() }
    }

    fun current(id: String): ConfirmedCommitment {
        validateContextId(id)
        return connection.prepareStatement(select+"WHERE c.commitment_id=? ORDER BY c.revision DESC LIMIT 1").use {
            it.setString(1,id); it.executeQuery().use { rows -> if(rows.next()) record(rows) else throw CommitmentNotFound() }
        }
    }

    fun revision(id: String, revision: Int): ConfirmedCommitment {
        validateContextId(id); require(revision in 1..1000)
        return connection.prepareStatement(select+"WHERE c.commitment_id=? AND c.revision=?").use {
            it.setString(1,id); it.setInt(2,revision); it.executeQuery().use { rows -> if(rows.next()) record(rows) else throw CommitmentNotFound() }
        }
    }

    fun confirm(request: CommitmentConfirmation, at: Instant): ConfirmedCommitment {
        val count=connection.createStatement().use { it.executeQuery("SELECT count(DISTINCT commitment_id) FROM commitment_revisions").use { r -> r.next(); r.getInt(1) } }
        require(count<1000)
        return insert(UUID.randomUUID().toString(),1,request,at)
    }

    fun correct(id: String, request: CommitmentCorrection, at: Instant): ConfirmedCommitment {
        val current=current(id)
        if(current.reference.revision!=request.expectedCommitmentRevision) throw CommitmentConflict()
        return insert(id,request.expectedCommitmentRevision+1,request.confirmation,at)
    }

    private fun insert(id: String, revision: Int, request: CommitmentConfirmation, at: Instant): ConfirmedCommitment {
        if(ContextIntakeStore(connection).currentRevision(request.intakeId)!=request.expectedIntakeRevision) throw CommitmentConflict()
        if(request.contextId!=null) {
            val current=ContextRows(connection).current(request.contextId)
            if(current.revision!=request.expectedContextRevision || ContextLifecycleStore(connection)
                    .view(current,at.atZone(ZoneOffset.UTC).toLocalDate()).sourceChanged) throw CommitmentConflict()
        }
        if(request.eventId!=null) connection.prepareStatement("SELECT 1 FROM events WHERE id=?").use {
            it.setString(1,request.eventId); it.executeQuery().use { rows -> if(!rows.next()) throw EventNotFound() }
        }
        connection.prepareStatement("INSERT INTO commitment_revisions(commitment_id,revision,intake_id,intake_revision,context_id," +
            "context_revision,event_id,definition_json,entered_by,confirmed_utc) VALUES(?,?,?,?,?,?,?,?,'authenticated_user',?)").use {
            it.setString(1,id); it.setInt(2,revision); it.setString(3,request.intakeId); it.setInt(4,request.expectedIntakeRevision)
            it.setString(5,request.contextId); it.setObject(6,request.expectedContextRevision); it.setString(7,request.eventId)
            it.setString(8,json.encodeToString(request.definition)); it.setString(9,at.toString()); it.executeUpdate()
        }
        return current(id)
    }
}
