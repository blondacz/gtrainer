package com.gtrainer

import kotlinx.serialization.Serializable

internal data class DevelopmentQueueEvent(val id: Long, val kind: String, val taskId: String?, val createdUtc: String)
@Serializable
data class DevelopmentMaintenanceTask(val id: String, val createdUtc: String, val expiresUtc: String, val state: String,
    val reason: String?, val coalescedSignal: Boolean = false)
@Serializable
enum class DevelopmentWorkerReason { EXECUTED, EXPIRED_BEFORE_CLAIM, QUEUED_INPUT_SUPERSEDED, RUNNING_PREDECESSOR_PRESERVED,
    RUNTIME_STOP_UNCONFIRMED, CLEANUP_PENDING, CLEANUP_FAILED, MAINTENANCE_COMPLETED, EXECUTION_REFUSED, PAYLOAD_DELETED, EVENT_RECONCILED }
@Serializable
data class DevelopmentWorkerSummary(val eventId: Long, val trigger: String, val createdUtc: String,
    val reasons: List<DevelopmentWorkerReason>, val affectedIds: List<String>, val deleted: Int, val executed: Boolean, val blocked: Boolean)
@Serializable
data class DevelopmentAttemptMetadata(val number: Int, val phase: ConnectedReviewAttemptPhase, val reason: String?, val createdUtc: String,
    val updatedUtc: String = createdUtc)
@Serializable
data class DevelopmentWorkerDiagnostics(val configured: Boolean, val executingRunId: String?, val blocked: Boolean,
    val unresolvedBoundaries: Int, val reason: String, val applianceMemoryLimitBytes: Long?, val minimumHostHeadroomBytes: Long = DevelopmentHealthPolicy.HOST_HEADROOM_BYTES,
    val health: DevelopmentHealthDiagnostics? = null)
@Serializable
data class DevelopmentHealthDiagnostics(val generation: Long, val containerId: String, val runtimeReady: Boolean,
    val availableHostBytes: Long, val observedApplianceLimitBytes: Long, val oldestMeasurementAgeMillis: Long)
internal data class DevelopmentRetentionResult(val deletedIds: List<String>, val failed: Boolean, val expiredQueued: Boolean = false)
