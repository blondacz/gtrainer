package com.gtrainer

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant

@Serializable
data class ContextDeletionPreview(val contextId: String, val expectedRevision: Int, val digest: String,
    val sources: List<ContextIntake>, val survivors: List<ContextLifecycleView>, val sourceLinks: List<ContextIntakeLink>, val commitments: List<ConfirmedCommitment>,
    val deletedContextIds: List<String>, val snapshotIds: List<String>, val receiptCount: Int,
    val executionIds: List<String> = emptyList(),
    val proposalIds: List<String> = emptyList(),
    val retainedApprovalOperationIds: List<String> = emptyList(),
    val limitation: String = "declared_dependencies_and_approved_replacements_not_semantic_discovery;encrypted_backups_follow_retention") {
    override fun toString()="ContextDeletionPreview([REDACTED])"
}

@Serializable
data class SourceRedaction(val intakeId: String, val expectedRevision: Int, val note: ContextIntakeRequest) {
    init { validateContextId(intakeId); require(expectedRevision in 1..999) }
    override fun toString()="SourceRedaction([REDACTED])"
}

@Serializable
data class ContextSurvivor(val item: ContextConfirmationItem, val intakeId: String?) {
    init { require(item.contextId!=null); intakeId?.let(::validateContextId) }
    override fun toString()="ContextSurvivor([REDACTED])"
}

/** Null confirmation explicitly deletes this dependent calendar projection, not an unrelated event. */
@Serializable
data class CommitmentDisposition(val commitmentId: String, val expectedRevision: Int, val confirmation: CommitmentConfirmation?) {
    init { validateContextId(commitmentId); require(expectedRevision in 1..1000) }
    override fun toString()="CommitmentDisposition([REDACTED])"
}

@Serializable
data class ContextDeletionRequest(val expectedRevision: Int, val previewDigest: String, val sources: List<SourceRedaction>,
    val survivors: List<ContextSurvivor>, val commitments: List<CommitmentDisposition>) {
    init {
        require(expectedRevision in 1..1000 && Regex("[0-9a-f]{64}").matches(previewDigest))
        require(sources.size<=1000 && survivors.size<=1000 && commitments.size<=1000)
        require(sources.map { it.intakeId }.distinct().size==sources.size)
        require(survivors.map { it.item.contextId }.distinct().size==survivors.size)
        require(commitments.map { it.commitmentId }.distinct().size==commitments.size)
    }
    override fun toString()="ContextDeletionRequest([REDACTED])"
}

@Serializable
data class ContextDeletionResult(val deletedContextIds: List<String>, val survivingContextIds: List<String>,
    val retainedCommitmentIds: List<String>, val deletedCommitmentIds: List<String>,
    val notice: String = "active_dependency_histories_removed;encrypted_backups_follow_retention;not_forensic_erasure")

interface ContextDeletionRepository {
    fun previewContextDeletion(id: String, at: Instant): ContextDeletionPreview
    fun redactAndDeleteContext(id: String, request: ContextDeletionRequest, at: Instant): ContextDeletionResult
}

class ContextDeletionService(private val repository: ContextDeletionRepository, private val clock: Clock = Clock.systemUTC()) {
    fun preview(id: String)=repository.previewContextDeletion(id,clock.instant())
    fun apply(id: String, request: ContextDeletionRequest)=repository.redactAndDeleteContext(id,request,clock.instant())
}

class ContextDeletionConflict : IllegalStateException("Deletion dependencies changed; inspect a new preview")
class ContextDeletionLimit : IllegalStateException("Deletion dependencies exceed the inspection bound")
