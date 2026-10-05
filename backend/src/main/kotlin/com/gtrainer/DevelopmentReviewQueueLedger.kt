package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Called only under the store monitor. Cleanup/acknowledgement belongs to the elected event consumer. */
internal class DevelopmentReviewQueueLedger(private val database: DevelopmentReviewDatabase, private val clock: Clock) {
    private val json = Json { encodeDefaults = true }
    fun events(): List<DevelopmentQueueEvent> = database.query(DevelopmentReviewQueueSql.EVENTS) { rows ->
        buildList { while (rows.next()) add(DevelopmentQueueEvent(rows.getLong("id"), rows.getString("kind"), rows.getString("task_id"), rows.getString("created_utc"))) }
    }
    fun queuedIds(): List<String> = ids(DevelopmentReviewQueueSql.QUEUED)
    fun acknowledge(id: Long) { database.update(DevelopmentReviewQueueSql.ACK, id.toString()) }

    fun maintenance(): DevelopmentMaintenanceTask = database.transaction {
        database.query(DevelopmentReviewQueueSql.MAINTENANCE_PENDING) { if (it.next()) maintenance(it) else null }?.let { return@transaction it }
        val count = database.query(DevelopmentReviewQueueSql.MAINTENANCE_COUNT) { it.next(); it.getInt(1) }
        val now = clock.instant()
        val task = DevelopmentMaintenanceTask(UUID.randomUUID().toString(), now.toString(), now.plus(TTL).toString(), "QUEUED", null, count >= 8)
        if (task.coalescedSignal) {
            signal()?.let { prior ->
                if (prior.state == "QUEUED") return@transaction prior
                // Reserved non-payload wake-up slot, not replay of an inference/maintenance history row.
                deleteEvents(prior.id)
                if (now.isBefore(Instant.parse(prior.expiresUtc))) {
                    database.update(DevelopmentReviewQueueSql.SIGNAL_REARM)
                    database.update(DevelopmentReviewSql.INSERT_EVENT, "maintenance", now.toString(), prior.id)
                    return@transaction prior.copy(state = "QUEUED", reason = null)
                }
            }
            database.update(DevelopmentReviewQueueSql.SIGNAL_INSERT, task.id, task.createdUtc, task.expiresUtc)
        } else database.update(DevelopmentReviewQueueSql.MAINTENANCE_INSERT, task.id, task.createdUtc, task.expiresUtc)
        database.update(DevelopmentReviewSql.INSERT_EVENT, "maintenance", now.toString(), task.id)
        task
    }

    fun maintenanceTasks(): List<DevelopmentMaintenanceTask> = database.query(DevelopmentReviewQueueSql.MAINTENANCE) { rows ->
        buildList { while (rows.next()) add(maintenance(rows)) }
    } + listOfNotNull(signal())

    fun finishMaintenance(task: DevelopmentMaintenanceTask, blocked: Boolean, cleanupFailed: Boolean) {
        val expired = !clock.instant().isBefore(Instant.parse(task.expiresUtc))
        database.update(if (task.coalescedSignal) DevelopmentReviewQueueSql.SIGNAL_FINISH else DevelopmentReviewQueueSql.MAINTENANCE_FINISH,
            if (expired) "EXPIRED" else if (cleanupFailed) "FAILED" else "SUCCESSFUL",
            if (blocked) "runtime_stop_unconfirmed" else if (cleanupFailed) "cleanup_failed" else "maintenance_completed", task.id)
    }

    private fun signal(): DevelopmentMaintenanceTask? = database.query(DevelopmentReviewQueueSql.SIGNAL) {
        if (it.next()) maintenance(it, true) else null
    }
    private fun maintenance(rows: ResultSet, signal: Boolean = false) = DevelopmentMaintenanceTask(rows.getString("id"), rows.getString("created_utc"),
        rows.getString("expires_utc"), rows.getString("state"), rows.getString("reason"), signal)

