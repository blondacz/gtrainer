package com.gtrainer

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID

@ConsistentCopyVisibility
data class DevelopmentReviewPreview internal constructor(
    val id: String, val caseId: String, val packet: InterpretationPacketV1,
    val providerId: String, val modelId: String, val configurationJson: String, val promptDigest: String,
    val policy: DevelopmentReviewPolicyBinding,
) { override fun toString() = "DevelopmentReviewPreview([REDACTED])" }

/**
 * Isolated synthetic persistence. All methods/close synchronize on this store;
 * IMMEDIATE SQLite transactions also fence other connections. No lock spans IO
 * to a provider/process. Returned packets are freshly decoded, never shared.
 */
class DevelopmentReviewStore internal constructor(path: Path, private val clock: Clock = Clock.systemUTC(),
    private val fixtures: DevelopmentReviewFixtureSource = DevelopmentReviewFixtureSource { DevelopmentReviewFixtures.load()[it] },
    private val monotonicNanos: () -> Long = System::nanoTime) :
    ConnectedReviewExecutionRepository, AutoCloseable {
    private val database = DevelopmentReviewDatabase(path)
    internal val ownershipDirectory: Path = path.parent
    private val ownership = DevelopmentReviewOwnershipLedger(database)
    private val work = DevelopmentReviewQueueLedger(database, clock)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Synchronized fun createPreview(caseId: String, model: DevelopmentReviewConfiguration): DevelopmentReviewPreview {
        val fixture = fixtures.fixture(caseId) ?: throw IllegalArgumentException("Unknown development case")
        require(fixture.caseId == caseId) { "Invalid development fixture" }
        return database.transaction {
            requireCapacity()
            val packet = json.decodeFromString<InterpretationPacketV1>(fixture.packetJson)
            val prompt = InterpretationContractV1.prompt(packet)
            val created = clock.instant()
            val id = UUID.randomUUID().toString()
            val refs = packet.context.map { ContextRevisionRefV1(it.contextId, it.revision) }
            val promptDigest = InterpretationContractV1.promptSha256(packet)
            val slot = slotKey(caseId, model)
            database.update(DevelopmentReviewSql.INSERT, id, caseId, json.encodeToString(packet), json.encodeToString(prompt),
                "local:${model.providerId}", model.modelId, json.encodeToString(model.binding()), json.encodeToString(model.executionPolicy),
                slot, fixture.inputRevision.toString(), promptDigest, promptDigest, packet.evidence.evidenceReportSha256,
                json.encodeToString(refs), created.toString(), created.plus(TTL).toString())
            preview(readRequired(id))
        }
    }

    /** First submission creates the run/TTL; duplicates preserve that immutable identity and budget. */
    @Synchronized fun submit(previewId: String): DevelopmentReviewTask = database.transaction {
        val preview = readRequired(previewId)
        if (expired(preview.expires)) throw DevelopmentReviewPreviewExpired()
        runForPreview(previewId)?.let { return@transaction task(it, preview) }
        val id = UUID.randomUUID().toString()
        val created = clock.instant()
        database.update(DevelopmentReviewSql.INSERT_TASK, id, previewId, created.toString(), created.plus(TTL).toString())
        val replaced = database.update(DevelopmentReviewSql.SUPERSEDE, id, id, preview.slotKey,
            preview.inputRevision.toString(), preview.inputDigest)
        recordEvent(id, "submitted", created)
        if (replaced > 0) database.query(DevelopmentReviewSql.REPLACED, id) {
            buildList { while (it.next()) add(it.getString(1)) }
        }.forEach { recordEvent(it, "queued_input_superseded", created) }
        database.query(DevelopmentReviewSql.RUNNING_PREDECESSORS, preview.slotKey, preview.inputRevision.toString(), preview.inputDigest) {
            buildList { while (it.next()) add(it.getString(1)) }
        }.forEach { recordEvent(it, "running_predecessor_preserved", created) }
        task(readRunRequired(id), preview)
    }

    @Synchronized fun queue(): List<DevelopmentReviewTask> = storageOperation {
        val runs = database.query(DevelopmentReviewSql.RUNS) { rows -> buildList { while (rows.next()) add(readRun(rows)) } }
        runs.map { task(it, readRequired(it.previewId)) }
    }

    @Synchronized fun task(id: String): DevelopmentReviewTask = storageOperation {
        val run = readRunRequired(id)
        task(run, readRequired(run.previewId))
    }

    @Synchronized internal fun runResponse(id: String): DevelopmentReviewRunResponse = storageOperation {
        database.transaction { DevelopmentReviewRunResponse(task(id), connectedReviewInspection(id), attempts(id)) }
    }

    @Synchronized fun preview(id: String): DevelopmentReviewPreview? = storageOperation {
        readRow(id)?.takeUnless { expired(it.expires) }?.let(::preview)
    }

    @Synchronized fun previews(): List<DevelopmentReviewPreview> = storageOperation {
        database.query(DevelopmentReviewSql.SELECT_ALL) { rows ->
            buildList {
                while (rows.next()) {
                    val row = read(rows)
                    if (!expired(row.expires)) add(preview(row))
                }
            }
        }
    }

    @Synchronized override fun connectedReviewStatus(snapshotId: String): ConnectedReviewStatus =
        storageOperation { val run = readRunRequired(snapshotId); status(run, readRequired(run.previewId)) }

    @Synchronized override fun connectedReviewPacket(snapshotId: String): InterpretationPacketV1 = storageOperation {
        val run = readRunRequired(snapshotId)
        val row = readRequired(run.previewId)
        if (expired(run.expires)) throw ConnectedReviewNotFound()
        packet(row)
    }

    @Synchronized override fun connectedReviewInspection(snapshotId: String): ConnectedReviewInspection = storageOperation {
        val run = readRunRequired(snapshotId)
        val row = readRequired(run.previewId)
        val available = !expired(run.expires)
        val review = if (available && run.outcome == DevelopmentReviewOutcome.SUCCESSFUL && run.output != null)
            json.decodeFromString<InterpretationDraftV1>(run.output) else null
        ConnectedReviewInspection(status(run, row),
            ConnectedReviewProviderSelection(row.provider.substringAfter(':'), row.model, "local"),
            if (available) json.decodeFromString(row.prompt) else InterpretationPromptV1("", ""), review, available)
    }

    @Synchronized override fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus? = database.transaction {
        if (ownership.epoch() != 0L || ownership.blocked()) return@transaction null
        if (database.update(DevelopmentReviewSql.CLAIM, snapshotId, clock.instant().toString()) != 1) return@transaction null
        recordEvent(snapshotId, "claimed", clock.instant())
        val run = readRunRequired(snapshotId)
        status(run, readRequired(run.previewId))
    }

    @Synchronized override fun publishConnectedReview(snapshotId: String, output: ValidatedConnectedReviewOutput,
        currentEvidenceDigest: String, currentContext: List<ContextRevisionRefV1>, provider: String, model: String,
        contractVersion: String): ConnectedReviewStatus = database.transaction {
        if (ownership.epoch() != 0L) return@transaction connectedReviewStatus(snapshotId)
        publish(snapshotId, output, currentEvidenceDigest, currentContext, provider, model, contractVersion)
    }

    private fun publish(snapshotId: String, output: ValidatedConnectedReviewOutput,
        currentEvidenceDigest: String, currentContext: List<ContextRevisionRefV1>, provider: String, model: String,
        contractVersion: String): ConnectedReviewStatus {
        val run = readRunRequired(snapshotId)
        val row = readRequired(run.previewId)
        val packet = packet(row)
        val refs = packet.context.map { ContextRevisionRefV1(it.contextId, it.revision) }
        if (expired(run.expires) || !publicationMatches(row, packet, refs, currentEvidenceDigest, currentContext, provider, model, contractVersion))
            return status(run, row)
        val changed = database.update(DevelopmentReviewSql.PUBLISH, output.text, snapshotId, clock.instant().toString()) == 1
        if (changed) recordEvent(snapshotId, "published", clock.instant())
        return status(readRunRequired(snapshotId), row)
    }

    @Synchronized override fun failConnectedReview(snapshotId: String, reason: String): ConnectedReviewStatus = database.transaction {
        if (ownership.epoch() != 0L) return@transaction connectedReviewStatus(snapshotId)
        fail(snapshotId, reason)
    }

    private fun fail(snapshotId: String, reason: String): ConnectedReviewStatus {
        val fixedReason = if (reason in SAFE_REASONS) reason else "execution_failed"
        val outcome = if (fixedReason == "cancelled") "CANCELLED" else "FAILED"
        if (database.update(DevelopmentReviewSql.FAIL, outcome, fixedReason, snapshotId) == 1)
            recordEvent(snapshotId, if (outcome == "CANCELLED") "cancelled" else "failed", clock.instant())
        val run = readRunRequired(snapshotId)
        return status(run, readRequired(run.previewId))
    }

    internal fun openOwnership(nodeName: String): DevelopmentReviewOwnershipSession =
        DevelopmentReviewOwnershipSession.acquire(this, nodeName)

    @Synchronized internal fun advanceOwnership(nodeName: String): DevelopmentOwnerEpoch = database.transaction { ownership.advance(nodeName) }
    @Synchronized internal fun pendingExecutions(): List<DevelopmentPendingExecution> = storageOperation { ownership.pending() }

    @Synchronized internal fun claimOwned(id: String, epoch: DevelopmentOwnerEpoch, boundary: DevelopmentNodeBoundary): DevelopmentOwnedClaim? =
        database.transaction {
            if (!ownership.canClaim(epoch, boundary)) return@transaction null
            val now = clock.instant()
            val origin = monotonicNanos()
            if (database.update(DevelopmentReviewSql.CLAIM, id, now.toString()) != 1) return@transaction null
            val run = readRunRequired(id)
            val row = readRequired(run.previewId)
            val policy = json.decodeFromString<DevelopmentReviewPolicyBinding>(row.policy)
            val limits = ConnectedReviewExecutionLimits.developmentLocal(Duration.between(now, run.expires).toMillis(),
                policy.attemptTimeoutMillis, policy.totalTimeoutMillis)
            val generation = ownership.claimed(id, epoch, boundary, run.expires)
            recordEvent(id, "claimed", now)
            DevelopmentOwnedClaim(id, epoch, generation, boundary, origin, limits)
        }

    @Synchronized internal fun ownedStarted(claim: DevelopmentOwnedClaim): Boolean = database.transaction { ownership.started(claim) }

    @Synchronized internal fun registerRuntime(handle: DevelopmentRuntimeHandle): Boolean = database.transaction {
        if (!ownership.started(handle.claim, requireRunning = false)) return@transaction false
        database.update(DevelopmentReviewRuntimeSql.INSERT, *runtimeParameters(handle))
        true
    }

    @Synchronized fun cancel(id: String): DevelopmentReviewCancellation = database.transaction {
        val run = readRunRequired(id)
        if (expired(run.expires)) return@transaction DevelopmentReviewCancellation(false, task(run, readRequired(run.previewId)))
        val changed = database.update(DevelopmentReviewRuntimeSql.CANCEL, id) == 1
        if (changed) recordEvent(id, "cancelled", clock.instant())
        val final = readRunRequired(id)
        DevelopmentReviewCancellation(changed || final.outcome == DevelopmentReviewOutcome.CANCELLED, task(final, readRequired(final.previewId)))
    }

    @Synchronized internal fun refuseQueued(id: String, reason: String) = database.transaction {
        val fixed = if (reason == "provider_not_configured") reason else "execution_failed"
        if (database.query(DevelopmentReviewSql.RUN, id) { it.next() && it.getString("state") == "QUEUED" }) fail(id, fixed)
    }

    @Synchronized internal fun beginOwnedStop(handle: DevelopmentRuntimeHandle): Boolean = database.transaction {
        if (!runtimeMatches(handle) || !ownership.matchesCurrent(handle.claim)) return@transaction false
        val changed = database.update(DevelopmentReviewRuntimeSql.STOPPING, handle.claim.runId, handle.claim.owner.number.toString(),
            handle.claim.generation.toString(), handle.claim.boundary.containerId) == 1
        if (changed) database.update(DevelopmentReviewRuntimeSql.TASK_STOPPING, handle.claim.runId)
        changed
    }

    @Synchronized internal fun unknownOwnedStop(handle: DevelopmentRuntimeHandle): Boolean = database.transaction {
        if (runtimeMatches(handle) && ownership.matchesCurrent(handle.claim)) database.update(DevelopmentReviewRuntimeSql.TASK_UNKNOWN, handle.claim.runId)
        false
    }

    @Synchronized internal fun confirmOwnedStop(receipt: DevelopmentRuntimeStopReceipt): Boolean = database.transaction {
        val handle = receipt.handle
        if (!runtimeMatches(handle) || !ownership.matchesCurrent(handle.claim)) return@transaction false
        database.update(DevelopmentReviewRuntimeSql.RECEIPT, receipt.safeJson(), handle.claim.runId)
        database.update(DevelopmentReviewRuntimeSql.CONFIRM, handle.claim.runId, handle.claim.owner.number.toString(),
            handle.claim.generation.toString(), handle.claim.boundary.containerId)
        database.update(DevelopmentReviewOwnershipSql.TASK_STOPPED, handle.claim.runId)
        true
    }

    private fun runtimeMatches(handle: DevelopmentRuntimeHandle) = database.query(DevelopmentReviewRuntimeSql.MATCH, *runtimeParameters(handle)) { it.next() }
    @Synchronized internal fun runtimeHandle(pending: DevelopmentPendingExecution, owner: DevelopmentOwnerEpoch): DevelopmentRuntimeHandle? = storageOperation {
        val boundary = pending.boundary ?: return@storageOperation null
        if (pending.epoch != owner.number) return@storageOperation null
        database.query(DevelopmentReviewRuntimeSql.HANDLE, pending.runId, pending.epoch.toString(), pending.generation.toString(), boundary.containerId) {
            if (!it.next()) null else DevelopmentRuntimeHandle(DevelopmentOwnedClaim(pending.runId, owner, pending.generation, boundary,
                0, ConnectedReviewExecutionLimits.developmentLocal(1)), it.getLong(1), it.getLong(2))
        }
    }
    private fun runtimeParameters(handle: DevelopmentRuntimeHandle): Array<String> = arrayOf(handle.claim.runId, handle.claim.owner.number.toString(),
        handle.claim.generation.toString(), handle.claim.boundary.containerId, handle.keeperPid.toString(), handle.keeperStartTicks.toString())

    @Synchronized internal fun ownedActive(claim: DevelopmentOwnedClaim): Boolean = database.transaction {
        val run = readRunRequired(claim.runId)
        ownership.permitsPublication(claim) && run.outcome == DevelopmentReviewOutcome.RUNNING && !expired(run.expires) &&
            monotonicNanos() - claim.claimedAtNanos < claim.limits.totalTimeoutMillis * 1_000_000
    }

    @Synchronized internal fun publishOwned(claim: DevelopmentOwnedClaim, output: ValidatedConnectedReviewOutput,
        evidence: String, context: List<ContextRevisionRefV1>, provider: String, model: String, contract: String): ConnectedReviewStatus =
        database.transaction {
            if (!ownership.permitsPublication(claim) ||
                monotonicNanos() - claim.claimedAtNanos >= claim.limits.totalTimeoutMillis * 1_000_000)
                return@transaction connectedReviewStatus(claim.runId)
            publish(claim.runId, output, evidence, context, provider, model, contract)
        }

    @Synchronized internal fun failOwned(claim: DevelopmentOwnedClaim, reason: String): ConnectedReviewStatus = database.transaction {
        if (!ownership.matchesCurrent(claim)) return@transaction connectedReviewStatus(claim.runId)
        fail(claim.runId, reason)
    }

    @Synchronized internal fun reconcileOwned(epoch: DevelopmentOwnerEpoch, pending: DevelopmentPendingExecution,
        proof: DevelopmentNodeStopProof): Boolean = database.transaction { ownership.reconcile(epoch, pending, proof) }

    @Synchronized internal fun pendingEvents(): List<DevelopmentQueueEvent> = storageOperation { work.events() }
    @Synchronized internal fun queuedIds(): List<String> = storageOperation { work.queuedIds() }
    @Synchronized internal fun acknowledge(event: DevelopmentQueueEvent) = database.transaction { work.acknowledge(event.id) }
    @Synchronized fun submitMaintenance(): DevelopmentMaintenanceTask = work.maintenance()
    @Synchronized fun maintenanceTasks(): List<DevelopmentMaintenanceTask> = storageOperation { work.maintenanceTasks() }
    @Synchronized internal fun finishMaintenance(task: DevelopmentMaintenanceTask, blocked: Boolean, cleanupFailed: Boolean = false) =
        database.transaction { work.finishMaintenance(task, blocked, cleanupFailed) }
    @Synchronized internal fun cleanupDue(): DevelopmentRetentionResult = work.cleanup()
    @Synchronized fun workerSummaries(): List<DevelopmentWorkerSummary> = storageOperation { work.summaries() }
    @Synchronized internal fun recordSummary(event: DevelopmentQueueEvent, reasons: List<DevelopmentWorkerReason>, ids: List<String>,
        deleted: Int, executed: Boolean, blocked: Boolean) = database.transaction { work.summary(event, reasons, ids, deleted, executed, blocked) }
    @Synchronized internal fun recordAttempt(claim: DevelopmentOwnedClaim, event: ConnectedReviewAttemptEvent) = database.transaction {
        if (ownership.matchesCurrent(claim) && database.query(DevelopmentReviewSql.RUN, claim.runId) { it.next() && !expired(Instant.parse(it.getString("expires_utc"))) }) work.attempt(claim, event)
    }
    @Synchronized fun attempts(id: String): List<DevelopmentAttemptMetadata> = storageOperation {
        val run = readRunRequired(id)
        if (expired(run.expires)) emptyList() else work.attempts(id)
    }

    private fun publicationMatches(row: Row, packet: InterpretationPacketV1, refs: List<ContextRevisionRefV1>,
        evidence: String, context: List<ContextRevisionRefV1>, provider: String, model: String, contract: String): Boolean =
        contract == InterpretationContractV1.PROFILE && InterpretationContractV1.promptSha256(packet) == row.promptDigest &&
            json.encodeToString(InterpretationContractV1.prompt(packet)) == row.prompt &&
            packet.evidence.evidenceReportSha256 == evidence && row.evidenceDigest == evidence &&
            refs == context && row.provider == provider && row.model == model

    private fun requireCapacity() {
        val count = database.query(DevelopmentReviewSql.COUNT) { rows -> rows.next(); rows.getInt(1) }
        if (count >= MAX_RETAINED) throw DevelopmentReviewCapacityReached()
    }

    private fun readRow(id: String): Row? = database.query(DevelopmentReviewSql.SELECT, id) { rows ->
        if (rows.next()) read(rows) else null
    }

    private fun readRequired(id: String) = readRow(id) ?: throw ConnectedReviewNotFound()
    private fun packet(row: Row) = json.decodeFromString<InterpretationPacketV1>(row.packet)
    private fun expired(expires: Instant) = !clock.instant().isBefore(expires)

    private fun preview(row: Row) = DevelopmentReviewPreview(row.id, row.caseId, packet(row),
        row.provider.substringAfter(':'), row.model, row.binding, row.promptDigest, json.decodeFromString(row.policy))

    private fun status(run: RunRow, row: Row): ConnectedReviewStatus {
        val packet = packet(row)
        val facts = packet.evidence.facts
        val expired = expired(run.expires)
        val state = when {
            expired && run.outcome in ACTIVE_OUTCOMES -> "EXPIRED"
            run.outcome == DevelopmentReviewOutcome.QUEUED -> "IN_FLIGHT"
            run.outcome == DevelopmentReviewOutcome.SUCCESSFUL -> "PUBLISHED"
            else -> run.outcome.name
        }
        return ConnectedReviewStatus(run.id, run.id, row.inputRevision, row.evidenceDigest, row.promptDigest,
            json.decodeFromString(row.context), facts.mapNotNull { it.oldest }.minOrNull().orEmpty(),
            facts.mapNotNull { it.newest }.maxOrNull().orEmpty(), packet.evidence.selectedSport, row.provider, row.model,
            InterpretationContractV1.PROFILE, state, run.reason, if (expired) null else run.output, run.created.toString())
    }

    private fun task(run: RunRow, row: Row): DevelopmentReviewTask {
        val expired = expired(run.expires)
        val newer = database.query(DevelopmentReviewSql.HAS_NEWER_INPUT, row.slotKey, row.inputRevision.toString(), row.inputDigest) {
            it.next(); it.getBoolean(1)
        }
        return DevelopmentReviewTask(run.id, row.id, run.queueOrder, row.caseId, row.slotKey, row.inputRevision, row.inputDigest,
            json.decodeFromString(row.binding), json.decodeFromString(row.policy), run.created.toString(), run.expires.toString(),
            if (expired && run.outcome in ACTIVE_OUTCOMES) DevelopmentReviewOutcome.EXPIRED else run.outcome, run.execution,
            if (expired && work.deletionFailed(row.id)) DevelopmentReviewRetentionState.DELETION_FAILED
            else if (expired) DevelopmentReviewRetentionState.DELETION_DUE else DevelopmentReviewRetentionState.RETAINED,
            run.replacementId, run.reason, !newer)
    }

    private fun readRunRequired(id: String) = database.query(DevelopmentReviewSql.RUN, id) {
        if (it.next()) readRun(it) else throw ConnectedReviewNotFound()
    }

    private fun runForPreview(id: String): RunRow? = database.query(DevelopmentReviewSql.RUN_FOR_PREVIEW, id) {
        if (it.next()) readRun(it) else null
    }

    private fun readRun(rows: ResultSet) = RunRow(rows.getString("id"), rows.getString("preview_id"), rows.getLong("queue_order"),
        Instant.parse(rows.getString("created_utc")), Instant.parse(rows.getString("expires_utc")),
        DevelopmentReviewOutcome.valueOf(rows.getString("state")), DevelopmentReviewExecutionState.valueOf(rows.getString("execution_state")),
        rows.getString("replacement_id"), rows.getString("stale_reason"), rows.getString("output"))

    private fun slotKey(caseId: String, model: DevelopmentReviewConfiguration): String {
        val encoded = json.encodeToString(listOf(caseId, model.providerId, model.modelId, InterpretationContractV1.PROFILE))
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun recordEvent(id: String, kind: String, now: Instant) =
        database.update(DevelopmentReviewSql.INSERT_EVENT, kind, now.toString(), id)

    private fun read(rows: ResultSet) = Row(
        rows.getString("id"), rows.getString("case_id"), rows.getString("packet"), rows.getString("prompt"),
        rows.getString("provider"), rows.getString("model"), rows.getString("binding"), rows.getString("policy"),
        rows.getString("prompt_digest"), rows.getString("evidence_digest"), rows.getString("context"),
        Instant.parse(rows.getString("created_utc")), Instant.parse(rows.getString("expires_utc")),
        rows.getString("slot_key"), rows.getLong("input_revision"), rows.getString("input_digest"))

    /** Strings, not aliased mutable packets, cross the internal persistence boundary. */
    private data class Row(val id: String, val caseId: String, val packet: String, val prompt: String,
        val provider: String, val model: String, val binding: String, val policy: String,
        val promptDigest: String, val evidenceDigest: String, val context: String,
        val created: Instant, val expires: Instant, val slotKey: String, val inputRevision: Long, val inputDigest: String) {
        override fun toString() = "DevelopmentReviewRow([REDACTED])"
    }

    private data class RunRow(val id: String, val previewId: String, val queueOrder: Long, val created: Instant, val expires: Instant,
        val outcome: DevelopmentReviewOutcome, val execution: DevelopmentReviewExecutionState, val replacementId: String?,
        val reason: String?, val output: String?) { override fun toString() = "DevelopmentReviewRunRow([REDACTED])" }

    @Synchronized override fun close() = database.close()

    private companion object {
        const val MAX_RETAINED = 32
        val TTL: Duration = Duration.ofHours(24)
        val ACTIVE_OUTCOMES = setOf(DevelopmentReviewOutcome.QUEUED, DevelopmentReviewOutcome.RUNNING)
        val SAFE_REASONS = setOf("execution_failed", "cancelled", "invalid_output", "total_timeout", "attempt_timeout",
            "provider_not_configured", "input_budget_exceeded",
            "response_budget_exceeded", "provider_unavailable", "qualification_gate_disabled", "provider_not_qualified",
            "runtime_health_failed", "runtime_health_unavailable", "resource_health_failed", "consent_required", "consent_revoked",
            "snapshot_changed", "provider_changed", "packet_changed", "safety_gate_unavailable", "hosted_cost_budget_exhausted",
            "hosted_cost_budget_exceeded", "invalid_local_cost_binding", "runtime_ownership_unavailable", "runtime_binding_mismatch",
            "provider_protocol_invalid")
    }
}
