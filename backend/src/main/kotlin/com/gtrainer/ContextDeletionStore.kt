package com.gtrainer

import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset

/** Read previews and atomic redaction SQL; connection/monitor/transaction always belong to HistoryStore. */
internal class ContextDeletionStore(private val connection: Connection) {
    private val rows=ContextRows(connection)
    private val intakes=ContextIntakeStore(connection)
    private val calendar=CommitmentStore(connection)
    private fun origin(context: AthleteContext): ContextIntakeLink? = connection.prepareStatement(
        "SELECT intake_id,intake_revision FROM context_intake_links WHERE context_id=? AND context_revision=?").use {
        it.setString(1,context.contextId); it.setInt(2,context.revision)
        it.executeQuery().use { r -> if(r.next()) ContextIntakeLink(r.getString(1),r.getInt(2),context.contextId,context.revision) else null }
    }

    fun preview(id: String, at: Instant): ContextDeletionPreview {
        val graph=contextDeletionGraph(connection,id)
        val survivors=graph.contextIds.filter { it !in graph.deletedContextIds }.map(rows::current)
        return ContextDeletionPreview(id,rows.current(id).revision,graph.digest,graph.sourceIds.map { intakes.history(it).first() },
            survivors.map { ContextLifecycleStore(connection).view(it,at.atZone(ZoneOffset.UTC).toLocalDate()) },survivors.mapNotNull(::origin),
            graph.commitmentIds.map(calendar::current),graph.deletedContextIds,graph.snapshotIds,graph.receiptIds.size,graph.executionIds,graph.proposalIds,graph.retainedApprovalOperationIds)
    }