    fun summary(event: DevelopmentQueueEvent, reasons: List<DevelopmentWorkerReason>, ids: List<String>, deleted: Int, executed: Boolean, blocked: Boolean) {
        if (deleted == 0 && !database.query(DevelopmentReviewQueueSql.EVENT_EXISTS, event.id.toString()) { it.next(); it.getBoolean(1) }) return
        val now = clock.instant()
        val bound = event.taskId?.let { id -> database.query(DevelopmentReviewSql.RUN, id) {
            if (it.next()) Instant.parse(it.getString("expires_utc")) else null
        } }
        if (bound != null && !now.isBefore(bound)) return
        val result = DevelopmentWorkerSummary(event.id, safeTrigger(event.kind), now.toString(), reasons.distinct().take(8),
            ids.filter { value -> runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false) }.distinct().take(32),
            deleted.coerceIn(0, 8), executed, blocked)
        val count = database.query(DevelopmentReviewQueueSql.SUMMARY_COUNT, event.id.toString()) { it.next(); it.getInt(1) }
        if (count >= 4) database.update(DevelopmentReviewQueueSql.SUMMARY_TRIM, event.id.toString(), event.id.toString())
        database.update(DevelopmentReviewQueueSql.SUMMARY_INSERT, event.id.toString(), if (bound != null) event.taskId else null,
            now.toString(), (bound ?: now.plus(TTL)).toString(), json.encodeToString(result))
        database.update(DevelopmentReviewQueueSql.SUMMARY_GLOBAL_TRIM)
    }
    fun summaries(): List<DevelopmentWorkerSummary> = database.query(DevelopmentReviewQueueSql.SUMMARIES, clock.instant().toString()) {
        buildList { while (it.next()) add(json.decodeFromString<DevelopmentWorkerSummary>(it.getString(1))) }
    }
    fun attempt(claim: DevelopmentOwnedClaim, event: ConnectedReviewAttemptEvent) {
        val number = event.number ?: return
        if (number !in 1..2) return
        val now = clock.instant()
        val created = attempts(claim.runId).find { it.number == number }?.createdUtc ?: now.toString()
        val metadata = DevelopmentAttemptMetadata(number, event.phase, event.reason?.takeIf { it in ATTEMPT_REASONS } ?: event.reason?.let { "execution_failed" },
            created, now.toString())
        database.update(DevelopmentReviewQueueSql.ATTEMPT_INSERT, claim.runId, number.toString(), json.encodeToString(metadata), now.toString())
    }
    fun attempts(id: String): List<DevelopmentAttemptMetadata> = database.query(DevelopmentReviewQueueSql.ATTEMPTS, id) {
        buildList { while (it.next()) add(json.decodeFromString<DevelopmentAttemptMetadata>(it.getString(1))) }
    }
    fun deletionFailed(previewId: String): Boolean = database.query(DevelopmentReviewQueueSql.DELETE_FAILED, previewId) { it.next(); it.getBoolean(1) }

    fun cleanup(): DevelopmentRetentionResult {
        var due = emptyList<Pair<String, String?>>()
        var expiredQueued = false
        return try {
            database.transaction {
                database.update(DevelopmentReviewOwnershipSql.CAPTURE_UNBOUND)
                due = database.query(DevelopmentReviewQueueSql.DUE, clock.instant().toString()) { rows ->
                    buildList { while (rows.next()) {
                        if (rows.getString("outcome") == "QUEUED") expiredQueued = true
                        add(rows.getString("id") to rows.getString("run_id"))
                    } }
                }
                due.forEach { (preview, run) ->
                    if (run != null) {
                        deleteEvents(run)
                        database.update(DevelopmentReviewQueueSql.DELETE_ATTEMPTS, run)
                        database.update(DevelopmentReviewQueueSql.DELETE_SUMMARIES, run)
                        database.update(DevelopmentReviewQueueSql.DELETE_TASK, run)
                    }
                    database.update(DevelopmentReviewQueueSql.DELETE_FAILURE, preview)
                    database.update(DevelopmentReviewQueueSql.DELETE_PREVIEW, preview)
                }
                cleanupControlRecords()
                DevelopmentRetentionResult(due.map { it.second ?: it.first }, false, expiredQueued)
            }
        } catch (_: DevelopmentReviewStorageFailure) {
            runCatching { database.transaction { due.forEach { database.update(DevelopmentReviewQueueSql.MARK_DELETE_FAILURE, it.first) } } }
            DevelopmentRetentionResult(emptyList(), true)
        }
    }

    private fun cleanupControlRecords() {
        val now = clock.instant().toString()
        database.update(DevelopmentReviewQueueSql.DUE_SUMMARIES, now)
        ids(DevelopmentReviewQueueSql.DUE_MAINTENANCE, now).forEach {
            deleteEvents(it); database.update(DevelopmentReviewQueueSql.DELETE_MAINTENANCE, it)
        }
        ids(DevelopmentReviewQueueSql.DUE_SAFETY, now).forEach {
            database.update(DevelopmentReviewQueueSql.DELETE_BINDING, it); database.update(DevelopmentReviewQueueSql.DELETE_SAFETY, it)
        }
    }
    private fun deleteEvents(id: String) {
        database.update(DevelopmentReviewQueueSql.DELETE_EVENT_SUMMARIES, id)
        database.update(DevelopmentReviewQueueSql.DELETE_ACK, id)
        database.update(DevelopmentReviewQueueSql.DELETE_EVENTS, id)
    }
    private fun ids(sql: String, vararg parameters: String) = database.query(sql, *parameters) { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    private fun safeTrigger(kind: String) = if (kind in TRIGGERS) kind else "queue_event"

    companion object {
        private val TTL = Duration.ofHours(24)
        private val TRIGGERS = setOf("submitted", "claimed", "cancelled", "published", "failed", "queued_input_superseded", "running_predecessor_preserved", "maintenance")
        private val ATTEMPT_REASONS = setOf("invalid_output", "total_timeout", "attempt_timeout", "cancelled", "provider_unavailable", "response_budget_exceeded",
            "runtime_health_failed", "runtime_health_unavailable", "resource_health_failed", "snapshot_changed", "provider_protocol_invalid", "runtime_binding_mismatch")
    }
}
