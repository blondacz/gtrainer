package com.gtrainer

import java.sql.Connection
import java.time.LocalDate

/** All calls share HistoryStore's monitor/transaction, including context copy and provenance. */
internal class ContextLifecycleStore(private val connection: Connection) {
    private fun state(context: AthleteContext): ContextLifecycle = connection.prepareStatement(
        "SELECT resolution,review_due_on FROM context_lifecycle WHERE context_id=? AND context_revision=?").use {
        it.setString(1,context.contextId); it.setInt(2,context.revision)
        it.executeQuery().use { rows -> if(rows.next()) ContextLifecycle(rows.getString(1),rows.getString(2)) else ContextLifecycle() }
    }

    fun view(context: AthleteContext, asOf: LocalDate): ContextLifecycleView {
        val changed=connection.prepareStatement("SELECT 1 FROM context_intake_links l WHERE l.context_id=? AND l.context_revision=? " +
            "AND l.intake_revision < (SELECT max(i.revision) FROM context_intakes i WHERE i.intake_id=l.intake_id)").use {
            it.setString(1,context.contextId); it.setInt(2,context.revision); it.executeQuery().use { rows -> rows.next() }
        }
        return projectContextLifecycle(context,state(context),changed,asOf)
    }

    fun update(id: String, request: ContextLifecycleUpdate, asOf: LocalDate): ContextLifecycleView {
        val current=ContextRows(connection).history(id).first()
        if(current.revision!=request.expectedContextRevision) throw ContextRevisionConflict()
        val next=ContextRows(connection).appendCopy(current)
        insert(next,ContextLifecycle(request.resolution,request.reviewDueOn))
        copyProvenance(current,next)
        invalidate(id)
        return view(next,asOf)
    }

    fun carry(current: AthleteContext, next: AthleteContext, resetResolution: Boolean = false) {
        val lifecycle=state(current)
        insert(next,if(resetResolution) lifecycle.copy(resolution="unresolved") else lifecycle)
        copyProvenance(current,next)
    }

    private fun insert(context: AthleteContext, lifecycle: ContextLifecycle) {
        connection.prepareStatement("INSERT INTO context_lifecycle(context_id,context_revision,resolution,review_due_on) VALUES(?,?,?,?)").use {
            it.setString(1,context.contextId); it.setInt(2,context.revision); it.setString(3,lifecycle.resolution); it.setString(4,lifecycle.reviewDueOn); it.executeUpdate()
        }
    }

    fun confirm(context: AthleteContext, lifecycle: ContextLifecycle) = insert(context,lifecycle)

    private fun copyProvenance(current: AthleteContext, next: AthleteContext) {
        connection.prepareStatement("SELECT count(*) FROM context_intake_links WHERE intake_id IN " +
            "(SELECT intake_id FROM context_intake_links WHERE context_id=? AND context_revision=?)").use {
            it.setString(1,current.contextId); it.setInt(2,current.revision)
            it.executeQuery().use { rows -> rows.next(); require(rows.getInt(1)<1000) }
        }
        connection.prepareStatement("INSERT INTO context_intake_links(context_id,context_revision,intake_id,intake_revision) " +
            "SELECT context_id,?,intake_id,intake_revision FROM context_intake_links WHERE context_id=? AND context_revision=?").use {
            it.setInt(1,next.revision); it.setString(2,current.contextId); it.setInt(3,current.revision); it.executeUpdate()
        }
    }

    private fun invalidate(id: String) {
        connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='STALE',stale_reason='context_changed' " +
            "WHERE state IN ('IN_FLIGHT','RUNNING','PUBLISHED') AND snapshot_id IN (SELECT snapshot_id FROM connected_review_context_refs WHERE context_id=?)").use {
            it.setString(1,id); it.executeUpdate()
        }
    }
}
