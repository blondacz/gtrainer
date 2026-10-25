package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate

@Serializable
data class CategoryStatus(
    val category: String,
    val recordCount: Int,
    val lastAttemptUtc: String?,
    val lastSuccessUtc: String?,
    val readStatus: String,
    val rejected: Int,
    val incomplete: Int,
    val latestObservedDate: String?,
    val latestObservedAgeDays: Long?,
    val upstreamFreshness: String = "unknown",
) {
    override fun toString(): String = "CategoryStatus(category=$category, values=[REDACTED])"
}

@Serializable
data class HistoryResponse(val activities: List<ActivityRecord>, val wellness: List<WellnessRecord>) {
    override fun toString(): String = "HistoryResponse([REDACTED])"
}

class HistoryStore(path: Path, private val constraintClock: java.time.Clock = java.time.Clock.systemUTC()) : AutoCloseable, ManualEventRepository, AthleteContextRepository, ContextIntakeRepository, ContextLifecycleRepository, CommitmentRepository, ContextDeletionRepository, PlanRepository, PlanReconciliationRepository, ActivityEffortRepository, PersonalSignalRepository, ExtraWorkloadRepository, PlanAmendmentRepository, ConnectedReviewExecutionRepository {
    fun athleteContexts() = AthleteContextService(this)
    private val connection: Connection
    private val scheduleJson = Json { encodeDefaults = true }
    private val intakeStore by lazy { ContextIntakeStore(connection) }
    private val contextRows by lazy { ContextRows(connection) }
    private val lifecycleStore by lazy { ContextLifecycleStore(connection) }
    private val commitmentStore by lazy { CommitmentStore(connection) }
    private val evidenceCaptureOwner=java.util.UUID.randomUUID().toString()
    private var signalEvidenceAdapter=SignalEvidenceAdapter { it }
    private var ownerUatContinuousClock: OwnerUatContinuousClock? = null

    /** Synthetic/trusted adapter seam only. No route or selector can install evidence authority. */
    internal constructor(path: Path,clock: java.time.Clock,adapter: SignalEvidenceAdapter): this(path,clock) {
        signalEvidenceAdapter=adapter
    }