    fun apply(id: String, request: ContextDeletionRequest, at: Instant): ContextDeletionResult {
        val graph=contextDeletionGraph(connection,id)
        if(rows.current(id).revision!=request.expectedRevision || graph.digest!=request.previewDigest) throw ContextDeletionConflict()
        val survivors=graph.contextIds.filter { it !in graph.deletedContextIds }.associateWith(rows::current)
        if(request.survivors.map { it.item.contextId }.toSet()!=survivors.keys || request.commitments.map { it.commitmentId }.toSet()!=graph.commitmentIds.toSet())
            throw ContextDeletionConflict()
        val origins=survivors.mapValues { origin(it.value) }
        val sources=request.sources.associateBy { it.intakeId }
        if(!graph.sourceIds.containsAll(sources.keys)) throw ContextDeletionConflict()
        sources.values.forEach { source -> if(intakes.currentRevision(source.intakeId)!=source.expectedRevision) throw ContextDeletionConflict() }
        request.survivors.forEach { survivor ->
            val current=survivors.getValue(survivor.item.contextId!!)
            if(current.revision!=survivor.item.expectedContextRevision || origins.getValue(current.contextId)?.intakeId!=survivor.intakeId) throw ContextDeletionConflict()
            if(survivor.intakeId!=null) {
                val source=sources[survivor.intakeId] ?: throw ContextDeletionConflict()
                require(survivor.item.context.authorAttribution==source.note.authorAttribution && survivor.item.context.sourceCategory==source.note.sourceCategory)
            }
        }
        request.commitments.forEach { disposition ->
            val current=calendar.current(disposition.commitmentId)
            if(current.reference.revision!=disposition.expectedRevision) throw ContextDeletionConflict()
            disposition.confirmation?.let { confirmation ->
                val source=sources[confirmation.intakeId] ?: throw ContextDeletionConflict()
                if(confirmation.intakeId!=current.reference.intakeId || confirmation.expectedIntakeRevision!=source.expectedRevision+1 ||
                    confirmation.contextId in graph.deletedContextIds || confirmation.eventId!=current.eventId) throw ContextDeletionConflict()
                confirmation.contextId?.let { linked ->
                    val survivor=survivors[linked] ?: throw ContextDeletionConflict()
                    if(confirmation.expectedContextRevision!=survivor.revision+1) throw ContextDeletionConflict()
                }
            }
        }

        // All edges/revisions were bound before the first write. Release old provenance capacity
        // within this same transaction; failure later rolls these removals back as well.
        // Global opaque journal coverage also crosses a deletion generation. Conservatively
        // purge every adaptation proof; never retain old values for unrelated automation.
        ConstraintEventStore(connection).invalidate()
        AdaptationReasonStore(connection).redact()
        PersonalSignalStateStore(connection).invalidateProofs()
        PlanAmendmentProposalStore(connection).purge()
        graph.contextIds.forEach { execute("DELETE FROM context_intake_links WHERE context_id=?",it) }
        val replacedSources=sources.values.associate { source ->
            source.intakeId to intakes.correct(source.intakeId,ContextIntakeCorrection(source.expectedRevision,source.note),at).revision
        }
        val replacedContexts=request.survivors.associate { survivor ->
            val current=survivors.getValue(survivor.item.contextId!!)
            val saved=rows.insert(current.contextId,Math.addExact(current.revision,1),survivor.item.context,current.retired)
            ContextLifecycleStore(connection).confirm(saved,survivor.item.lifecycle)
            survivor.intakeId?.let { source -> intakes.associate(source,ContextIntakeAssociation(replacedSources.getValue(source),saved.contextId,saved.revision),allowRetired=true) }
            saved.contextId to saved.revision
        }
        val replacedCommitments=request.commitments.mapNotNull { disposition -> disposition.confirmation?.let { confirmation ->
            val saved=calendar.correct(disposition.commitmentId,CommitmentCorrection(disposition.expectedRevision,confirmation),at)
            saved.reference.commitmentId to saved.reference.revision
        } }.toMap()

        // Fences retain only opaque IDs and revision numbers, never health text or content hashes.
        graph.contextIds.forEach { contextId ->
            val old=survivors[contextId]?.revision ?: rows.current(contextId).revision
            connection.prepareStatement("INSERT INTO context_deletion_fences(context_id,last_purged_revision) VALUES(?,?) " +
                "ON CONFLICT(context_id) DO UPDATE SET last_purged_revision=max(last_purged_revision,excluded.last_purged_revision)").use {
                it.setString(1,contextId); it.setInt(2,old); it.executeUpdate()
            }
        }
        graph.receiptIds.forEach { execute("DELETE FROM context_confirmation_receipts WHERE confirmation_id=?",it) }
        graph.executionIds.forEach {
            PersonalReviewRuntimeStore(connection).revokeExecution(it)
            execute("DELETE FROM scoped_execution_bindings WHERE execution_id=?",it)
        }
        graph.snapshotIds.forEach {
            execute("DELETE FROM connected_review_snapshots WHERE snapshot_id=?",it)
            execute("DELETE FROM scoped_evidence_snapshots WHERE snapshot_id=?",it)
        }
        graph.commitmentIds.forEach { purge("commitment_revisions","commitment_id",it,replacedCommitments[it]) }
        graph.contextIds.forEach { purge("athlete_context","context_id",it,replacedContexts[it]) }
        graph.sourceIds.forEach { purge("context_intakes","intake_id",it,replacedSources[it]) }
        return ContextDeletionResult(graph.deletedContextIds,replacedContexts.keys.sorted(),replacedCommitments.keys.sorted(),
            graph.commitmentIds.filter { it !in replacedCommitments })
    }

    /** Called inside snapshot creation's owner transaction, after deletion's fences become visible. */
    fun validatePreparedContext(context: AthleteContext) {
        val fenced=connection.prepareStatement("SELECT 1 FROM context_deletion_fences WHERE context_id=?").use {
            it.setString(1,context.contextId); it.executeQuery().use { row -> row.next() }
        }
        if(fenced) {
            val current=try { rows.current(context.contextId) } catch (_: ContextNotFound) { throw ContextRevisionConflict() }
            if(current!=context) throw ContextRevisionConflict()
        }
    }

    private fun execute(sql: String, id: String) = connection.prepareStatement(sql).use { it.setString(1,id); it.executeUpdate() }
    private fun purge(table: String, key: String, id: String, keptRevision: Int?) {
        connection.prepareStatement("DELETE FROM $table WHERE $key=?" + if(keptRevision==null) "" else " AND revision<>?").use {
            it.setString(1,id); keptRevision?.let { revision -> it.setInt(2,revision) }; it.executeUpdate()
        }
    }
}
