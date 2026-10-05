package com.gtrainer

import kotlinx.serialization.Serializable

@Serializable
enum class DevelopmentReviewOutcome { QUEUED, RUNNING, SUCCESSFUL, FAILED, CANCELLED, SUPERSEDED, EXPIRED }
@Serializable
enum class DevelopmentReviewExecutionState { NOT_STARTED, STARTING, EXECUTING, STOPPING, STOPPED_CONFIRMED, UNKNOWN }
@Serializable
enum class DevelopmentReviewRetentionState { RETAINED, DELETION_DUE, DELETING, PAYLOAD_DELETED, DELETION_FAILED }

@Serializable
data class DevelopmentReviewTask(
    val id: String, val previewId: String, val queueOrder: Long, val caseId: String,
    val slotKey: String, val inputRevision: Long, val inputDigest: String,
    val model: DevelopmentReviewModelBinding, val policy: DevelopmentReviewPolicyBinding,
    val createdUtc: String, val expiresUtc: String, val outcome: DevelopmentReviewOutcome,
    val execution: DevelopmentReviewExecutionState, val retention: DevelopmentReviewRetentionState,
    val replacementId: String?, val reason: String?, val latestInput: Boolean,
) {
    override fun toString() = "DevelopmentReviewTask([REDACTED])"
}

internal class DevelopmentReviewPreviewExpired : IllegalStateException("development_preview_expired")

@Serializable
data class DevelopmentReviewCancellation(val accepted: Boolean, val task: DevelopmentReviewTask)
