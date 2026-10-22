package com.gtrainer

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** Called only under HistoryStore's monitor and, for writes, its transaction. Owns neither. */
internal class ContextIntakeStore(private val connection: Connection) {
    private val columns = "intake_id,revision,content,source_category,author_attribution,entered_by,observed_on,recorded_utc"
    private fun intake(row: ResultSet) = ContextIntake(row.getString(1), row.getInt(2), row.getString(3),
        row.getString(4), row.getString(5), row.getString(6), row.getString(7), row.getString(8))

    fun list(): List<ContextIntake> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT $columns FROM context_intakes WHERE (intake_id,revision) IN " +
            "(SELECT intake_id,max(revision) FROM context_intakes GROUP BY intake_id) ORDER BY intake_id LIMIT 1001").use { rows ->
            buildList { while (rows.next()) { require(size < 1000); add(intake(rows)) } }
        }
    }

    fun history(id: String): List<ContextIntake> {
        validateContextId(id)
        return connection.prepareStatement("SELECT $columns FROM context_intakes WHERE intake_id=? ORDER BY revision DESC LIMIT 1001").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> buildList { while (rows.next()) { require(size < 1000); add(intake(rows)) } } }
        }.also { if (it.isEmpty()) throw ContextIntakeNotFound() }
    }

    fun currentRevision(id: String): Int {
        validateContextId(id)
        return connection.prepareStatement("SELECT max(revision) FROM context_intakes WHERE intake_id=?").use {
            it.setString(1,id); it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }.also { if(it==0) throw ContextIntakeNotFound() }
    }

    fun create(request: ContextIntakeRequest, at: Instant): ContextIntake {
        val count = connection.createStatement().use { it.executeQuery("SELECT count(DISTINCT intake_id) FROM context_intakes").use { r -> r.next(); r.getInt(1) } }
        require(count < 1000)
        return insert(UUID.randomUUID().toString(), 1, request, at)
    }

    fun correct(id: String, request: ContextIntakeCorrection, at: Instant): ContextIntake {
        val current = currentRevision(id)
        if (current != request.expectedRevision) throw ContextIntakeConflict()
        return insert(id, Math.addExact(current, 1), request.note, at)
    }

    private fun insert(id: String, revision: Int, request: ContextIntakeRequest, at: Instant): ContextIntake {
        connection.prepareStatement("INSERT INTO context_intakes($columns) VALUES(?,?,?,?,?,'authenticated_user',?,?)").use {
            it.setString(1, id); it.setInt(2, revision); it.setString(3, request.content); it.setString(4, request.sourceCategory)
            it.setString(5, request.authorAttribution); it.setString(6, request.observedOn); it.setString(7, at.toString())
            it.executeUpdate()
        }
        return ContextIntake(id, revision, request.content, request.sourceCategory, request.authorAttribution,
            "authenticated_user", request.observedOn, at.toString())
    }

    fun links(id: String): List<ContextIntakeLink> {
        val currentRevision = currentRevision(id)
        return connection.prepareStatement("SELECT intake_id,intake_revision,context_id,context_revision FROM context_intake_links " +
            "WHERE intake_id=? ORDER BY intake_revision,context_id,context_revision LIMIT 1001").use {
            it.setString(1, id)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) { require(size < 1000); add(ContextIntakeLink(rows.getString(1),rows.getInt(2),rows.getString(3),rows.getInt(4),rows.getInt(2) != currentRevision)) }
            } }
        }
    }

    /** Explicit provenance association only. Never creates or mutates a structured assertion. */
    fun associate(id: String, request: ContextIntakeAssociation, allowRetired: Boolean = false): ContextIntakeLink {
        if (currentRevision(id) != request.expectedRevision) throw ContextIntakeConflict()
        connection.prepareStatement("SELECT revision,retired FROM athlete_context WHERE context_id=? ORDER BY revision DESC LIMIT 1").use {
            it.setString(1, request.contextId)
            it.executeQuery().use { row ->
                if (!row.next()) throw ContextNotFound()
                if (row.getInt(1) != request.contextRevision || row.getInt(2) != 0 && !allowRetired) throw ContextIntakeConflict()
            }
        }
        val link = ContextIntakeLink(id, request.expectedRevision, request.contextId, request.contextRevision)
        val existing = links(id)
        if (link in existing) return link
        require(existing.size < 1000)
        val prior = connection.prepareStatement("SELECT 1 FROM context_intake_links WHERE context_id=? AND context_revision=?").use {
            it.setString(1,request.contextId); it.setInt(2,request.contextRevision); it.executeQuery().use { r -> r.next() }
        }
        if (prior) throw ContextIntakeConflict()
        connection.prepareStatement("INSERT INTO context_intake_links(intake_id,intake_revision,context_id,context_revision) VALUES(?,?,?,?)").use {
            it.setString(1,id); it.setInt(2,request.expectedRevision); it.setString(3,request.contextId); it.setInt(4,request.contextRevision); it.executeUpdate()
        }
        return link
    }

    fun requireUnlinkedContext(id: String) {
        val calendarLinked=connection.prepareStatement("SELECT 1 FROM commitment_revisions WHERE context_id=? LIMIT 1").use {
            it.setString(1,id); it.executeQuery().use { rows -> rows.next() }
        }
        if(calendarLinked) throw ContextIntakeConflict()
        val linked = connection.prepareStatement("SELECT 1 FROM context_intake_links WHERE context_id=? LIMIT 1").use {
            it.setString(1, id); it.executeQuery().use { rows -> rows.next() }
        }
        if (linked) throw ContextIntakeConflict()
    }

    fun invalidateReviews(id: String) {
        connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='STALE',stale_reason='context_changed' " +
            "WHERE state IN ('IN_FLIGHT','RUNNING','PUBLISHED') AND snapshot_id IN " +
            "(SELECT r.snapshot_id FROM connected_review_context_refs r JOIN context_intake_links l ON r.context_id=l.context_id WHERE l.intake_id=?)").use {
            it.setString(1,id); it.executeUpdate()
        }
    }
}
