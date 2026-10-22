package com.gtrainer

import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class ContextConfirmationItem(val context: AthleteContextRequest, val lifecycle: ContextLifecycle = ContextLifecycle(),
    val contextId: String? = null, val expectedContextRevision: Int? = null) {
    init {
        require((contextId==null)==(expectedContextRevision==null))
        contextId?.let(::validateContextId); expectedContextRevision?.let { require(it in 1..999) }
    }
    override fun toString()="ContextConfirmationItem([REDACTED])"
}

@Serializable
data class CommitmentConfirmationItem(val definition: CommitmentDefinition, val commitmentId: String? = null,
    val expectedCommitmentRevision: Int? = null, val context: ContextRevisionRefV1? = null, val contextItemIndex: Int? = null) {
    init {
        require((commitmentId==null)==(expectedCommitmentRevision==null))
        commitmentId?.let(::validateContextId); expectedCommitmentRevision?.let { require(it in 1..999) }
        context?.let { validateContextId(it.contextId); require(it.revision in 1..1000) }
        require(contextItemIndex==null || contextItemIndex in 0..19 && context==null)
    }
    override fun toString()="CommitmentConfirmationItem([REDACTED])"
}

/** All items shown to the user are confirmed together, or none are saved. No extraction caller. */
@Serializable
data class ContextConfirmationRequest(val expectedRevision: Int, val contexts: List<ContextConfirmationItem> = emptyList(),
    val commitments: List<CommitmentConfirmationItem> = emptyList(), val confirmationId: String) {
    init {
        require(expectedRevision in 1..1000 && contexts.size+commitments.size in 1..20)
        validateContextId(confirmationId)
        require(contexts.mapNotNull { it.contextId }.let { it.size==it.distinct().size })
        require(commitments.mapNotNull { it.commitmentId }.let { it.size==it.distinct().size })
        require(commitments.all { it.contextItemIndex==null || it.contextItemIndex in contexts.indices })
    }
    override fun toString()="ContextConfirmationRequest([REDACTED])"
}

@Serializable
data class ContextConfirmationResult(val contexts: List<AthleteContext>, val commitments: List<ConfirmedCommitment>, val replayed: Boolean = false) {
    override fun toString()="ContextConfirmationResult([REDACTED])"
}

@Serializable private data class ConfirmedItemRef(val id: String, val revision: Int)
@Serializable private data class ConfirmationRefs(val contexts: List<ConfirmedItemRef>, val commitments: List<ConfirmedItemRef>)

/** Caller owns the monitor/transaction, including inserts, links, lifecycle and invalidation. */
internal class ContextConfirmationStore(private val connection: Connection) {
    private val json=Json { encodeDefaults=true }
    fun confirm(id: String, request: ContextConfirmationRequest, at: Instant): ContextConfirmationResult {
        validateContextId(id)
        val digest=AnalysisClaims.hash(json.encodeToString(request))
        connection.prepareStatement("SELECT intake_id,request_sha256,refs_json FROM context_confirmation_receipts WHERE confirmation_id=?").use {
            it.setString(1,request.confirmationId); it.executeQuery().use { receipt -> if(receipt.next()) {
                if(receipt.getString(1)!=id || receipt.getString(2)!=digest) throw ContextIntakeConflict()
                val refs=json.decodeFromString<ConfirmationRefs>(receipt.getString(3))
                return ContextConfirmationResult(refs.contexts.map { ref -> ContextRows(connection).revision(ref.id,ref.revision) },
                    refs.commitments.map { ref -> CommitmentStore(connection).revision(ref.id,ref.revision) },replayed=true)
            } }
        }
        val intakeStore=ContextIntakeStore(connection)
        val source=intakeStore.history(id).first()
        if(source.revision!=request.expectedRevision) throw ContextIntakeConflict()
        val rows=ContextRows(connection)
        val contexts=request.contexts.map { item ->
            require(item.context.sourceCategory==source.sourceCategory && item.context.authorAttribution==source.authorAttribution)
            val current=item.contextId?.let(rows::current)
            if(current!=null && current.revision!=item.expectedContextRevision) throw ContextRevisionConflict()
            // Reconfirm an exact source identity, never silently move an item between notes.
            if(current!=null) connection.prepareStatement("SELECT intake_id FROM context_intake_links WHERE context_id=? AND context_revision=?").use {
                it.setString(1,current.contextId); it.setInt(2,current.revision); it.executeQuery().use { links ->
                    if(!links.next() || links.getString(1)!=id) throw ContextIntakeConflict()
                }
            }
            if(current==null) rows.requireCapacity()
            val saved=rows.insert(current?.contextId ?: UUID.randomUUID().toString(),(current?.revision ?: 0)+1,item.context,current?.retired ?: false)
            ContextLifecycleStore(connection).confirm(saved,item.lifecycle)
            intakeStore.associate(id,ContextIntakeAssociation(source.revision,saved.contextId,saved.revision),allowRetired=true)
            saved
        }
        val commitments=request.commitments.map { item ->
            val repository=CommitmentStore(connection)
            val original=item.commitmentId?.let(repository::current)
            if(original!=null && original.reference.intakeId!=id) throw CommitmentConflict()
            // Retain an existing optional context/event association; never quietly detach it.
            val confirmedContext=item.contextItemIndex?.let { contexts[it] }
            val confirmation=CommitmentConfirmation(id,source.revision,item.definition,
                confirmedContext?.contextId ?: item.context?.contextId ?: original?.reference?.contextId,
                confirmedContext?.revision ?: item.context?.revision ?: original?.reference?.contextRevision,original?.eventId)
            if(item.commitmentId==null) repository.confirm(confirmation,at)
            else repository.correct(item.commitmentId,CommitmentCorrection(item.expectedCommitmentRevision!!,confirmation),at)
        }
        val refs=ConfirmationRefs(contexts.map { ConfirmedItemRef(it.contextId,it.revision) },commitments.map { ConfirmedItemRef(it.reference.commitmentId,it.reference.revision) })
        connection.prepareStatement("INSERT INTO context_confirmation_receipts(confirmation_id,intake_id,intake_revision,request_sha256,refs_json) VALUES(?,?,?,?,?)").use {
            it.setString(1,request.confirmationId); it.setString(2,id); it.setInt(3,source.revision); it.setString(4,digest); it.setString(5,json.encodeToString(refs)); it.executeUpdate()
        }
        return ContextConfirmationResult(contexts,commitments)
    }
}