    init {
        require(!Files.isSymbolicLink(path)) { "Database must be an app-owned file" }
        Files.createDirectories(path.toAbsolutePath().parent)
        if (!Files.exists(path)) {
            Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
        }
        // Restrict before SQLite creates WAL/SHM files; SQLite derives their
        // permissions from the database rather than the process umask.
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout=5000")
                statement.execute("PRAGMA foreign_keys=ON")
                transaction {
                    if (HistorySchema.createIfEmpty(connection)) {
                        saveInitialReviewStateLocked()
                        statement.execute("INSERT INTO scoped_review_configuration(singleton,timezone_confirmed) VALUES(1,0)")
                        statement.execute("INSERT INTO plan_selection(singleton,generation,selected_plan_id,selected_revision) VALUES(1,0,NULL,NULL)")
                        ScopedQueueStore(connection).seed()
                        PersonalReviewRuntimeStore(connection).seed()
                        SignalMutationStore(connection).seed()
                        statement.execute("INSERT INTO constraint_observation_metadata(singleton,mutation_sequence,deletion_generation,retained_from_sequence,available) VALUES(1,0,0,1,1)")
                    }
                    HistorySchema.validate(connection)
                    reviewScheduleStateLocked()
                }
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=FULL")
                statement.execute("PRAGMA secure_delete=ON")
                transaction {
                    if(statement.executeUpdate("UPDATE sync_status SET read_status='INTERRUPTED' WHERE read_status='RUNNING'")>0) ScopedQueueStore(connection).sourceChanged()
                }
                transaction {
                    PersonalReviewRuntimeStore(connection).advanceEpoch()
                    ScopedExecutionStore(connection).recoverInterrupted()
                }
            }
            // JDBC creates the file; restrict it explicitly instead of relying
            // on the container process umask. The containing PVC remains private.
            runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } catch (error: Exception) {
            connection.close()
            if (error is HistorySchemaMismatch) throw error
            throw IllegalStateException("Database initialization failed; contents withheld")
        }
    }

    private fun <T> transaction(operation: () -> T): T {
        connection.autoCommit = false
        return try {
            val result = operation()
            connection.commit()
            result
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally { connection.autoCommit = true }
    }

    @Synchronized
    fun begin(category: String, at: Instant) = transaction {
        categoryTable(category)
        val previous=sourceStatusBindingLocked(category)
        connection.prepareStatement("INSERT INTO sync_status(category,last_attempt_utc,read_status) VALUES (?,?,'RUNNING') ON CONFLICT(category) DO UPDATE SET last_attempt_utc=excluded.last_attempt_utc,read_status='RUNNING',rejected=0,incomplete=0").use {
            it.setString(1, category); it.setString(2, at.toString()); it.executeUpdate()
        }
        if(previous!=sourceStatusBindingLocked(category)) ScopedQueueStore(connection).sourceChanged()
        Unit
    }

    @Synchronized
    fun interrupted(category: String) = transaction {
        categoryTable(category)
        connection.prepareStatement("UPDATE sync_status SET read_status='INTERRUPTED' WHERE category=? AND read_status='RUNNING'").use {
            it.setString(1, category); if(it.executeUpdate()>0) ScopedQueueStore(connection).sourceChanged()
        }
        Unit
    }

    private fun sourceStatusBindingLocked(category: String): List<String?> = connection.prepareStatement(
        "SELECT last_attempt_utc,last_success_utc,read_status,rejected,incomplete FROM sync_status WHERE category=?").use { statement ->
        statement.setString(1,category);statement.executeQuery().use { row -> if(row.next()) (1..5).map { row.getString(it) } else emptyList() }
    }

    @Synchronized
    fun completeActivities(result: ReadResult<ActivityRecord>, at: Instant) = complete("activities", result.status,
        result.rejected, result.incomplete, at, result.records.map { Triple(it.source, it.sourceRecordId, it.startLocal.take(10)) to Json.encodeToString(it) })

    @Synchronized
    fun completeWellness(result: ReadResult<WellnessRecord>, at: Instant) = complete("wellness", result.status,
        result.rejected, result.incomplete, at, result.records.map { Triple(it.source, it.sourceRecordId, it.date) to Json.encodeToString(it) })

    private fun complete(category: String, status: ReadStatus, rejected: Int, incomplete: Int, at: Instant,
                         records: List<Pair<Triple<String, String, String>, String>>) = transaction {
        val table = categoryTable(category)
        val previousStatus=sourceStatusBindingLocked(category)
        if (status == ReadStatus.SUCCESS) {
            // Validate every input, even a duplicate overwritten later in this batch.
            for ((identity, _) in records) {
                require(identity.first.matches(Regex("[a-zA-Z0-9_.-]{1,64}")) && identity.second.length in 1..128)
                LocalDate.parse(identity.third)
            }
            val finalRecords = records.associateBy { it.first.first to it.first.second }.values
            connection.prepareStatement("SELECT record_json FROM $table WHERE source=? AND source_id=?").use { select ->
                connection.prepareStatement("INSERT INTO $table(source,source_id,observed_date,record_json) VALUES (?,?,?,?) ON CONFLICT(source,source_id) DO UPDATE SET observed_date=excluded.observed_date,record_json=excluded.record_json").use { insert ->
                    for ((identity, json) in finalRecords) {
                        select.setString(1, identity.first); select.setString(2, identity.second)
                        val previous = select.executeQuery().use { if (it.next()) it.getString(1) else null }
                        val currentWellness=if(category=="wellness") Json.decodeFromString<WellnessRecord>(json) else null
                        val previousWellness=if(category=="wellness") previous?.let { Json.decodeFromString<WellnessRecord>(it) } else null
                        val changed: Boolean
                        val sport: String?
                        val sleepChanged: Boolean
                        val sleepAvailable: Boolean
                        if (category == "activities") {
                            val current = Json.decodeFromString<ActivityRecord>(json)
                            changed = previous == null || current != Json.decodeFromString<ActivityRecord>(previous)
                            sport = current.sport
                            sleepChanged = false
                            sleepAvailable = false
                        } else {
                            val current = requireNotNull(currentWellness)
                            val old = previousWellness
                            changed = current != old
                            sport = null
                            sleepChanged = listOf("sleepSecs", "sleepScore").any { key ->
                                current.measurements[key]?.let { it != old?.measurements?.get(key) } == true
                            }
                            sleepAvailable = listOf("sleepSecs", "sleepScore").any { it in current.measurements }
                        }
                        if (!changed) continue
                        currentWellness?.let { current ->
                            // Record both date sides and all present signal fields: even a provenance-only
                            // correction changes the whole-record receive revision used by dependencies.
                            val fields=(current.measurements.keys+previousWellness?.measurements.orEmpty().keys)
                                .intersect(setOf("sleepSecs","hrv","hrvSDNN"))
                            SignalMutationStore(connection).record(identity.first,scopedActivityId(identity.first,identity.second),
                                previousWellness?.date,current.date,fields)
                        }
                        insert.setString(1, identity.first); insert.setString(2, identity.second)
                        insert.setString(3, identity.third); insert.setString(4, json); insert.executeUpdate()
                        if (category == "activities") ScopedHistoryReads.saveIdentity(connection, identity.first, identity.second)
                         appendReviewChangeLocked(category, identity, at, sport, sleepChanged, sleepAvailable)
                         staleConnectedReviewsLocked("evidence_changed")
                    }
                }
            }
        }
        // Failed/empty source reads never silently remove previously imported
        // history. Last successful read remains separate from the latest attempt.
        connection.prepareStatement("UPDATE sync_status SET read_status=?,rejected=?,incomplete=?,last_success_utc=CASE WHEN ?='SUCCESS' THEN ? ELSE last_success_utc END WHERE category=?").use {
            it.setString(1, status.name); it.setInt(2, rejected); it.setInt(3, incomplete)
            it.setString(4, status.name); it.setString(5, at.toString()); it.setString(6, category); it.executeUpdate()
        }
        if(previousStatus!=sourceStatusBindingLocked(category)) ScopedQueueStore(connection).sourceChanged()
    }

    private fun appendReviewChangeLocked(category: String, identity: Triple<String, String, String>, at: Instant,
                                          sport: String?, sleepChanged: Boolean, sleepAvailable: Boolean) {
        // JSON array encoding is unambiguous even when opaque IDs contain delimiters.
        val tuple = Json.encodeToString(listOf(identity.first, identity.second))
        val digest = AnalysisClaims.hash(tuple)
        connection.prepareStatement("INSERT INTO review_import_changes(category,observed_date,received_utc,identity_sha256,sport,sleep_changed,sleep_available) VALUES (?,?,?,?,?,?,?)").use {
            it.setString(1, category); it.setString(2, identity.third); it.setString(3, at.toString())
            it.setString(4, digest); it.setString(5, sport); it.setInt(6, if (sleepChanged) 1 else 0)
            it.setInt(7, if (sleepAvailable) 1 else 0); it.executeUpdate()
        }
    }

    private fun latestReviewRevisionLocked(): Long = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT coalesce(max(revision),0) FROM review_import_changes").use {
            check(it.next()); it.getLong(1)
        }
    }

    private fun staleConnectedReviewsLocked(reason: String) {
        if(reason.startsWith("context_")) ScopedQueueStore(connection).planReasonChanged() else ScopedQueueStore(connection).sourceChanged()
        connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='STALE',stale_reason=? WHERE state IN ('IN_FLIGHT','RUNNING','PUBLISHED')").use {
            it.setString(1, reason); it.executeUpdate()
        }
    }

    /** Historical fixture seam only: no production preparation caller. New preparation must use owner capture/write-token fences. */
    @Synchronized
    internal fun createConnectedReviewSnapshot(binding: ConnectedReviewBinding, at: Instant): ConnectedReviewStatus = transaction {
        require(binding.requestId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        require(!binding.requestId.startsWith("scoped-execution:")) { "Reserved execution namespace" }
        require(binding.contractVersion == InterpretationContractV1.PROFILE)
        require(binding.providerSelection.storageId.length <= 71)
        val from = LocalDate.parse(binding.coverageFrom); val until = LocalDate.parse(binding.coverageUntil); require(from <= until)
        (binding.retrieval.mandatoryRestrictions+binding.retrieval.optionalContexts).forEach { ContextDeletionStore(connection).validatePreparedContext(it) }
        val packet = InterpretationContractV1.packet(binding.evidence, binding.retrieval)
        require(binding.sport == packet.evidence.selectedSport)
        val packetJson = scheduleJson.encodeToString(packet)
        val packetDigest = InterpretationContractV1.promptSha256(packet)
        val evidenceDigest = packet.evidence.evidenceReportSha256
        require(evidenceDigest.matches(Regex("[a-f0-9]{64}")))
        val refs = packet.context.map { ContextRevisionRefV1(it.contextId, it.revision) }.distinct().sortedWith(compareBy({ it.contextId }, { it.revision }))
        require(refs.size == packet.context.size && refs.all { it.revision > 0 })
        val generation = connection.prepareStatement("SELECT active_generation FROM connected_review_requests WHERE request_id=?").use {
            it.setString(1, binding.requestId); it.executeQuery().use { row -> if (row.next()) Math.addExact(row.getLong(1), 1) else 1L }
        }
        connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='STALE',stale_reason='new_generation' WHERE snapshot_id IN (SELECT snapshot_id FROM connected_review_snapshots WHERE request_id=?) AND state IN ('IN_FLIGHT','RUNNING','PUBLISHED')").use { it.setString(1,binding.requestId); it.executeUpdate() }
        val id = java.util.UUID.randomUUID().toString()
        connection.prepareStatement("INSERT INTO connected_review_snapshots VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
            it.setString(1,id); it.setString(2,binding.requestId); it.setLong(3,generation); it.setString(4,packetJson); it.setString(5,packetDigest)
            it.setString(6,evidenceDigest); it.setString(7,scheduleJson.encodeToString(refs)); it.setString(8,from.toString()); it.setString(9,until.toString())
            it.setString(10,binding.sport); it.setString(11,binding.providerSelection.storageId); it.setString(12,binding.providerSelection.modelId); it.setString(13,binding.contractVersion); it.setString(14,at.toString()); it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO connected_review_snapshot_state VALUES(?,'IN_FLIGHT',NULL,NULL)").use { it.setString(1,id); it.executeUpdate() }
        connection.prepareStatement("INSERT INTO connected_review_requests VALUES(?,?) ON CONFLICT(request_id) DO UPDATE SET active_generation=excluded.active_generation").use { it.setString(1,binding.requestId); it.setLong(2,generation); it.executeUpdate() }
        connection.prepareStatement("INSERT INTO connected_review_context_refs VALUES(?,?,?)").use { insert -> refs.forEach { insert.setString(1,id); insert.setString(2,it.contextId); insert.setInt(3,it.revision); insert.addBatch() }; insert.executeBatch() }
        connectedReviewStatusLocked(id)
    }

    /** Accepts only output already independently validated; never called by runtime in this task. */
    @Synchronized
    override fun publishConnectedReview(snapshotId: String, output: ValidatedConnectedReviewOutput, currentEvidenceDigest: String,
                               currentContext: List<ContextRevisionRefV1>, provider: String, model: String,
                               contractVersion: String): ConnectedReviewStatus = transaction {
        val snapshot = connectedReviewStatusLocked(snapshotId)
        val active = connection.prepareStatement("SELECT active_generation FROM connected_review_requests WHERE request_id=?").use { it.setString(1,snapshot.requestId); it.executeQuery().use { r -> r.next(); r.getLong(1) } }
        if (snapshot.state !in setOf("IN_FLIGHT", "RUNNING") || active != snapshot.generation) return@transaction snapshot
        require(currentEvidenceDigest.matches(Regex("[a-f0-9]{64}")))
        require(currentContext.distinct().size == currentContext.size && currentContext.all { it.revision > 0 })
        val reason = when {
            snapshot.evidenceDigest != currentEvidenceDigest -> "evidence_changed"
            snapshot.context.toSet() != currentContext.toSet() -> "context_changed"
            snapshot.provider != provider || snapshot.model != model -> "provider_changed"
            snapshot.contractVersion != contractVersion -> "contract_changed"
            else -> null
        }
        if (reason != null) {
            connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='STALE',stale_reason=? WHERE snapshot_id=?").use { it.setString(1,reason); it.setString(2,snapshotId); it.executeUpdate() }
            return@transaction connectedReviewStatusLocked(snapshotId)
        }
        require(output.text.length <= 12_000)
        connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='PUBLISHED',published_output=? WHERE snapshot_id=? AND state IN ('IN_FLIGHT','RUNNING')").use { it.setString(1,output.text); it.setString(2,snapshotId); check(it.executeUpdate()==1) }
        connectedReviewStatusLocked(snapshotId)
    }

    @Synchronized
    override fun connectedReviewStatus(snapshotId: String): ConnectedReviewStatus = connectedReviewStatusLocked(snapshotId)

    @Synchronized
    override fun failConnectedReview(snapshotId: String, reason: String): ConnectedReviewStatus = transaction {
        require(reason.matches(Regex("[a-z][a-z0-9_]{1,63}")))
        connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='FAILED',stale_reason=? WHERE snapshot_id=? AND state IN ('IN_FLIGHT','RUNNING')").use {
            it.setString(1, reason); it.setString(2, snapshotId); it.executeUpdate()
        }
        connectedReviewStatusLocked(snapshotId)
    }

    @Synchronized
    override fun claimConnectedReview(snapshotId: String): ConnectedReviewStatus? = transaction {
        val claimed = connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='RUNNING' WHERE snapshot_id=? AND state='IN_FLIGHT'").use {
            it.setString(1, snapshotId); it.executeUpdate() == 1
        }
        if (claimed) connectedReviewStatusLocked(snapshotId) else null
    }

    @Synchronized
    override fun connectedReviewPacket(snapshotId: String): InterpretationPacketV1 = connection.prepareStatement(
        "SELECT s.packet_json,s.packet_sha256,x.state FROM connected_review_snapshots s JOIN connected_review_snapshot_state x USING(snapshot_id) WHERE s.snapshot_id=?").use {
        it.setString(1, snapshotId); it.executeQuery().use { row ->
            if (!row.next()) throw ConnectedReviewNotFound()
            val packetJson = row.getString(1)
            val packet = scheduleJson.decodeFromString<InterpretationPacketV1>(packetJson)
            require(row.getString(2) == InterpretationContractV1.promptSha256(packet)) {
                "Connected review packet is unavailable"
            }
            packet
        }
    }

    @Synchronized
    override fun connectedReviewInspection(snapshotId: String): ConnectedReviewInspection {
        val status = connectedReviewStatusLocked(snapshotId)
        val selection = ConnectedReviewProviderSelection(status.provider.substringAfter(':', ""), status.model,
            status.provider.substringBefore(':'))
        val packet = try { connectedReviewPacket(snapshotId) } catch (_: Exception) { null }
        val review = status.publishedOutput?.let { raw ->
            runCatching { scheduleJson.decodeFromString<InterpretationDraftV1>(raw) }.getOrNull()
        }
        return ConnectedReviewInspection(status, selection,
            packet?.let(InterpretationContractV1::prompt) ?: InterpretationPromptV1("", ""), review, packet != null)
    }

    private fun connectedReviewStatusLocked(id: String): ConnectedReviewStatus = connection.prepareStatement("SELECT s.snapshot_id,s.request_id,s.generation,s.evidence_digest,s.packet_sha256,s.refs_json,s.coverage_from,s.coverage_until,s.sport,s.provider,s.model,s.contract_version,x.state,x.stale_reason,x.published_output,s.created_utc FROM connected_review_snapshots s JOIN connected_review_snapshot_state x USING(snapshot_id) WHERE s.snapshot_id=?").use {
        it.setString(1,id); it.executeQuery().use { r -> if (!r.next()) throw ConnectedReviewNotFound()
            ConnectedReviewStatus(r.getString(1),r.getString(2),r.getLong(3),r.getString(4),r.getString(5),scheduleJson.decodeFromString(r.getString(6)),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getString(14),r.getString(15),r.getString(16))
        }
    }

    private fun sha256(value: String) = AnalysisClaims.hash(value)


    private fun reviewScheduleStateLocked(): ReviewSchedulerState = try {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT singleton,state_json FROM review_schedule").use {
                check(it.next() && it.getInt(1) == 1)
                val state = HistorySchema.decodeState(it.getString(2))
                check(!it.next() && state.version >= 0 && state.cursor >= 0)
                state
            }
        }
    } catch (_: Exception) {
        throw IllegalStateException("Review schedule persistence failed; contents withheld")
    }

    private fun saveInitialReviewStateLocked() {
        connection.prepareStatement("INSERT INTO review_schedule(singleton,state_json) VALUES (1,?)").use {
            it.setString(1, scheduleJson.encodeToString(ReviewSchedulerState())); it.executeUpdate()
        }
    }

    private fun saveReviewStateLocked(state: ReviewSchedulerState) {
        connection.prepareStatement("UPDATE review_schedule SET state_json=? WHERE singleton=1").use {
            it.setString(1, scheduleJson.encodeToString(state)); check(it.executeUpdate() == 1)
        }
        ScopedQueuePreviewStore(connection).prune((state.queue.outcomes.filter { it.scopedIntent!=null }.map { it.id }+
            state.queue.pending.filter { it.scopedIntent!=null }.map { it.id }+listOfNotNull(state.queue.running?.takeIf { it.scopedIntent!=null }?.id)).toSet())
        ScopedQueueStore(connection).pruneOwnedSnapshots(state.queue.outcomes.mapNotNull { it.snapshotId }.toSet())
    }

    @Synchronized
    fun reviewScheduleState(): ReviewSchedulerState = reviewScheduleStateLocked()

    @Synchronized
    fun updateReviewSchedule(expectedVersion: Long, configuration: ReviewScheduleConfiguration, at: Instant,
                             modelId: String?, modelVersion: Long?): ReviewSchedulerState = transaction {
        val previous = reviewScheduleStateLocked()
        if (previous.version != expectedVersion) throw ReviewScheduleConflict()
        val state = ReviewSchedulerState(configuration, Math.addExact(previous.version, 1), latestReviewRevisionLocked(),
            modelVersion, modelId, if (modelId != null && modelVersion != null) at.toString() else null,
            queue = ReviewQueueRules.invalidate(previous.queue, "review_configuration_changed", at),purposeHighWater=previous.purposeHighWater)
        saveReviewStateLocked(state)
        connection.createStatement().use { check(it.executeUpdate("UPDATE scoped_review_configuration SET timezone_confirmed=1 WHERE singleton=1") == 1) }
        state
    }

    @Synchronized
    fun evaluateReviewSchedule(evaluate: (ReviewSchedulerState, List<ImportedReviewChange>, Long,
                                          (LocalDate, LocalDate) -> HistoryResponse) -> Pair<ReviewSchedulerState, ReviewScheduleStatus>): ReviewScheduleStatus = transaction {
        evaluateReviewScheduleLocked(evaluate,persist=true)
    }

    /** Snapshot-consistent projection only: no schedule/queue writes or external lifecycle callbacks. */
    @Synchronized
    internal fun inspectReviewSchedule(inspect: (ReviewSchedulerState,List<ImportedReviewChange>,Long,
        (LocalDate,LocalDate)->HistoryResponse)->Pair<ReviewSchedulerState,ReviewScheduleStatus>): ReviewScheduleStatus = transaction {
        evaluateReviewScheduleLocked(inspect,persist=false)
    }

    private fun evaluateReviewScheduleLocked(evaluate: (ReviewSchedulerState,List<ImportedReviewChange>,Long,
        (LocalDate,LocalDate)->HistoryResponse)->Pair<ReviewSchedulerState,ReviewScheduleStatus>,persist: Boolean): ReviewScheduleStatus {
        val state = reviewScheduleStateLocked()
        val latestRevision = latestReviewRevisionLocked()
        val changes = connection.prepareStatement("SELECT revision,category,observed_date,received_utc,identity_sha256,sport,sleep_changed,sleep_available FROM review_import_changes WHERE revision>? ORDER BY revision LIMIT 50001").use {
            it.setLong(1, state.cursor)
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    if (size == 50_000) throw TrendSizeLimit()
                    add(ImportedReviewChange(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4),
                         rows.getString(5), rows.getString(6), rows.getInt(7) != 0, rows.getInt(8) != 0))
                }
            } }
        }
        val (updated, status) = evaluate(state, changes, latestRevision) { oldest, newest -> history(oldest, newest, 50_000) }
        if(persist) saveReviewStateLocked(updated)
        return status
    }

    /** Legacy evaluation and new purpose occurrence admission share this one owner transaction. */
    @Synchronized
    internal fun evaluatePersonalSchedule(at: Instant,scopedArmedAt: Instant?,persist: Boolean,durableQueue: Boolean,
        evaluate: (ReviewSchedulerState,List<ImportedReviewChange>,Long,(LocalDate,LocalDate)->HistoryResponse)->Pair<ReviewSchedulerState,ReviewScheduleStatus>): ReviewScheduleStatus = transaction {
        evaluateReviewScheduleLocked({ state,changes,revision,history ->
            val (legacy,status)=evaluate(state,changes,revision,history)
            val slots=ReviewPurposeSlots.evaluate(state.configuration.purposeSlots,state.configuration.timeZone,state.version,
                scopedArmedAt,state.configuration.paused || !state.configuration.enabled,state.purposeHighWater,at)
            val revisions=ScopedQueueStore(connection).revisions()
            val submissions=slots.due.map { occurrence ->
                val scope=ReviewScopeResolver.resolve(occurrence.request.selection,at,state.configuration.timeZone)
                ReviewQueueRules.ScopedQueueSubmission(ScopedQueueIntent(occurrence.profile,occurrence.purpose,state.version,scope,revisions.source,revisions.planReason,occurrence.providerSelection),
                    occurrence.occurrenceId,listOf("purpose_slot"))
            }
            val next=if(persist && durableQueue) legacy.copy(purposeHighWater=slots.highWater,
                queue=ReviewQueueRules.enqueueScoped(legacy.queue,submissions,state.configuration.queuePolicy,at)) else legacy
            next to status.copy(purposeSlots=slots.statuses,purposeDue=slots.due)
        },persist)
    }

    @Synchronized
    internal fun <T> updateReviewQueue(operation: (ReviewSchedulerState) -> Pair<ReviewSchedulerState, T>): T = transaction {
        val (state, result) = operation(reviewScheduleStateLocked())
        saveReviewStateLocked(state)
        result
    }

    @Synchronized
    internal fun scopedRoutingRevisions(): ScopedRoutingRevisions = ScopedQueueStore(connection).revisions()

    private fun scopedJobCurrentLocked(job: ReviewQueueJob,state: ReviewSchedulerState): Boolean {
        val intent=job.scopedIntent ?: return false
        return state.queue.running==job && intent.configurationVersion==state.version && !state.configuration.paused &&
            (job.manual || state.configuration.enabled) && intent.scope.timeZone==(intent.adaptation?.family?.timeZone ?: state.configuration.timeZone)
    }

    @Synchronized
    internal fun captureScopedQueueJob(job: ReviewQueueJob,at: Instant): CapturedScopedQueueJob = transaction {
        captureScopedQueueJobLocked(job,at)
    }

    @Synchronized
    internal fun captureScopedQueueJob(job: ReviewQueueJob,clock: java.time.Clock,adaptationKey: String?=null): CapturedScopedQueueJob = transaction {
        captureScopedQueueJobLocked(job,clock.instant(),adaptationKey)
    }

    private fun captureScopedQueueJobLocked(job: ReviewQueueJob,at: Instant,adaptationKey: String?=null): CapturedScopedQueueJob {
        if(!scopedJobCurrentLocked(job,reviewScheduleStateLocked())) throw ScopedEvidenceConflict()
        val intent=requireNotNull(job.scopedIntent)
        // The family queue primitive is not yet bound to a coherent adaptation snapshot.
        // Refuse before preparation rather than publishing descriptive facts as its result.
        require(intent.adaptation==null) { "Adaptation evidence admission is not integrated" }
        // The reporting dates stay explicit. The snapshot cutoff is the actual preparation read, not a claim
        // that today's imported database reconstructs what was known at the earlier scheduled/routing instant.
        require(at>=Instant.parse(intent.scope.asOf))
        val request=ScopedEvidenceRequest(selection=intent.scope.request,purpose=intent.purpose)
        return CapturedScopedQueueJob(job,request,captureScopedEvidenceLocked(request,at,adaptationKey),ScopedQueueStore(connection).revisions())
    }

    @Synchronized
    internal fun publishScopedQueueJob(capture: CapturedScopedQueueJob,prepared: PreparedScopedEvidence,at: Instant): ScopedEvidenceSnapshot = transaction {
        if(capture.evidence.adaptationReference?.let { at>=it.stored.validUntil }==true) throw ScopedEvidenceConflict()
        val state=reviewScheduleStateLocked()
        if(!scopedJobCurrentLocked(capture.job,state) || capture.revisions!=ScopedQueueStore(connection).revisions() ||
            totalChangesLocked()!=capture.evidence.totalChanges || prepared.captured!==capture.evidence || prepared.request!=capture.request)
            throw ScopedEvidenceConflict()
        val owned=ScopedQueueStore(connection)
        owned.pruneOwnedSnapshots(state.queue.outcomes.mapNotNull { it.snapshotId }.toSet())
        val snapshot=ScopedEvidenceStore(connection).insert(prepared,at)
        owned.ownSnapshot(snapshot.snapshotId,capture.job.id)
        val outcome=ReviewQueueOutcome(capture.job.id,null,if(snapshot.state=="BLOCKED") "blocked" else "factual_prepared",
            if(snapshot.state=="BLOCKED") "evidence_preparation_blocked" else "profile_execution_closed_plan_unchanged",at.toString(),capture.job.startedUtc,
            snapshotId=snapshot.snapshotId,scopedIntent=requireNotNull(capture.job.scopedIntent).copy(sourceRevision=capture.revisions.source,planReasonRevision=capture.revisions.planReason),
            evidenceCutoffUtc=snapshot.manifest.scope.asOf,preview=capture.job.scopedIntent.providerSelection?.let {
                if(snapshot.state=="BLOCKED") ScopedQueuePreview("blocked","evidence_preparation_blocked") else ScopedQueuePreview("not_prepared","preview_not_prepared") })
        val queue=ReviewQueueRules.finish(state.queue,outcome,state.configuration.queuePolicy,at)
        saveReviewStateLocked(state.copy(queue=queue))
        snapshot
    }

    /** A second commit after factual completion. Exact preview assembly has already finished outside the monitor. */
    @Synchronized internal fun attachScopedQueuePreview(job: ReviewQueueJob,snapshotId: String,prepared: PreparedScopedExecution) = transaction {
        checkExecutionAdaptationLocked(prepared.adaptationCapture)
        if(!scopedSourceTimeCurrent(prepared.binding,constraintClock.instant())) throw ScopedEvidenceConflict()
        if(totalChangesLocked()!=prepared.totalChanges) throw ScopedEvidenceConflict()
        val state=reviewScheduleStateLocked()
        val outcome=state.queue.outcomes.singleOrNull { it.id==job.id } ?: throw ScopedEvidenceConflict()
        val intent=outcome.scopedIntent ?: throw ScopedEvidenceConflict()
        val binding=prepared.binding
        if(outcome.snapshotId!=snapshotId || outcome.status!="factual_prepared" || outcome.preview?.state!="not_prepared" ||
            intent.configurationVersion!=state.version || state.configuration.paused || (!job.manual && !state.configuration.enabled) ||
            job.key!=scopedQueueKey(intent) || binding.source.snapshotId!=snapshotId || binding.providerSelection!=intent.providerSelection ||
            binding.profile!=intent.profile) throw ScopedEvidenceConflict()
        ScopedExecutionStore(connection).insert(prepared)
        if(!job.manual) ScopedQueuePreviewStore(connection).own(binding.executionId,job.id)
        val preview=ScopedQueuePreview(if(binding.providerSelection.providerKind=="hosted") "awaiting_consent" else "blocked_admission",
            if(binding.providerSelection.providerKind=="hosted") "exact_consent_required_execution_closed" else "runtime_admission_closed",
            binding.executionId,binding.createdUtc)
        saveReviewStateLocked(state.copy(queue=state.queue.copy(outcomes=state.queue.outcomes.map { if(it.id==job.id) it.copy(preview=preview) else it })))
    }

    @Synchronized internal fun blockScopedQueuePreview(jobId: String,snapshotId: String) = transaction {
        val state=reviewScheduleStateLocked()
        val old=state.queue.outcomes.singleOrNull { it.id==jobId && it.snapshotId==snapshotId && it.preview?.state=="not_prepared" }
            ?: return@transaction
        val updated=old.copy(preview=ScopedQueuePreview("blocked","preview_preparation_unavailable"))
        saveReviewStateLocked(state.copy(queue=state.queue.copy(outcomes=state.queue.outcomes.map { if(it.id==jobId) updated else it })))
    }

    @Synchronized
    internal fun reviewJobReport(job: ReviewQueueJob, today: LocalDate, latestAllowedDate: LocalDate = today): TrendReport {
        val intent=job.requireLegacyIntent()
        val range = TrendRange(LocalDate.parse(intent.oldest), LocalDate.parse(intent.newest))
        val records = history(range.previous().oldest, range.newest, 50_000)
        val selected = if (intent.scope.kind != "activity") records else records.copy(activities = records.activities.filter { activity ->
            AnalysisClaims.hash(Json.encodeToString(listOf(activity.source, activity.sourceRecordId))) == intent.activitySha256
        })
        return Trends.report(selected, range, intent.scope.sport, today, statuses(today), latestAllowedDate)
    }

    @Synchronized
    override fun events(): List<ManualEvent> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT id,start_date,end_date,sport,goal,notes FROM events ORDER BY start_date,end_date,id LIMIT 1001").use { rows ->
            buildList {
                while (rows.next()) {
                    require(size < 1000) { "Event list exceeds bound" }
                    add(ManualEvent(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6)))
                }
            }
        }
    }

    @Synchronized
    override fun saveEvent(event: ManualEvent) = transaction {
        connection.prepareStatement("SELECT count(*) FROM events WHERE id<>?").use {
            it.setString(1, event.id); it.executeQuery().use { rows -> check(rows.next()); require(rows.getInt(1) < 1000) { "Event capacity reached" } }
        }
        connection.prepareStatement("INSERT INTO events(id,start_date,end_date,sport,goal,notes) VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET start_date=excluded.start_date,end_date=excluded.end_date,sport=excluded.sport,goal=excluded.goal,notes=excluded.notes").use {
            it.setString(1, event.id); it.setString(2, event.startDate); it.setString(3, event.endDate)
            it.setString(4, event.sport); it.setString(5, event.goal); it.setString(6, event.notes); it.executeUpdate()
        }
        Unit
    }

    @Synchronized
    override fun deleteEvent(id: String): Boolean = connection.prepareStatement("DELETE FROM events WHERE id=?").use {
        it.setString(1, id); it.executeUpdate() > 0
    }

    @Synchronized override fun intakes() = intakeStore.list()
    @Synchronized override fun intakeHistory(id: String) = intakeStore.history(id)
    @Synchronized override fun createIntake(request: ContextIntakeRequest, at: Instant) = constraintTransaction { intakeStore.create(request, at).also { ScopedQueueStore(connection).planReasonChanged() } }
    @Synchronized override fun correctIntake(id: String, request: ContextIntakeCorrection, at: Instant) = constraintTransaction {
        val result = intakeStore.correct(id, request, at)
        intakeStore.invalidateReviews(id)
        ScopedQueueStore(connection).planReasonChanged()
        result
    }
    @Synchronized override fun intakeLinks(id: String) = intakeStore.links(id)
    @Synchronized override fun associateIntake(id: String, request: ContextIntakeAssociation) = constraintTransaction { intakeStore.associate(id, request).also { ScopedQueueStore(connection).planReasonChanged() } }
    @Synchronized override fun confirmIntake(id: String, request: ContextConfirmationRequest, at: Instant) = constraintTransaction {
        val result=ContextConfirmationStore(connection).confirm(id,request,at)
        if(!result.replayed && result.contexts.isNotEmpty()) staleConnectedReviewsLocked("context_changed")
        else if(!result.replayed) ScopedQueueStore(connection).planReasonChanged()
        result
    }

    @Synchronized override fun commitments() = commitmentStore.list()
    @Synchronized override fun commitmentHistory(id: String) = commitmentStore.history(id)
    @Synchronized override fun confirmCommitment(request: CommitmentConfirmation, at: Instant) = constraintTransaction { commitmentStore.confirm(request,at).also { ScopedQueueStore(connection).planReasonChanged() } }
    @Synchronized override fun correctCommitment(id: String, request: CommitmentCorrection, at: Instant) = constraintTransaction { commitmentStore.correct(id,request,at).also { ScopedQueueStore(connection).planReasonChanged() } }
    @Synchronized override fun previewContextDeletion(id: String, at: Instant) = transaction { ContextDeletionStore(connection).preview(id,at) }
    @Synchronized override fun redactAndDeleteContext(id: String, request: ContextDeletionRequest, at: Instant) = constraintTransaction(deleting=true) { ContextDeletionStore(connection).apply(id,request,at).also { ScopedQueueStore(connection).planReasonChanged() } }
    @Synchronized override fun plans() = transaction { PlanStore(connection).list() }
    @Synchronized override fun plan(id: String, revision: Int?) = PlanStore(connection).get(id,revision)
    @Synchronized override fun createPlan(request: AuthoredPlanRequest, at: Instant) = transaction { PlanStore(connection).create(request,at).also { ScopedQueueStore(connection).planReasonChanged() } }
    @Synchronized override fun correctPlan(id: String, request: PlanCorrection, at: Instant) = transaction {
        PlanStore(connection).correct(id,request,at).also {
            ConstraintEventStore(connection).enrollSelected(constraintClock.instant())
            ScopedQueueStore(connection).planReasonChanged()
        }
    }
    @Synchronized override fun selectPlan(request: PlanSelectionRequest) = transaction {
        PlanStore(connection).select(request).also {
            ConstraintEventStore(connection).enrollSelected(constraintClock.instant())
            ScopedQueueStore(connection).planReasonChanged()
        }
    }

    /** Observe canonical/provenance boundaries, never infer materiality from these opaque receipts. */
    private fun <T> constraintTransaction(deleting:Boolean=false,block:()->T): T = transaction {
        val at=constraintClock.instant()
        val events=ConstraintEventStore(connection)
        val budget=ConstraintProofBudget()
        val previous=ConstraintMutationStore(connection).frontier()
        // Deletion has a separate purge path: never persist its old sensitive endpoint.
        val proofBefore=if(!deleting && events.hasAvailable()) try {
            ConstraintCanonicalProof.capture(connection,at,budget)
        } catch (_: ConstraintProofLimit) { events.invalidate();null } else null
        val before=ConstraintObservationHeads.capture(connection)
        val result=block()
        val after=ConstraintObservationHeads.capture(connection)
        val changed=(before.keys+after.keys).filter { before[it]!=after[it] }.map { key ->
            after[key]?.ref ?: before.getValue(key).ref.copy(present=false)
        }
        if(changed.isNotEmpty()) {
            // Proposals bind the complete local constraint/source inventory. On correction
            // or deletion, purge their copied operations/explanations, not merely freshness.
            // New identities only stale prior bindings; they have no prior text to redact.
            if(deleting || before.keys.any { before[it]!=after[it] }) PlanAmendmentProposalStore(connection).purge()
            val mutations=ConstraintMutationStore(connection)
            if(deleting) mutations.invalidateForDeletion()
            val current=mutations.append(changed)
            if(proofBefore!=null) try {
                events.observe(proofBefore,ConstraintCanonicalProof.capture(connection,at,budget),previous,current,at,budget)
            } catch (_: ConstraintProofLimit) { events.invalidate() }
        }
        result
    }

    @Synchronized override fun signalRules()=PersonalSignalRuleStore(connection).list()

    @Synchronized override fun workloadRules()=ExtraWorkloadRuleStore(connection).list()

    @Synchronized override fun saveWorkloadRule(request: ExtraWorkloadRuleRequest,at: Instant): ExtraWorkloadRuleReceipt = transaction {
        ExtraWorkloadRuleStore(connection).save(request,at).also { if(!it.replayed) ScopedQueueStore(connection).planReasonChanged() }
    }

    @Synchronized override fun capturePlanAmendment(at: Instant): CapturedPlanAmendment = transaction {
        capturePlanAmendmentLocked(at)
    }

    private fun capturePlanAmendmentLocked(at: Instant): CapturedPlanAmendment {
        val version=dataVersionLocked()
        val plans=PlanStore(connection)
        val selection=plans.selection()
        val plan=selection.selected?.let { plans.get(it.planId,it.revision) }
        val contexts=if(plan?.definition?.policy!=null) {
            val date=at.atZone(java.time.ZoneId.of(plan.definition.policy.adaptationTimeZone)).toLocalDate()
            contextRows.latest().map { lifecycleStore.view(it,date) }
        } else emptyList()
        val (links,heads)=if(plan?.definition?.policy!=null) captureContextBindingsLocked() else emptyList<ContextIntakeLink>() to emptyMap<String,Int>()
        return CapturedPlanAmendment(PlanAmendmentInputs(selection,plan,contexts,if(plan?.definition?.policy!=null) commitmentStore.list() else emptyList(),links,heads),
            at,evidenceCaptureOwner,totalChangesLocked(),version)
    }

    @Synchronized internal fun captureAmendmentSelection(snapshotId: String,at: Instant): CapturedAmendmentSelection = transaction {
        validateContextId(snapshotId)
        val policy=capturePlanAmendmentLocked(at)
        val source=ScopedExecutionSource(snapshotId=snapshotId)
        val evidence=captureExecutionSourceLocked(source,scopedExecutionRequestId(AmendmentSelectionContractV1.PROFILE,snapshotId),at)
        CapturedAmendmentSelection(policy,evidence)
    }

    /** Pure preparation and validation both end here, without storing executions or proposals. */
    @Synchronized internal fun finishAmendmentSelection(capture: CapturedAmendmentSelection,validUntil: Instant,clock: java.time.Clock) = transaction {
        checkAmendmentSelectionLocked(capture,validUntil,clock)
    }

    private fun checkAmendmentSelectionLocked(capture: CapturedAmendmentSelection,validUntil: Instant,clock: java.time.Clock) {
        val now=clock.instant();val policy=capture.policy
        if(policy.ownerId!=evidenceCaptureOwner || policy.totalChanges!=totalChangesLocked() || policy.dataVersion!=dataVersionLocked() ||
            policy.input.selection!=PlanStore(connection).selection() || now<policy.at || now>=validUntil || validUntil>policy.at.plusSeconds(86400)) throw PlanConflict()
        checkExecutionAdaptationLocked(capture.source.scoped?.adaptationReference?.current)
        val stored=ScopedEvidenceStore(connection).get(requireNotNull(capture.source.source.snapshotId))
        if(stored!=capture.source.snapshot) throw ScopedEvidenceConflict()
    }

    @Synchronized internal fun proposalForSubmission(digest: String): String? = transaction {
        PlanAmendmentProposalStore(connection).findSubmission(digest)?.proposalId
    }

    @Synchronized internal fun amendmentProposalIndex(offset: Int,limit: Int): PlanAmendmentProposalIndex = transaction {
        PlanAmendmentProposalStore(connection).index(offset,limit)
    }

    @Synchronized internal fun amendmentApprovalReplay(id: String,operationId: String,digest: String): StoredPlanAmendmentApproval? = transaction {
        PlanAmendmentApprovalStore(connection).replay(id,operationId,digest)
    }

    @Synchronized internal fun applyPlanAmendment(prepared: PreparedProposalApproval,clock: java.time.Clock): StoredPlanAmendmentApproval = transaction {
        val captured=prepared.captured;val row=captured.stored
        val approvals=PlanAmendmentApprovalStore(connection)
        approvals.replay(row.proposalId,prepared.request.operationId,prepared.requestSha256)?.let { return@transaction it }
        val proposals=PlanAmendmentProposalStore(connection)
        if(proposals.get(row.proposalId)!=row || row.state!="pending") throw PlanConflict()
        val proposal=prepared.proposal()
        val capture=captured.capture
        val at=clock.instant()
        checkAmendmentSelectionLocked(capture,Instant.parse(row.expiresUtc),java.time.Clock.fixed(at,java.time.ZoneOffset.UTC))
        val input=capture.policy.input
        if(PlanAmendmentPolicy.checkWholeResult(input,at,proposal.selected).isNotEmpty()) throw PlanConflict()
        val base=requireNotNull(input.plan)
        if(base.reference!=prepared.request.expectedBasePlan || input.selection.generation!=prepared.request.expectedSelectionGeneration ||
            row.projectionSha256!=prepared.request.expectedProposalSha256 || proposal.selected.isEmpty() || proposal.outcome!="selected") throw PlanConflict()
        val replacements=proposal.selected.associate { it.sessionId to it.after }
        val definition=base.definition.copy(sessions=base.definition.sessions.map { replacements[it.sessionId] ?: it })
        val saved=PlanStore(connection).correctExact(base.reference.planId,PlanCorrection(base.reference.revision,input.selection.generation,definition),at)
        ConstraintEventStore(connection).enrollSelected(at)
        ScopedQueueStore(connection).planReasonChanged()
        proposals.markApplied(row.proposalId,row.projectionSha256)
        val receipt=approvals.insert(StoredPlanAmendmentApproval(prepared.request.operationId,row.proposalId,saved.reference,at.toString()),prepared.requestSha256)
        // Own writes invalidate the base binding; only the final clock/deadline fence remains.
        val finish=clock.instant();val policy=requireNotNull(base.definition.policy)
        val deadline=(listOf(Instant.parse(row.expiresUtc),requireNotNull(capture.source.validUntil),capture.policy.at.plusSeconds(86400))+
            proposal.selected.flatMap { listOf(amendmentStartCutoff(it.before,policy),amendmentStartCutoff(it.after,policy)) }).min()
        if(finish<at || finish<capture.policy.at || finish>=deadline) throw PlanConflict()
        receipt
    }

    @Synchronized internal fun appliedProposalInspection(id: String): PlanAmendmentProposalInspection? = transaction {
        val row=PlanAmendmentProposalStore(connection).get(id)
        if(row.state=="applied") appliedProposalInspectionLocked(row) else null
    }

    private fun appliedProposalInspectionLocked(row: StoredPlanAmendmentProposal): PlanAmendmentProposalInspection {
        val receipt=PlanAmendmentApprovalStore(connection).getForProposal(row.proposalId) ?: throw PlanConflict()
        return PlanAmendmentProposalInspection(row.proposalId,row.projectionSha256,"applied",null,"not_applicable","applied",
            row.createdUtc,row.expiresUtc,null,null,appliedReceipt=receipt.receipt())
    }

    @Synchronized internal fun savePlanAmendmentProposal(prepared: PreparedProposalSave,clock: java.time.Clock): StoredPlanAmendmentProposal = transaction {
        checkAmendmentSelectionLocked(prepared.capture,Instant.parse(prepared.row.expiresUtc),clock)
        PlanAmendmentProposalStore(connection).insert(prepared.row)
    }

    @Synchronized internal fun captureProposalInspection(id: String,at: Instant): CapturedProposalInspection = transaction {
        val row=PlanAmendmentProposalStore(connection).get(id)
        val policy=capturePlanAmendmentLocked(at)
        val source=ScopedExecutionSource(snapshotId=row.snapshotId)
        val evidence=captureExecutionSourceLocked(source,scopedExecutionRequestId(AmendmentSelectionContractV1.PROFILE,row.snapshotId),at)
        CapturedProposalInspection(row,CapturedAmendmentSelection(policy,evidence))
    }

    @Synchronized internal fun finishProposalInspection(captured: CapturedProposalInspection,current: Boolean,clock: java.time.Clock): PlanAmendmentProposalInspection = transaction {
        val policy=captured.capture.policy;val now=clock.instant()
        val row=PlanAmendmentProposalStore(connection).get(captured.stored.proposalId)
        if(policy.ownerId!=evidenceCaptureOwner || policy.totalChanges!=totalChangesLocked() || policy.dataVersion!=dataVersionLocked() ||
            row!=captured.stored || now<policy.at) throw PlanConflict()
        if(row.state=="applied") return@transaction appliedProposalInspectionLocked(row)
        val inspection=ProposalFreshness.inspection(row,current,now)
        val proposal=inspection.proposal
        val result=if(proposal==null) inspection else {
            val input=policy.input;val plan=requireNotNull(input.plan)
            inspection.copy(reviewConstraints=AmendmentReviewConstraints(row.proposalId,row.projectionSha256,proposal.policyInputSha256,
                proposal.basePlan,proposal.selectionGeneration,proposal.policyVersion,policy.at.toString(),requireNotNull(plan.definition.policy),
                proposal.horizon,input.contexts,input.commitments,plan.definition.sessions.map { AmendmentReviewOccupancy(it.sessionId,it.schedule,it.omitted) }))
        }
        if(ScopedEvidencePreparation.json.encodeToString(result).toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
        result
    }

    @Synchronized internal fun rejectPlanAmendmentProposal(id: String,digest: String,at: Instant): StoredPlanAmendmentProposal = transaction {
        val row=PlanAmendmentProposalStore(connection).get(id)
        if(at<Instant.parse(row.createdUtc)) throw PlanConflict()
        PlanAmendmentProposalStore(connection).reject(id,digest,at)
    }

    @Synchronized override fun finishPlanAmendment(capture: CapturedPlanAmendment,report: PlanAmendmentInspection,clock: java.time.Clock): PlanAmendmentInspection = transaction {
        val now=clock.instant()
        val deadline=PlanAmendmentPolicy.validUntil(capture.input,capture.at,report.candidates)
        if(capture.ownerId!=evidenceCaptureOwner || capture.totalChanges!=totalChangesLocked() || capture.dataVersion!=dataVersionLocked() ||
            capture.input.selection!=PlanStore(connection).selection() || now<capture.at || now>=Instant.parse(report.validUntilUtc) ||
            report.validUntilUtc!=deadline.toString() || now>=deadline || report.policyVersion!=PlanAmendmentPolicy.VERSION ||
            report.asOf!=capture.at.toString() || report.basePlan!=capture.input.plan?.reference || report.selectionGeneration!=capture.input.selection.generation) throw PlanConflict()
        if(ScopedEvidencePreparation.json.encodeToString(report).toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
        report
    }

    @Synchronized override fun captureExtraWorkload(id: String,at: Instant): CapturedExtraWorkload = transaction {
        val dataVersion=dataVersionLocked()
        CapturedExtraWorkload(ExtraWorkloadRuleStore(connection).get(id),at,capturePlanReconciliationLocked(at),totalChangesLocked(),
            dataVersion,ScopedQueueStore(connection).revisions().source,evidenceCaptureOwner)
    }

    @Synchronized override fun finishExtraWorkload(capture: CapturedExtraWorkload,report: ExtraWorkloadReport): ExtraWorkloadReport = transaction {
        if(capture.ownerId!=evidenceCaptureOwner || capture.totalChanges!=totalChangesLocked() || capture.dataVersion!=dataVersionLocked() ||
            capture.sourceRevision!=ScopedQueueStore(connection).revisions().source || ExtraWorkloadRuleStore(connection).get(capture.rule.ruleId)!=capture.rule) throw PlanConflict()
        if(ScopedEvidencePreparation.json.encodeToString(report).toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
        report
    }

    @Synchronized override fun saveSignalRule(request: PersonalSignalRuleRequest,at: Instant): PersonalSignalRuleReceipt = transaction {
        PersonalSignalRuleStore(connection).save(request,at).also { if(!it.replayed) ScopedQueueStore(connection).planReasonChanged() }
    }

    @Synchronized override fun capturePersonalSignal(id: String,query: PersonalSignalQuery,at: Instant): CapturedPersonalSignal = transaction {
        capturePersonalSignalLocked(id,query,at)
    }

    private fun capturePersonalSignalLocked(id: String,query: PersonalSignalQuery,at: Instant,maximumRecords: Int=50_000): CapturedPersonalSignal {
        val dataVersion=dataVersionLocked()
        val rule=PersonalSignalRuleStore(connection).get(id)
        val config=rule.configuration
        val history=if(config!=null && rule.enabled) {
            require(LocalDate.parse(query.newest)<=at.atZone(java.time.ZoneId.of(config.timeZone)).toLocalDate())
            val first=LocalDate.parse(query.oldest).minusDays(config.baseline.trailingDays.toLong());require(first.year in 1..9999)
            ScopedHistoryReads.periodHistory(connection,listOf(ScopedDateRange(first.toString(),LocalDate.parse(query.oldest).minusDays(1).toString()),
                ScopedDateRange(query.oldest,query.newest)),includeWellness=true,includeActivities=false,maximumRecords=maximumRecords)
        } else HistoryResponse(emptyList(),emptyList())
        val samples=signalSamplesLocked(history,config?.selector?.field)
        val state=PersonalSignalStateStore(connection).get(id)
        return CapturedPersonalSignal(rule,query,at,samples,statuses(at.atZone(java.time.ZoneOffset.UTC).toLocalDate()).filter { it.category=="wellness" },
            ScopedQueueStore(connection).revisions().source,totalChangesLocked(),state.first,state.second,evidenceCaptureOwner,dataVersion,history.wellness.size)
    }

    private fun signalSamplesLocked(history: HistoryResponse,field: String?,provenance: List<ScopedImportProvenance> = ScopedHistoryReads.provenance(connection,history)): List<ScopedSignalSample> = signalEvidenceAdapter.adapt(
        ScopedWellnessEvidence.raw(history.wellness,provenance).filter { it.field==field }.map { observation ->
            val receipt=observation.provenance.singleOrNull()
            ScopedSignalSample(ScopedSignalIdentity(observation.field,observation.unit,observation.source,observation.upstreamSource,
                observation.method,observation.basis,observation.verification),observation.sourceDate,observation.value,
                observation.evidenceId,receipt?.receivedUtc,receipt?.revision)
        }).also { require(it.size<=50_000) }

    /** Internal staging seam; public preparation/admission must not combine separately captured reads. */
    @Synchronized internal fun captureAdaptationEvidence(at: Instant): CapturedAdaptationEvidence = transaction {
        captureAdaptationEvidenceLocked(at)
    }

    private fun captureAdaptationEvidenceLocked(at: Instant,includeSignals: Boolean=true): CapturedAdaptationEvidence {
        val dataVersion=dataVersionLocked()
        val reconciliation=capturePlanReconciliationLocked(at)
        val zone=java.time.ZoneId.of(reconciliation.plan?.definition?.policy?.adaptationTimeZone ?: reviewScheduleStateLocked().configuration.timeZone)
        val contexts=contextRows.latest().map { lifecycleStore.view(it,at.atZone(zone).toLocalDate()) }
        val signalRules=if(includeSignals) PersonalSignalRuleStore(connection).list() else emptyList()
        var sampleWork=reconciliation.records.size.toLong()
        val signals=signalRules.map { rule ->
            val ruleZone=java.time.ZoneId.of(rule.configuration?.timeZone ?: zone.id)
            val today=at.atZone(ruleZone).toLocalDate()
            // Do not invent dates before supported civil history at the lower boundary.
            val oldest=maxOf(LocalDate.of(1,1,1).plusDays((rule.configuration?.baseline?.trailingDays ?: 0).toLong()),today.minusDays(365))
            val query=PersonalSignalQuery(minOf(today,oldest).toString(),today.toString())
            capturePersonalSignalLocked(rule.ruleId,query,at,(50_000-sampleWork).toInt()).also { captured ->
                sampleWork+=captured.rawRecordCount;require(sampleWork<=50_000) { "Adaptation signal capture exceeds the shared record-work budget" }
            }
        }
        val reasons=AdaptationReasonStore(connection)
        val heads=reasons.heads()
        val (links,sourceHeads)=captureContextBindingsLocked()
        return CapturedAdaptationEvidence(at,reconciliation,contexts,commitmentStore.list(),links,sourceHeads,ExtraWorkloadRuleStore(connection).list(),signals,
            heads,reasons.admissions(heads.map { it.reasonId }.toSet()),SignalMutationStore(connection).removalGeneration(),
            ScopedQueueStore(connection).revisions().source,totalChangesLocked(),dataVersion,evidenceCaptureOwner,zone.id,
            ConstraintMutationStore(connection).frontier(),ConstraintMutationStore(connection).inventory(),
            ConstraintEventStore(connection).capture(reconciliation.plan,ConstraintMutationStore(connection).frontier()),reasons.supports())
    }

    @Synchronized internal fun captureSignalAdaptation(at: Instant): CapturedSignalAdaptation = transaction {
        captureSignalAdaptationLocked(at)
    }

    private fun captureSignalAdaptationLocked(at: Instant): CapturedSignalAdaptation {
        var root=captureAdaptationEvidenceLocked(at,includeSignals=false)
        var work=root.reconciliation.records.size
        val replay=PersonalSignalRuleStore(connection).list().map { rule ->
            captureSignalReplayLocked(rule,at,50_000-work).also { work+=it.signal.rawRecordCount }
        }
        root=root.copy(signals=replay.map { it.signal })
        return CapturedSignalAdaptation(root,replay,reviewScheduleStateLocked(),ScopedQueueStore(connection).revisions())
    }

    @Synchronized internal fun captureAdaptationResult(job: ReviewQueueJob?,at: Instant): CapturedAdaptationResult = transaction {
        captureAdaptationResultLocked(job,at)
    }

    @Synchronized internal fun captureBriefAdaptation(job: ReviewQueueJob,at: Instant): CapturedAdaptationResult? = transaction {
        if(!scopedJobCurrentLocked(job,reviewScheduleStateLocked())) throw ScopedEvidenceConflict()
        val plans=PlanStore(connection)
        val selected=plans.selection().selected
        if(selected==null || plans.get(selected.planId,selected.revision).definition.policy==null) null
        else captureAdaptationResultLocked(null,at).copy(authorizationJob=job)
    }

    private fun captureAdaptationResultLocked(job: ReviewQueueJob?,at: Instant): CapturedAdaptationResult {
        if(job!=null && !scopedJobCurrentLocked(job,reviewScheduleStateLocked())) throw ScopedEvidenceConflict()
        val capture=captureSignalAdaptationLocked(at)
        val plan=capture.root.reconciliation.plan ?: throw ScopedEvidenceConflict()
        val policy=plan.definition.policy ?: throw ScopedEvidenceConflict()
        job?.let {
            val payload=it.scopedIntent?.adaptation ?: throw ScopedEvidenceConflict()
            if(payload.workVersion==null || payload.family!=AdaptationFamily(plan.reference,policy.horizonDays,policy.adaptationTimeZone,plan.reference.planId) ||
                payload.selectionGeneration!=capture.root.reconciliation.selection.generation) throw ScopedEvidenceConflict()
        }
        val request=ScopedEvidenceRequest(selection=ScopedReviewRequest(scope="day",wellnessPolicy="assessment"),purpose="adaptation")
        return CapturedAdaptationResult(capture,captureScopedEvidenceLocked(request,at),request,job)
    }

    @Synchronized internal fun publishAdaptationResult(prepared: PreparedAdaptationResult,at: Instant): ScopedEvidenceSnapshot = transaction {
        val capture=prepared.capture
        val root=capture.adaptation.root
        checkAdaptationCaptureLocked(root)
        if(at<root.at || at>=prepared.admission.validUntil || capture.evidence.totalChanges!=totalChangesLocked()) throw ScopedEvidenceConflict()
        val state=reviewScheduleStateLocked()
        val job=capture.job
        if(capture.authorizationJob?.let { !scopedJobCurrentLocked(it,state) }==true) throw ScopedEvidenceConflict()
        val work=requireNotNull(prepared.admission.work)
        if(job!=null && job.scopedIntent?.adaptation?.workVersionSha256!=work.key()) throw ScopedEvidenceConflict()
        val results=AdaptationResultStore(connection)
        val existing=results.get(work.key())
        if(existing!=null && at>=existing.validUntil) throw ScopedEvidenceConflict()
        val snapshot=existing?.snapshot ?: ScopedEvidenceStore(connection).insert(prepared.evidence,at).also {
            if(it.state=="PREPARED") results.insert(work,it,prepared.admission.validUntil)
        }
        if(snapshot.state=="PREPARED") {
            val reasons=AdaptationReasonStore(connection)
            val covered=prepared.admission.heads.filter { it.condition=="active" && reasons.get(it.reasonId)==it }
            reasons.fulfill(covered)
        }
        if(job!=null) {
            val outcome=ReviewQueueOutcome(job.id,null,if(snapshot.state=="PREPARED") "factual_prepared" else "blocked",
                if(snapshot.state=="PREPARED") "adaptation_facts_only_plan_unchanged" else "evidence_preparation_blocked",at.toString(),job.startedUtc,
                scopedIntent=job.scopedIntent,snapshotId=snapshot.snapshotId,evidenceCutoffUtc=snapshot.manifest.scope.asOf)
            saveReviewStateLocked(state.copy(queue=ReviewQueueRules.finish(state.queue,outcome,state.configuration.queuePolicy,at)))
        }
        snapshot
    }

    private fun captureSignalReplayLocked(rule: PersonalSignalRule,at: Instant,maximumRecords: Int): CapturedSignalReplay {
        val stored=PersonalSignalStateStore(connection).replay(rule.ruleId)
        val config=rule.configuration
        val zone=java.time.ZoneId.of(config?.timeZone ?: "UTC")
        val today=at.atZone(zone).toLocalDate()
        val oldest=maxOf(LocalDate.of(1,1,1).plusDays((config?.baseline?.trailingDays ?: 0).toLong()),today.minusDays(365))
        val query=PersonalSignalQuery(minOf(oldest,today).toString(),today.toString())
        val evaluationStart=oldest.minusDays((config?.baseline?.trailingDays ?: 0).toLong())
        val old=stored.checkpoint
        val oldWatch=old?.takeIf { config!=null && rule.enabled && it.state.ruleRevision==rule.revision &&
            it.binding.newest<=today.toString() && java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it.binding.oldest),today)<=731 }
        val first=minOf(evaluationStart,oldWatch?.binding?.oldest?.let(LocalDate::parse) ?: evaluationStart)
        val ranges=buildList {
            var date=first
            while(date<=today) {
                val end=minOf(today,date.plusDays(365));add(ScopedDateRange(date.toString(),end.toString()));date=end.plusDays(1)
            }
        }
        val history=if(config!=null && rule.enabled) ScopedHistoryReads.periodHistory(connection,ranges,includeWellness=true,includeActivities=false,maximumRecords=maximumRecords)
            else HistoryResponse(emptyList(),emptyList())
        val provenance=ScopedHistoryReads.provenance(connection,history)
        val samples=signalSamplesLocked(history,config?.selector?.field,provenance)
        val state=PersonalSignalStateStore(connection).get(rule.ruleId)
        val signal=CapturedPersonalSignal(rule,query,at,samples.filter { it.date>=evaluationStart.toString() && it.date<=today.toString() },
            statuses(today).filter { it.category=="wellness" },ScopedQueueStore(connection).revisions().source,totalChangesLocked(),state.first,state.second,
            evidenceCaptureOwner,dataVersionLocked(),history.wellness.size)
        val mutations=SignalMutationStore(connection).capture(old?.mutationFrontier ?: 0,old?.removalGeneration ?: SignalMutationStore(connection).removalGeneration())
        val binding=oldWatch?.let { SignalCheckpointContinuity.bind(it.binding.selector,it.binding.oldest,it.binding.newest,samples,at) }
        return CapturedSignalReplay(signal,stored,samples,mutations,binding,first.toString(),history,provenance)
    }

    @Synchronized internal fun finishAdaptationProjection(prepared: PreparedAdaptationProjection): PreparedAdaptationProjection = transaction {
        val capture=prepared.captured
        checkAdaptationCaptureLocked(capture)
        prepared
    }

    private fun checkAdaptationCaptureLocked(capture: CapturedAdaptationEvidence) {
        if(capture.ownerId!=evidenceCaptureOwner || capture.totalChanges!=totalChangesLocked() || capture.dataVersion!=dataVersionLocked() ||
            capture.sourceRevision!=ScopedQueueStore(connection).revisions().source || capture.removalGeneration!=SignalMutationStore(connection).removalGeneration() ||
            capture.constraintFrontier!=ConstraintMutationStore(connection).frontier() ||
            capture.reconciliation.selection!=PlanStore(connection).selection()) throw PlanConflict()
    }

    /** Atomic owner handoff used by the armed factual scheduler. It never admits provider execution. */
    @Synchronized internal fun commitSignalAdaptation(prepared: PreparedSignalAdaptation,clock: java.time.Clock): SignalAdaptationAdmission = transaction {
        val capture=prepared.capture
        val root=capture.root
        checkAdaptationCaptureLocked(root)
        val now=clock.instant()
        if(now<root.at || now>=prepared.validUntil || prepared.validUntil!=PreparedSignalAdaptation.validUntil(root,prepared.signals)) throw PlanConflict()
        val schedule=reviewScheduleStateLocked()
        if(schedule!=capture.schedule || ScopedQueueStore(connection).revisions()!=capture.revisions) throw PlanConflict()
        require(schedule.configuration.enabled && !schedule.configuration.paused) { "Adaptation scheduling is not enabled" }
        val plan=requireNotNull(root.reconciliation.plan)
        val policy=requireNotNull(plan.definition.policy)
        val family=AdaptationFamily(plan.reference,policy.horizonDays,policy.adaptationTimeZone,plan.reference.planId)
        val states=PersonalSignalStateStore(connection)
        prepared.signals.forEach { signal ->
            val original=signal.capture.signal
            signal.capture.stored.highWater?.let { if(root.at<Instant.parse(it.admittedUtc) || now<Instant.parse(it.admittedUtc)) throw PlanConflict() }
            checkPersonalSignalCaptureLocked(original)
            if(states.replay(original.rule.ruleId)!=signal.capture.stored) throw PlanConflict()
            original.rule.configuration?.let { config -> if(now.atZone(java.time.ZoneId.of(config.timeZone)).toLocalDate().toString()!=original.query.newest) throw PlanConflict() }
            val mutations=SignalMutationStore(connection).capture(signal.capture.mutations.through,signal.capture.mutations.removalGeneration)
            if(!mutations.complete() || mutations.through!=signal.capture.mutations.through) throw PlanConflict()
        }
        fun sameFamily(job: ReviewQueueJob)=job.scopedIntent?.adaptation?.family==family
        val pending=schedule.queue.pending.singleOrNull(::sameFamily)
        val running=schedule.queue.running?.takeIf(::sameFamily)
        val reasons=AdaptationReasonStore(connection)
        val old=root.reasonHeads.associateBy { it.reasonId }
        prepared.heads.forEach { reasons.save(it,old[it.reasonId]?.revision ?: 0) }
        reasons.saveSupports(prepared.supports)
        val need=prepared.heads.filter { it.reasonId in prepared.candidates && AdaptationReasons.requiresAdmission(it,reasons.admissions(setOf(it.reasonId))) }
        val outstanding=reasons.unfulfilled(prepared.heads.filter { it.condition=="active" })
        var queue=schedule.queue
        val intent=prepared.intent
        if(intent==null) {
            if(pending!=null) queue=queue.copy(pending=queue.pending-pending,outcomes=(queue.outcomes+ReviewQueueOutcome(pending.id,null,"cancelled",
                "adaptation_reasons_retracted",now.toString(),pending.startedUtc,scopedIntent=pending.scopedIntent)).takeLast(24))
        } else if(need.isNotEmpty() || outstanding.isNotEmpty() || pending!=null && pending.scopedIntent?.adaptation?.workVersionSha256!=intent.adaptation?.workVersionSha256) {
            val covered=running?.scopedIntent?.adaptation?.workVersionSha256==intent.adaptation?.workVersionSha256 &&
                running?.scopedIntent?.adaptation?.selectionGeneration==intent.adaptation?.selectionGeneration
            if(!covered) queue=ReviewQueueRules.enqueueScoped(queue,listOf(ReviewQueueRules.ScopedQueueSubmission(intent,
                AnalysisClaims.hash("signal-admission:${requireNotNull(prepared.work).key()}"),intent.adaptation!!.reasonIds)),schedule.configuration.queuePolicy,now)
        }
        val job=(queue.pending+listOfNotNull(queue.running)).singleOrNull { sameFamily(it) &&
            it.scopedIntent?.adaptation?.workVersionSha256==intent?.adaptation?.workVersionSha256 &&
            it.scopedIntent?.adaptation?.selectionGeneration==root.reconciliation.selection.generation }
        if(need.isNotEmpty() && (job==null || !job.reasons.containsAll(need.map { it.reasonId }))) throw PlanConflict()
        val admitted=need.map { head -> reasons.admit(head,requireNotNull(job).id,now).also { if(it.jobId!=job.id) throw PlanConflict() }.reasonId }.toSet()
        if(queue!=schedule.queue) saveReviewStateLocked(schedule.copy(queue=queue))
        prepared.signals.forEach { signal ->
            val rule=signal.capture.signal.rule
            val previous=signal.capture.stored
            var checkpoint=signal.checkpoint
            var state=requireNotNull(signal.transition.state)
            var water=previous.highWater
            val sequence=(water?.episodeSequence ?: 0).let { if(it==Long.MAX_VALUE) it else it+1 }
            if(PreparedSignalAdaptation.reasonId(rule.ruleId,sequence) in admitted) {
                val config=requireNotNull(rule.configuration)
                state=PersonalSignalTransitions.acknowledge(signal.transition,now)
                checkpoint=requireNotNull(checkpoint).copy(state=state)
                val deadline=maxOf(now.plusSeconds(config.cooldownSeconds),water?.cooldownUntil(config) ?: now)
                water=SignalAdmissionHighWater(rule.ruleId,sequence,now.toString(),deadline.toString(),state.date,config.timeZone)
            }
            if(previous.checkpoint!=checkpoint || previous.highWater!=water || states.get(rule.ruleId).second!=state)
                states.saveReplay(rule.ruleId,previous.generation,state,checkpoint,water)
        }
        SignalAdaptationAdmission(admitted.sorted(),job?.id,prepared.signals.map { it.status })
    }

    private fun dataVersionLocked()=connection.createStatement().use { s -> s.executeQuery("PRAGMA data_version").use { r -> check(r.next());r.getLong(1) } }

    private fun checkPersonalSignalCaptureLocked(capture: CapturedPersonalSignal) {
        if(capture.ownerId!=evidenceCaptureOwner || capture.totalChanges!=totalChangesLocked() || capture.dataVersion!=dataVersionLocked() ||
            capture.sourceRevision!=ScopedQueueStore(connection).revisions().source ||
            PersonalSignalRuleStore(connection).get(capture.rule.ruleId)!=capture.rule ||
            PersonalSignalStateStore(connection).get(capture.rule.ruleId).first!=capture.stateGeneration) throw PlanConflict()
    }

    @Synchronized override fun finishPersonalSignal(capture: CapturedPersonalSignal,report: PersonalSignalReport): PersonalSignalReport = transaction {
        checkPersonalSignalCaptureLocked(capture)
        if(ScopedEvidencePreparation.json.encodeToString(report).toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
        report
    }

    /** Internal projection seam only. Task 7.4 must atomically admit reasons and acknowledge emissions. */
    internal fun commitPersonalSignalProjection(capture: CapturedPersonalSignal,clock: java.time.Clock): PersonalSignalState {
        // Derive from the capture outside owner locks; no caller-supplied episode can bypass verification.
        val report=PersonalSignalRules.evaluate(capture.rule,capture.query,capture.at,capture.samples)
        val transition=PersonalSignalTransitions.advance(report,capture.state,capture.sourceRevision,capture.at)
        return commitPersonalSignalProjectionLocked(capture,transition,clock)
    }

    @Synchronized private fun commitPersonalSignalProjectionLocked(capture: CapturedPersonalSignal,transition: PersonalSignalTransition,clock: java.time.Clock): PersonalSignalState = transaction {
        checkPersonalSignalCaptureLocked(capture)
        val config=requireNotNull(capture.rule.configuration)
        val now=clock.instant()
        if(now<capture.at || now.atZone(java.time.ZoneId.of(config.timeZone)).toLocalDate()!=capture.at.atZone(java.time.ZoneId.of(config.timeZone)).toLocalDate()) throw PlanConflict()
        require(transition.status !in setOf("needs_reevaluation","historical_inspection_only","disabled"))
        val state=requireNotNull(transition.state)
        require(state.ruleId==capture.rule.ruleId && state.ruleRevision==capture.rule.revision && state.sourceRevision==capture.sourceRevision &&
            state.date==capture.query.newest && state.projected.lastEmittedUtc==capture.state?.projected?.lastEmittedUtc)
        // Evaluation cannot acknowledge a fresh emission through this seam.
        require(!state.projected.emitted || capture.state?.projected?.let { it.emitted && it.episodeId==state.projected.episodeId }==true)
        PersonalSignalStateStore(connection).save(state.ruleId,capture.stateGeneration,state)
        state
    }

    @Synchronized override fun capturePlanReconciliation(at: Instant): CapturedPlanReconciliation = transaction {
        capturePlanReconciliationLocked(at)
    }

    /** Shared locked capture for inspection and upcoming coherent adaptation assembly; never nests transactions. */
    private fun capturePlanReconciliationLocked(at: Instant): CapturedPlanReconciliation {
        val plans=PlanStore(connection);val selection=plans.selection()
        val records=connection.createStatement().use { statement ->
            statement.executeQuery("SELECT record_json FROM activities ORDER BY source,source_id LIMIT 50001").use { rows -> buildList {
                while(rows.next()) { if(size==50_000) throw TrendSizeLimit();add(Json.decodeFromString<ActivityRecord>(rows.getString(1))) }
            } }
        }
        val receipts=ScopedHistoryReads.provenance(connection,HistoryResponse(records,emptyList())).associateBy { scopedActivityId(it.source,it.sourceRecordId) }
        val activities=records.map { record ->
            val id=scopedActivityId(record)
            val start=record.startInstant?.let(Instant::parse)
            val local=java.time.LocalDateTime.parse(record.startLocal)
            // Verify the normalized provenance, not merely a parsable arbitrary instant.
            if(start!=null) when(record.timeContext) {
                "explicit_offset" -> require(local.toInstant(java.time.ZoneOffset.of(requireNotNull(record.utcOffset)))==start)
                "named_zone" -> {
                    val zone=java.time.ZoneId.of(requireNotNull(record.timeZone));val offsets=zone.rules.getValidOffsets(local)
                    require(offsets.size==1 && local.toInstant(offsets.single())==start)
                }
                "upstream_instant" -> record.timeZone?.let { require(java.time.LocalDateTime.ofInstant(start,java.time.ZoneId.of(it))==local) }
            }
            ReconciliationActivity(id,receipts[id]?.revision,AnalysisClaims.hash(Json.encodeToString(record)),record.sport,
                record.startLocal,record.startInstant,record.timeContext)
        }.sortedBy { it.activityId }
        return CapturedPlanReconciliation(selection,selection.selected?.let { plans.get(it.planId,it.revision) },activities,
            selection.selected?.let { PlanReconciliationStore(connection).confirmations(it.planId) }.orEmpty(),
            statuses(at.atZone(java.time.ZoneOffset.UTC).toLocalDate()).filter { it.category=="activities" },totalChangesLocked(),
            records.associateBy(::scopedActivityId),ActivityEffortStore(connection).heads())
    }

    @Synchronized override fun reconciliationReceipt(request: PlanReconciliationConfirmationRequest)=PlanReconciliationStore(connection).receipt(request)

    @Synchronized override fun finishPlanReconciliation(capture: CapturedPlanReconciliation,view: PlanReconciliationView): PlanReconciliationView {
        if(totalChangesLocked()!=capture.totalChanges) throw PlanConflict()
        return view
    }

    @Synchronized override fun effortReceipt(request: ActivityEffortRequest)=ActivityEffortStore(connection).receipt(request)

    @Synchronized override fun effortHistory(activityId: String,scaleId: String): ActivityEffortHistory {
        val store=ActivityEffortStore(connection);val scale=store.scale(scaleId)
        require(activityId.matches(Regex("[a-f0-9]{64}")))
        // Indexed current-record lookup is optional: detached history remains accessible with no imported record.
        val record=connection.prepareStatement("SELECT a.record_json FROM activities a JOIN scoped_activity_identities i ON i.source=a.source AND i.source_id=a.source_id WHERE i.identity_sha256=?").use { s ->
            s.setString(1,activityId);s.executeQuery().use { r -> if(r.next()) Json.decodeFromString<ActivityRecord>(r.getString(1)) else null }
        }
        val identity=record?.let { r ->
            val receipt=ScopedHistoryReads.provenance(connection,HistoryResponse(listOf(r),emptyList())).single()
            ReconciliationActivity(activityId,receipt.revision,AnalysisClaims.hash(Json.encodeToString(r)),r.sport,r.startLocal,r.startInstant,r.timeContext)
        }
        val result=ActivityEffortHistory(activityId,scale,identity,store.history(activityId,scaleId))
        if(ScopedEvidencePreparation.json.encodeToString(result).toByteArray(Charsets.UTF_8).size>MAX_SCOPED_INSPECTION_BYTES) throw ScopedEvidenceInspectionLimit()
        return result
    }

    @Synchronized override fun saveEffortReport(request: ActivityEffortRequest,capture: CapturedPlanReconciliation?,clock: java.time.Clock): ActivityEffortReceipt = transaction {
        val store=ActivityEffortStore(connection)
        store.receipt(request)?.let { return@transaction it }
        if(request.action=="report" && (capture==null || totalChangesLocked()!=capture.totalChanges)) throw PlanConflict()
        val at=clock.instant()
        request.origin?.let { origin ->
            val captured=requireNotNull(capture)
            if(Instant.parse(origin.asOf)>at || captured.selection.generation!=origin.expectedSelectionGeneration || captured.selection.selected!=origin.plan) throw PlanConflict()
            // Time alone may introduce a competing automatic candidate after the inspected cutoff.
            val cutoff=Instant.parse(origin.asOf)
            if(captured.activities.any { it.verifiedStart()?.let { start -> start>cutoff && start<=at }==true }) throw PlanConflict()
        }
        store.save(request,capture,at).also { ScopedQueueStore(connection).planReasonChanged() }
    }

    @Synchronized override fun confirmPlanReconciliation(request: PlanReconciliationConfirmationRequest,capture: CapturedPlanReconciliation,
        activity: ReconciliationActivity?,clock: java.time.Clock): PlanReconciliationReceipt = transaction {
        val store=PlanReconciliationStore(connection)
        store.receipt(request)?.let { return@transaction it }
        if(totalChangesLocked()!=capture.totalChanges || PlanStore(connection).selection()!=capture.selection) throw PlanConflict()
        val at=clock.instant()
        validatePlanConfirmation(request,capture,activity,at)
        store.insert(request,activity,at).also { ScopedQueueStore(connection).planReasonChanged() }
    }

    @Synchronized override fun contextLifecycle(asOf: LocalDate) = ContextLifecycleReport(asOf.toString(),contextRows.latest().map { lifecycleStore.view(it,asOf) })
    @Synchronized override fun updateContextLifecycle(id: String, request: ContextLifecycleUpdate, asOf: LocalDate) = constraintTransaction {
        lifecycleStore.update(id,request,asOf).also { ScopedQueueStore(connection).planReasonChanged() }
    }

    private fun latestContextsLocked(): List<AthleteContext> = contextRows.latest()

    @Synchronized override fun contexts(today: LocalDate): List<AthleteContext> {
        return latestContextsLocked().filter { context -> !context.retired && LocalDate.parse(context.observedOn) <= today &&
            (context.applicableFrom == null || LocalDate.parse(context.applicableFrom) <= today) &&
            (context.applicableUntil == null || LocalDate.parse(context.applicableUntil) >= today) }
            .sortedWith(compareByDescending<AthleteContext> { it.observedOn }.thenBy { it.contextId })
    }

    @Synchronized override fun contextRevisions(id: String): List<AthleteContext> = contextRows.history(id)

    @Synchronized override fun retrieveContexts(query: ContextRetrievalQuery): ContextRetrievalResult =
        retrieveAthleteContext(latestContextsLocked(), query)

    private fun saveContext(id: String, revision: Int, request: AthleteContextRequest, retired: Boolean = false): AthleteContext {
        return contextRows.insert(id,revision,request,retired)
    }

    @Synchronized override fun createContext(request: AthleteContextRequest): AthleteContext = constraintTransaction {
        val count = connection.createStatement().use { it.executeQuery("SELECT count(DISTINCT context_id) FROM athlete_context").use { r -> r.next(); r.getInt(1) } }
        require(count < 1000) { "Context capacity reached" }
        staleConnectedReviewsLocked("context_changed")
        saveContext(java.util.UUID.randomUUID().toString(),1,request)
    }

    @Synchronized override fun correctContext(id: String, request: AthleteContextRequest): AthleteContext = constraintTransaction {
        validateContextId(id)
        intakeStore.requireUnlinkedContext(id)
        val current = contextRows.history(id).first()
        require(current.revision in 1..999)
        staleConnectedReviewsLocked("context_changed")
        saveContext(id, current.revision+1, request).also { lifecycleStore.carry(current,it,resetResolution=true) }
    }

    @Synchronized override fun retireContext(id: String): Boolean = constraintTransaction {
        validateContextId(id)
        val current = try { contextRows.history(id).first() } catch (_: ContextNotFound) { return@constraintTransaction false }
        if (current.retired) return@constraintTransaction false
        staleConnectedReviewsLocked("context_changed")
        contextRows.appendCopy(current,retired=true).also { lifecycleStore.carry(current,it) }
        true
    }

    @Synchronized override fun deleteContext(id: String): Boolean = constraintTransaction(deleting=true) {
        validateContextId(id)
        try { contextRows.current(id) } catch (_: ContextNotFound) { return@constraintTransaction false }
        val deletion=ContextDeletionStore(connection)
        val preview=deletion.preview(id,Instant.now())
        // Retain the direct endpoint only for an unshared closure. Every supported deletion
        // uses the same recursive purge/fence boundary; shared data needs explicit redaction.
        if(preview.sources.isNotEmpty() || preview.survivors.isNotEmpty() || preview.commitments.isNotEmpty()) throw ContextIntakeConflict()
        deletion.apply(id,ContextDeletionRequest(preview.expectedRevision,preview.digest,emptyList(),emptyList(),emptyList()),Instant.now())
        ScopedQueueStore(connection).planReasonChanged()
        true
    }

    @Synchronized override fun recordReviewFeedback(snapshotId: String, request: ReviewFeedbackRequest, observedOn: LocalDate): AthleteContext = constraintTransaction {
        validateContextId(snapshotId)
        val review = connectedReviewStatusLocked(snapshotId)
        require(review.state in setOf("PUBLISHED", "STALE") && review.publishedOutput != null) {
            "Feedback requires a stored connected-review output"
        }
        val feedback = buildList {
            request.rating?.let { add("rating=$it") }
            request.correction?.let { add("correction=$it") }
        }.joinToString("\n")
        val context = AthleteContextRequest(
            category = "feedback",
            sourceCategory = "review_feedback",
            authorAttribution = "athlete",
            observedOn = observedOn.toString(),
            reviewId = snapshotId,
            content = feedback,
        )
        saveContext(java.util.UUID.randomUUID().toString(), 1, context).also { ScopedQueueStore(connection).planReasonChanged() }
    }

    private fun categoryTable(category: String): String {
        require(category in setOf("activities", "wellness")) { "Invalid record category" }
        return category
    }

    @Synchronized
    fun statuses(today: LocalDate): List<CategoryStatus> = listOf("activities", "wellness").map { category ->
        val table = categoryTable(category)
        val (count, latest) = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*),max(observed_date) FROM $table").use { result ->
                result.next(); result.getInt(1) to result.getString(2)
            }
        }
        connection.prepareStatement("SELECT last_attempt_utc,last_success_utc,read_status,rejected,incomplete FROM sync_status WHERE category=?").use {
            it.setString(1, category)
            it.executeQuery().use { result ->
                val age = latest?.let { date -> java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(date), today) }
                if (result.next()) CategoryStatus(category, count, result.getString(1), result.getString(2),
                    result.getString(3), result.getInt(4), result.getInt(5), latest, age)
                else CategoryStatus(category, count, null, null, "NEVER_READ", 0, 0, latest, age)
            }
        }
    }

    @Synchronized
    fun history(oldest: LocalDate, newest: LocalDate, maximumRecords: Int? = null): HistoryResponse {
        require(oldest <= newest)
        require(maximumRecords == null || maximumRecords in 1..50_000)
        fun rows(category: String): List<String> = connection.prepareStatement("SELECT record_json FROM ${categoryTable(category)} WHERE observed_date BETWEEN ? AND ? ORDER BY observed_date,source,source_id" +
            (maximumRecords?.let { " LIMIT ${it + 1}" } ?: "")).use {
            it.setString(1, oldest.toString()); it.setString(2, newest.toString())
            it.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
        }
        val activities = rows("activities")
        val wellness = rows("wellness")
        if (maximumRecords != null && activities.size + wellness.size > maximumRecords) throw TrendSizeLimit()
        return HistoryResponse(activities.map { Json.decodeFromString<ActivityRecord>(it) },
            wellness.map { Json.decodeFromString<WellnessRecord>(it) })
    }

    @Synchronized
    internal fun scopedReviewOptions(asOf: Instant, oldest: String?, newest: String?, offset: Int): ScopedReviewOptions =
        ScopedHistoryReads.options(connection, reviewScheduleStateLocked(), asOf, oldest, newest, offset)

    internal fun scopedTrends(request: ScopedReviewRequest, asOf: Instant): ScopedTrendReport {
        val captured = captureScopedRead(request, asOf)
        return ScopedTrends.report(captured.scope, captured.history, captured.statuses)
    }

    @Synchronized
    internal fun captureScopedRead(request: ScopedReviewRequest, asOf: Instant): CapturedScopedRead = transaction {
        captureScopedReadLocked(request,asOf)
    }

    private fun captureScopedReadLocked(request: ScopedReviewRequest, asOf: Instant): CapturedScopedRead {
        val state = reviewScheduleStateLocked()
        require(ScopedHistoryReads.timezoneConfirmed(connection)) { "Save an explicit reporting timezone first" }
        val selected = if (request.mode == "activities") ScopedHistoryReads.selected(connection, request.activityIds) else emptyMap()
        val scope = ReviewScopeResolver.resolve(request, asOf, state.configuration.timeZone, selected)
        val records = if (scope.status != "available") HistoryResponse(emptyList(), emptyList()) else if (request.mode == "activities") {
            val today = asOf.atZone(java.time.ZoneId.of(state.configuration.timeZone)).toLocalDate().toString()
            HistoryResponse(request.activityIds.map { selected.getValue(it) },
                if (scope.wellnessPolicy == "off") emptyList() else ScopedHistoryReads.selectedWellness(connection,
                    scope.activityDates.filter { it <= today }, 50_000 - selected.size))
        } else ScopedHistoryReads.periodHistory(connection, listOfNotNull(scope.evidenceRange,
            scope.comparison.comparableSelected, scope.comparison.comparablePrevious,
            scope.comparison.priorCalendarFull, scope.comparison.comparisonFull), scope.wellnessPolicy != "off")
        return CapturedScopedRead(scope, records, statuses(asOf.atZone(java.time.ZoneId.of(state.configuration.timeZone)).toLocalDate()),
            ScopedHistoryReads.provenance(connection, records))
    }

    private fun totalChangesLocked(): Long = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT total_changes()").use { it.next();it.getLong(1) }
    }

    @Synchronized
    internal fun captureScopedEvidence(request: ScopedEvidenceRequest, at: Instant,adaptationKey: String?=null): CapturedScopedEvidence = transaction {
        captureScopedEvidenceLocked(request,at,adaptationKey)
    }

    private fun captureScopedEvidenceLocked(request: ScopedEvidenceRequest,at: Instant,adaptationKey: String?=null,presentAt: Instant=at): CapturedScopedEvidence {
        val configuredZone=java.time.ZoneId.of(reviewScheduleStateLocked().configuration.timeZone)
        require(request.baselines.all { java.time.LocalDate.parse(it.configuration.evaluationDate)<=at.atZone(configuredZone).toLocalDate() })
        val base=captureScopedReadLocked(request.selection,at)
        val today=at.atZone(java.time.ZoneId.of(base.scope.timeZone)).toLocalDate()
        val latestRange=if(request.purpose in setOf("morning","evening") && base.scope.wellnessPolicy=="assessment")
            listOf(ScopedDateRange(today.minusDays(minOf(365L,today.toEpochDay()-LocalDate.of(1,1,1).toEpochDay())).toString(),today.toString())) else emptyList()
        val read=if(request.baselines.isEmpty() && latestRange.isEmpty()) base else {
            val ranges=request.baselines.map { it.range() }+base.history.wellness.map { ScopedDateRange(it.date,it.date) }+latestRange
            val wellness=ScopedHistoryReads.periodHistory(connection,ranges,includeWellness=true,includeActivities=false,
                maximumRecords=50_000-base.history.activities.size).wellness
            val history=base.history.copy(wellness=wellness)
            base.copy(history=history,provenance=ScopedHistoryReads.provenance(connection,history))
        }
        val contexts=contextRows.latest().map { lifecycleStore.view(it,today) }
        val (links,sourceHeads)=captureContextBindingsLocked()
        val plans=PlanStore(connection)
        val selection=plans.selection()
        val reference=adaptationKey?.let { key ->
            val stored=AdaptationResultStore(connection).get(key) ?: throw ScopedEvidenceConflict()
            CapturedAdaptationReference(stored,captureSignalAdaptationLocked(presentAt),ScopedEvidenceStore(connection).membership(stored.snapshot.snapshotId))
        }
        return CapturedScopedEvidence(read,contexts,links,sourceHeads,commitmentStore.list(),selection,
            selection.selected?.let { plans.get(it.planId,it.revision) },totalChangesLocked(),reference?.stored?.snapshot?.packet?.adaptation,reference)
    }

    private fun captureContextBindingsLocked(): Pair<List<ContextIntakeLink>,Map<String,Int>> {
        val links=connection.createStatement().use { statement ->
            statement.executeQuery("SELECT l.intake_id,l.intake_revision,l.context_id,l.context_revision FROM context_intake_links l " +
                "JOIN (SELECT context_id,max(revision) revision FROM athlete_context GROUP BY context_id) c " +
                "ON l.context_id=c.context_id AND l.context_revision=c.revision ORDER BY l.context_id LIMIT 1001").use { rows ->
                buildList { while(rows.next()) { require(size<1000);add(ContextIntakeLink(rows.getString(1),rows.getInt(2),rows.getString(3),rows.getInt(4))) } }
            }
        }
        val sourceHeads=connection.createStatement().use { statement ->
            statement.executeQuery("SELECT intake_id,max(revision) FROM context_intakes GROUP BY intake_id ORDER BY intake_id LIMIT 1001").use { rows ->
                buildMap { while(rows.next()) { require(size<1000);put(rows.getString(1),rows.getInt(2)) } }
            }
        }
        return links.map { it.copy(sourceChanged=it.intakeRevision<sourceHeads.getValue(it.intakeId)) } to sourceHeads
    }

    private fun captureExecutionSourceLocked(source: ScopedExecutionSource,requestId: String,presentAt: Instant=constraintClock.instant()): CapturedExecutionSource {
        val snapshot=source.snapshotId?.let { ScopedEvidenceStore(connection).get(it) }
        val scoped=try { snapshot?.let { captureScopedEvidenceLocked(it.request,Instant.parse(it.snapshot.manifest.scope.asOf),it.snapshot.packet?.adaptation?.workKey,presentAt) } }
            catch(_: IllegalArgumentException) { null }
            catch(_: TrendSizeLimit) { null }
        val intake=source.intakeId?.let { ContextIntakeStore(connection).history(it).first() }
        return CapturedExecutionSource(source,scoped,snapshot,intake,ScopedExecutionStore(connection).generation(requestId),totalChangesLocked())
    }

    @Synchronized
    internal fun captureScopedExecutionSource(profile: String,source: ScopedExecutionSource): CapturedExecutionSource = transaction {
        require(profile in ScopedProfileAdmission.profiles)
        captureExecutionSourceLocked(source,scopedExecutionRequestId(profile,source.snapshotId ?: requireNotNull(source.intakeId)))
    }

    @Synchronized internal fun captureScopedEvidenceInspection(id: String,at: Instant): CapturedExecutionSource = transaction {
        captureExecutionSourceLocked(ScopedExecutionSource(snapshotId=id),scopedExecutionRequestId(ScopedDescriptiveContractV1.PROFILE,id),at)
    }

    @Synchronized
    internal fun saveScopedExecution(prepared: PreparedScopedExecution): StoredScopedExecution = transaction {
        checkExecutionAdaptationLocked(prepared.adaptationCapture)
        val binding=prepared.binding
        if(!scopedSourceTimeCurrent(binding,constraintClock.instant())) throw ScopedEvidenceConflict()
        if(totalChangesLocked()!=prepared.totalChanges) throw ScopedEvidenceConflict()
        val executions=ScopedExecutionStore(connection)
        require(binding.generation==executions.generation(binding.requestId)+1)
        executions.insert(prepared)
        executions.get(binding.executionId)
    }

    @Synchronized
    internal fun captureScopedExecution(id: String): CapturedExecutionCheck = transaction {
        val stored=ScopedExecutionStore(connection).get(id)
        CapturedExecutionCheck(stored,captureExecutionSourceLocked(stored.binding.source,stored.binding.requestId))
    }

    /** Caller calculates source binding outside the monitor; this token closes the read/publication race. */
    @Synchronized
    internal fun finishScopedExecutionCheck(capture: CapturedExecutionCheck,current: Boolean): StoredScopedExecution {
        checkExecutionAdaptationLocked(capture.source.scoped?.adaptationReference?.current)
        val stored=ScopedExecutionStore(connection).get(capture.stored.binding.executionId)
        if(totalChangesLocked()!=capture.source.totalChanges) throw ScopedEvidenceConflict()
        return if(current && scopedSourceTimeCurrent(stored.binding,constraintClock.instant())) stored else stored.copy(state="STALE",staleReason="source_changed",output=null)
    }

    @Synchronized
    internal fun claimPersonalReview(claim: PersonalReviewClaim,capture: CapturedExecutionCheck,current: Boolean,at: Instant): StoredScopedExecution? = transaction {
        checkExecutionAdaptationLocked(capture.source.scoped?.adaptationReference?.current)
        val store=ScopedExecutionStore(connection)
        store.get(capture.stored.binding.executionId)
        if(totalChangesLocked()!=capture.source.totalChanges) throw ScopedEvidenceConflict()
        if(!current || !scopedSourceTimeCurrent(capture.stored.binding,at) || !personalBindingMatches(claim,capture.stored.binding) || capture.stored.state!="IN_FLIGHT") return@transaction null
        if(!PersonalReviewRuntimeStore(connection).claimExecution(claim,at)) return@transaction null
        store.claim(capture.stored.binding.executionId,capture.stored.binding.generation) ?: throw PersonalRuntimeConflict()
    }

    @Synchronized
    internal fun publishPersonalReview(claim: PersonalReviewClaim,capture: CapturedExecutionCheck,current: Boolean,output: ValidatedConnectedReviewOutput,
        clock: java.time.Clock): StoredScopedExecution = transaction {
        checkExecutionAdaptationLocked(capture.source.scoped?.adaptationReference?.current)
        val store=ScopedExecutionStore(connection)
        store.get(capture.stored.binding.executionId)
        if(totalChangesLocked()!=capture.source.totalChanges) throw ScopedEvidenceConflict()
        val runtime=PersonalReviewRuntimeStore(connection)
        val supervision=OwnerUatSupervisionStore(connection)
        val supervised=if(claim.supervisionRequired) supervision.get(claim.claimId) ?: throw PersonalRuntimeConflict() else null
        fun publicationClock()=ownerUatContinuousClock?.sample() ?: throw PersonalRuntimeConflict()
        if(supervised!=null && !supervision.authorizePublication(claim,publicationClock())) throw PersonalRuntimeConflict()
        if(!current || !scopedSourceTimeCurrent(capture.stored.binding,clock.instant()) || capture.stored.state!="RUNNING" || !personalBindingMatches(claim,capture.stored.binding) || !runtime.consumePublication(claim,clock.instant())) throw PersonalRuntimeConflict()
        val published=store.publish(capture.stored.binding.executionId,output)
        if(supervised!=null) {
            if(!supervision.authorizePublication(claim,publicationClock())) throw PersonalRuntimeConflict()
            val finalState=requireNotNull(supervision.get(claim.claimId))
            supervision.tombstone(claim,"published")
            // Last check is inside the same transaction, after result/journal writes and before commit.
            if(!finalState.permits(publicationClock())) throw PersonalRuntimeConflict()
        }
        published
    }

    private fun personalBindingMatches(claim: PersonalReviewClaim,binding: ScopedExecutionBinding)=
        claim.executionId==binding.executionId && claim.executionGeneration==binding.generation && claim.profile==binding.profile &&
            claim.resource.selection==binding.providerSelection && claim.packetDigest==binding.consentSha256 && claim.dependencyDigest==binding.dependencyBindingSha256

    private fun scopedSourceTimeCurrent(binding: ScopedExecutionBinding,at: Instant)=binding.sourceValidUntilUtc?.let { at<Instant.parse(it) } ?: true

    private fun checkExecutionAdaptationLocked(capture: CapturedSignalAdaptation?) {
        if(capture!=null) try { checkAdaptationCaptureLocked(capture.root) } catch(_: PlanConflict) { throw ScopedEvidenceConflict() }
    }

    private fun personalSourceTimeCurrent(claim: PersonalReviewClaim,at: Instant)=try {
        scopedSourceTimeCurrent(ScopedExecutionStore(connection).get(claim.executionId).binding,at)
    } catch(_: ConnectedReviewNotFound) { false }

    @Synchronized internal fun personalOwnerEpoch(): String = PersonalReviewRuntimeStore(connection).epoch()
    @Synchronized internal fun personalRuntimeLease(id: String): PersonalRuntimeLease? = PersonalReviewRuntimeStore(connection).get(id)
    @Synchronized internal fun personalReviewForExecution(id: String): PersonalRuntimeLease? = PersonalReviewRuntimeStore(connection).forExecution(id)
    @Synchronized internal fun unresolvedPersonalReviews(): List<PersonalRuntimeLease> = PersonalReviewRuntimeStore(connection).unresolved()
    @Synchronized internal fun configureOwnerUatClock(clock: OwnerUatContinuousClock) {
        check(ownerUatContinuousClock==null || ownerUatContinuousClock===clock)
        ownerUatContinuousClock=clock
    }
    @Synchronized internal fun supervisedOwnerUatJob(claimId: String): OwnerUatSupervisedJob? = OwnerUatSupervisionStore(connection).get(claimId)

    @Synchronized internal fun reservePersonalReview(capture: CapturedExecutionCheck,current: Boolean,epoch: String,
        observation: PersonalResourceObservation,resource: PersonalResourceIdentity,clock: java.time.Clock,totalTimeoutMillis: Long,
        policy: ConnectedReviewExecutionPolicy=ConnectedReviewExecutionPolicy.PERSONAL,
        supervision: OwnerUatJobRegistration?=null): PersonalReviewClaim? = transaction {
        checkExecutionAdaptationLocked(capture.source.scoped?.adaptationReference?.current)
        val at=clock.instant()
        require(policy in setOf(ConnectedReviewExecutionPolicy.PERSONAL,ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT))
        require(totalTimeoutMillis in 1..if(policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT) 1_200_000 else 600_000)
        if(totalChangesLocked()!=capture.source.totalChanges) throw ScopedEvidenceConflict()
        val binding=capture.stored.binding
        if(!current || !scopedSourceTimeCurrent(binding,at) || capture.stored.state!="IN_FLIGHT" || binding.providerSelection!=resource.selection ||
            !observation.current(resource,at)) return@transaction null
        require(supervision==null || policy==ConnectedReviewExecutionPolicy.OWNER_LOCAL_UAT)
        val claim=PersonalReviewRuntimeStore(connection).reserve(epoch,binding,resource,at,at.plusMillis(totalTimeoutMillis),supervision!=null)
        if(claim!=null && supervision!=null) OwnerUatSupervisionStore(connection).register(claim,supervision,
            ownerUatContinuousClock?.sample() ?: throw PersonalRuntimeConflict())
        claim
    }

    @Synchronized internal fun beginPersonalStart(claim: PersonalReviewClaim,observation: PersonalResourceObservation,clock: java.time.Clock): Boolean = transaction {
        val at=clock.instant()
        personalSourceTimeCurrent(claim,at) && observation.current(claim.resource,at) && PersonalReviewRuntimeStore(connection).beginStart(claim,at)
    }
    @Synchronized internal fun authorizePersonalAttempt(claim: PersonalReviewClaim,number: Int,
        observation: PersonalResourceObservation,clock: java.time.Clock): Boolean = transaction {
        val at=clock.instant()
        personalSourceTimeCurrent(claim,at) && observation.current(claim.resource,at) && PersonalReviewRuntimeStore(connection).authorizeAttempt(claim,number,at)
    }
    /** Synchronous revocation is independent of the provider coroutine. Never call control.stop under this monitor. */
    @Synchronized internal fun revokePersonalReview(claim: PersonalReviewClaim,reason: String="cancelled",ownerOutcome: String="cancelled"): PersonalRuntimeLease? = transaction {
        if(claim.supervisionRequired) OwnerUatSupervisionStore(connection).tombstone(claim,ownerOutcome)
        val revoked=PersonalReviewRuntimeStore(connection).revoke(claim)
        if(revoked!=null) try { ScopedExecutionStore(connection).fail(claim.executionId,reason) } catch(_: ConnectedReviewNotFound) { }
        revoked
    }
    @Synchronized internal fun confirmPersonalStop(epoch: String,claim: PersonalReviewClaim,evidence: PersonalStopEvidence,at: Instant): Boolean = transaction {
        val journal=OwnerUatSupervisionStore(connection)
        val supervised=if(claim.supervisionRequired) journal.get(claim.claimId) ?: return@transaction false else null
        if(supervised!=null && (supervised.claim!=claim || !supervised.tombstoned)) return@transaction false
        val confirmed=PersonalReviewRuntimeStore(connection).confirmStop(epoch,claim,evidence,at)
        if(confirmed && supervised!=null) check(journal.save(supervised,supervised.copy(phase="STOP_CONFIRMED",
            stopObservation=runCatching { ownerUatContinuousClock?.sample() }.getOrNull())))
        confirmed
    }
    @Synchronized internal fun markPersonalStopUnknown(epoch: String,claim: PersonalReviewClaim): Boolean = transaction {
        PersonalReviewRuntimeStore(connection).unknown(epoch,claim)
    }

    @Synchronized
    internal fun failScopedExecution(id: String,reason: String): StoredScopedExecution = transaction {
        PersonalReviewRuntimeStore(connection).revokeExecution(id)
        ScopedExecutionStore(connection).fail(id,reason)
    }

    @Synchronized
    internal fun discardScopedExecution(id: String): Boolean = transaction {
        ensureScopedExecutionDiscardableLocked(id)
        PersonalReviewRuntimeStore(connection).revokeExecution(id)
        ScopedExecutionStore(connection).delete(id)
    }

    @Synchronized internal fun scopedExecutionHistory(profile: String,snapshotId: String?,offset: Int,limit: Int)=
        ScopedExecutionStore(connection).history(profile,snapshotId,offset,limit)

    @Synchronized internal fun ensureScopedExecutionDiscardable(id: String)=ensureScopedExecutionDiscardableLocked(id)

    private fun ensureScopedExecutionDiscardableLocked(id: String) {
        validateContextId(id)
        // Every historical/retired revision is a dependency, not only its latest presentation.
        val linked=connection.prepareStatement("SELECT 1 FROM athlete_context WHERE category='feedback' AND review_id=? LIMIT 1").use {
            it.setString(1,id);it.executeQuery().use { r -> r.next() }
        }
        if(linked) throw ScopedExecutionFeedbackConflict()
    }

    @Synchronized internal fun recordScopedExecutionFeedback(id: String,input: ScopedExecutionFeedbackRequest,observedOn: LocalDate): AthleteContext = constraintTransaction {
        val stored=ScopedExecutionStore(connection).get(id)
        require(stored.binding.profile==ScopedDescriptiveContractV1.PROFILE && stored.state in setOf("PUBLISHED","STALE")) {
            "Feedback requires a stored descriptive result"
        }
        val output=requireNotNull(stored.output) { "Feedback requires a stored descriptive result" }
        require(AnalysisClaims.hash(output)==input.expectedOutputSha256) { "Feedback result changed" }
        require(restoredScopedInvocation(stored.binding).validate(output) is ConnectedReviewValidation.Accepted) {
            "Feedback result could not be verified"
        }
        val count=connection.createStatement().use { it.executeQuery("SELECT count(DISTINCT context_id) FROM athlete_context").use { r -> r.next();r.getInt(1) } }
        require(count<1000) { "Context capacity reached" }
        val content=listOfNotNull(input.feedback.rating?.let { "rating=$it" },input.feedback.correction?.let { "correction=$it" }).joinToString("\n")
        saveContext(java.util.UUID.randomUUID().toString(),1,AthleteContextRequest(category="feedback",sourceCategory="review_feedback",
            authorAttribution="athlete",observedOn=observedOn.toString(),reviewId=id,content=content))
            .also { ScopedQueueStore(connection).planReasonChanged() }
    }

    @Synchronized
    internal fun savedIntakeSuggestionExecutionId(id: String,expectedRevision: Int): String? = transaction {
        validateContextId(id);require(expectedRevision in 1..1000)
        if(ContextIntakeStore(connection).history(id).first().revision!=expectedRevision) throw ContextIntakeConflict()
        connection.prepareStatement("SELECT b.execution_id FROM scoped_execution_bindings b JOIN scoped_execution_state s USING(execution_id) JOIN connected_review_requests r ON r.request_id=b.request_id AND r.active_generation=b.generation WHERE b.intake_id=? AND b.intake_revision=? AND b.profile='context-intake-suggestion-v1' AND s.state='PUBLISHED'").use { statement ->
            statement.setString(1,id);statement.setInt(2,expectedRevision)
            statement.executeQuery().use { rows -> if(rows.next()) rows.getString(1) else null }
        }
    }

    @Synchronized
    internal fun saveScopedEvidence(prepared: PreparedScopedEvidence, at: Instant): ScopedEvidenceSnapshot = transaction {
        if(prepared.captured.adaptationReference?.let { at>=it.stored.validUntil }==true) throw ScopedEvidenceConflict()
        // An intervening write (including newly applicable data or a deleted dependency) cannot publish this capture.
        // False conflicts from unrelated writes are safe; this transient fence is not a permanent staleness policy.
        if(totalChangesLocked()!=prepared.captured.totalChanges) throw ScopedEvidenceConflict()
        ScopedEvidenceStore(connection).insert(prepared,at)
    }

    @Synchronized
    internal fun storedScopedEvidence(id: String): StoredScopedEvidence = ScopedEvidenceStore(connection).get(id).copy(totalChanges=totalChangesLocked())

    @Synchronized
    internal fun finishScopedEvidenceInspection(id: String,expectedChanges: Long,current: Boolean,validUntil: Instant?=null,
        adaptationCapture: CapturedSignalAdaptation?=null): ScopedEvidenceSnapshot {
        checkExecutionAdaptationLocked(adaptationCapture)
        val stored=ScopedEvidenceStore(connection).get(id) // Purge wins over any cached private payload.
        if(totalChangesLocked()!=expectedChanges) throw ScopedEvidenceConflict()
        return stored.snapshot.copy(state=if(current && (validUntil==null || constraintClock.instant()<validUntil)) stored.snapshot.state else "STALE")
    }

    @Synchronized
    internal fun scopedEvidenceRecords(id: String,offset: Int,limit: Int): ScopedEvidenceRecords = transaction {
        ScopedEvidenceStore(connection).records(id,offset,limit)
    }

    @Synchronized
    fun removeImports() {
        transaction {
            val state = reviewScheduleStateLocked()
            staleConnectedReviewsLocked("evidence_removed")
            ImportedEvidenceRemovalStore(connection).purgeDerivedCopies()
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM activities")
                it.executeUpdate("DELETE FROM wellness")
                it.executeUpdate("DELETE FROM sync_status")
                it.executeUpdate("DELETE FROM review_import_changes")
            }
            saveReviewStateLocked(ReviewSchedulerState(configuration = state.configuration, version = Math.addExact(state.version, 1),purposeHighWater=state.purposeHighWater))
        }
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        // Events are a separate app-owned table and are deliberately untouched.
        // This is data removal, not a claim of forensic SD-card/backup erasure.
    }

    @Synchronized
    override fun close() { connection.close() }
}
