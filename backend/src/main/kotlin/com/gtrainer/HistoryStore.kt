package com.gtrainer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

class HistoryStore(path: Path) : AutoCloseable, ManualEventRepository, AthleteContextRepository, ConnectedReviewExecutionRepository {
    fun athleteContexts() = AthleteContextService(this)
    private val connection: Connection
    private val scheduleJson = Json { encodeDefaults = true }

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
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=FULL")
                statement.execute("PRAGMA secure_delete=ON")
                val version = statement.executeQuery("PRAGMA user_version").use { result -> result.next(); result.getInt(1) }
                require(version in 0..7) { "Database schema is not compatible with this app" }
                if (version == 0) transaction {
                    statement.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_utc TEXT NOT NULL)")
                    statement.execute("CREATE TABLE activities (source TEXT NOT NULL, source_id TEXT NOT NULL, observed_date TEXT NOT NULL, record_json TEXT NOT NULL, PRIMARY KEY(source,source_id))")
                    statement.execute("CREATE TABLE wellness (source TEXT NOT NULL, source_id TEXT NOT NULL, observed_date TEXT NOT NULL, record_json TEXT NOT NULL, PRIMARY KEY(source,source_id))")
                    statement.execute("CREATE INDEX activity_dates ON activities(observed_date)")
                    statement.execute("CREATE INDEX wellness_dates ON wellness(observed_date)")
                    statement.execute("CREATE TABLE events (id TEXT PRIMARY KEY, start_date TEXT NOT NULL, end_date TEXT NOT NULL, sport TEXT NOT NULL, goal TEXT NOT NULL, notes TEXT)")
                    statement.execute("CREATE TABLE sync_status (category TEXT PRIMARY KEY, last_attempt_utc TEXT, last_success_utc TEXT, read_status TEXT NOT NULL, rejected INTEGER NOT NULL DEFAULT 0, incomplete INTEGER NOT NULL DEFAULT 0)")
                    connection.prepareStatement("INSERT INTO schema_migrations VALUES (1,?)").use {
                        it.setString(1, Instant.now().toString()); it.executeUpdate()
                    }
                    statement.execute("PRAGMA user_version=1")
                }
                if (version < 2) transaction {
                    statement.execute("CREATE TABLE review_schedule (singleton INTEGER PRIMARY KEY CHECK(singleton=1), state_json TEXT NOT NULL)")
                    statement.execute("CREATE TABLE review_import_changes (revision INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, observed_date TEXT NOT NULL, received_utc TEXT NOT NULL, identity_sha256 TEXT NOT NULL, sport TEXT, sleep_changed INTEGER NOT NULL, sleep_available INTEGER NOT NULL)")
                    saveInitialReviewStateLocked()
                    connection.prepareStatement("INSERT INTO schema_migrations VALUES (2,?)").use {
                        it.setString(1, Instant.now().toString()); it.executeUpdate()
                    }
                    statement.execute("PRAGMA user_version=2")
                }
                if (version < 3) transaction {
                    statement.execute("CREATE TABLE athlete_context (context_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0), category TEXT NOT NULL, author_attribution TEXT NOT NULL, entered_by TEXT NOT NULL, observed_on TEXT NOT NULL, applicable_from TEXT, applicable_until TEXT, sport TEXT, activity_id TEXT, review_id TEXT, content TEXT NOT NULL, PRIMARY KEY(context_id,revision), CHECK(applicable_until IS NULL OR applicable_from IS NULL OR applicable_until >= applicable_from))")
                    statement.execute("CREATE INDEX athlete_context_observed ON athlete_context(observed_on)")
                    statement.execute("CREATE INDEX athlete_context_applicability ON athlete_context(applicable_from,applicable_until,sport,activity_id)")
                    connection.prepareStatement("INSERT INTO schema_migrations VALUES (3,?)").use {
                        it.setString(1, Instant.now().toString()); it.executeUpdate()
                    }
                    statement.execute("PRAGMA user_version=3")
                }
                if (version < 4) transaction {
                    statement.execute("ALTER TABLE athlete_context ADD COLUMN retired INTEGER NOT NULL DEFAULT 0 CHECK(retired IN (0,1))")
                    connection.prepareStatement("INSERT INTO schema_migrations VALUES (4,?)").use {
                        it.setString(1, Instant.now().toString()); it.executeUpdate()
                    }
                    statement.execute("PRAGMA user_version=4")
                }
                if (version < 5) transaction {
                    statement.execute("ALTER TABLE athlete_context ADD COLUMN source_category TEXT NOT NULL DEFAULT 'user_report' CHECK(source_category IN ('user_report','clinician_guidance','coach_guidance','review_feedback'))")
                    connection.prepareStatement("INSERT INTO schema_migrations VALUES (5,?)").use {
                        it.setString(1, Instant.now().toString()); it.executeUpdate()
                    }
                    statement.execute("PRAGMA user_version=5")
                }
                if (version < 6) transaction {
                    statement.execute("ALTER TABLE athlete_context ADD COLUMN restriction_kind TEXT")
                    statement.execute("ALTER TABLE athlete_context ADD COLUMN restriction_value TEXT")
                    statement.execute("ALTER TABLE athlete_context ADD COLUMN restriction_unit TEXT")
                    connection.prepareStatement("INSERT INTO schema_migrations VALUES (6,?)").use { it.setString(1, Instant.now().toString()); it.executeUpdate() }
                    statement.execute("PRAGMA user_version=6")
                }
                if (version < 7) transaction {
                    statement.execute("CREATE TABLE connected_review_snapshots (snapshot_id TEXT PRIMARY KEY, request_id TEXT NOT NULL, generation INTEGER NOT NULL, packet_json TEXT NOT NULL, packet_sha256 TEXT NOT NULL, evidence_digest TEXT NOT NULL, refs_json TEXT NOT NULL, coverage_from TEXT NOT NULL, coverage_until TEXT NOT NULL, sport TEXT, provider TEXT NOT NULL, model TEXT NOT NULL, contract_version TEXT NOT NULL, created_utc TEXT NOT NULL, UNIQUE(request_id,generation))")
                    statement.execute("CREATE TABLE connected_review_snapshot_state (snapshot_id TEXT PRIMARY KEY REFERENCES connected_review_snapshots(snapshot_id) ON DELETE CASCADE, state TEXT NOT NULL, stale_reason TEXT, published_output TEXT)")
                    statement.execute("CREATE TABLE connected_review_requests (request_id TEXT PRIMARY KEY, active_generation INTEGER NOT NULL)")
                    statement.execute("CREATE TABLE connected_review_context_refs (snapshot_id TEXT NOT NULL REFERENCES connected_review_snapshots(snapshot_id) ON DELETE CASCADE, context_id TEXT NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(snapshot_id,context_id,revision))")
                    statement.execute("CREATE INDEX connected_review_context_lookup ON connected_review_context_refs(context_id)")
                    statement.execute("CREATE TRIGGER connected_review_snapshot_immutable BEFORE UPDATE ON connected_review_snapshots BEGIN SELECT RAISE(ABORT,'connected review snapshots are immutable'); END")
                    connection.prepareStatement("INSERT INTO schema_migrations VALUES (7,?)").use { it.setString(1, Instant.now().toString()); it.executeUpdate() }
                    statement.execute("PRAGMA user_version=7")
                }
                reviewScheduleStateLocked() // Missing or corrupt scheduler state must fail closed.
                statement.executeUpdate("UPDATE sync_status SET read_status='INTERRUPTED' WHERE read_status='RUNNING'")
            }
            // JDBC creates the file; restrict it explicitly instead of relying
            // on the container process umask. The containing PVC remains private.
            runCatching { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        } catch (_: Exception) {
            connection.close()
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
    fun begin(category: String, at: Instant) {
        categoryTable(category)
        connection.prepareStatement("INSERT INTO sync_status(category,last_attempt_utc,read_status) VALUES (?,?,'RUNNING') ON CONFLICT(category) DO UPDATE SET last_attempt_utc=excluded.last_attempt_utc,read_status='RUNNING',rejected=0,incomplete=0").use {
            it.setString(1, category); it.setString(2, at.toString()); it.executeUpdate()
        }
    }

    @Synchronized
    fun interrupted(category: String) {
        categoryTable(category)
        connection.prepareStatement("UPDATE sync_status SET read_status='INTERRUPTED' WHERE category=? AND read_status='RUNNING'").use {
            it.setString(1, category); it.executeUpdate()
        }
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
                            val current = Json.decodeFromString<WellnessRecord>(json)
                            val old = previous?.let { Json.decodeFromString<WellnessRecord>(it) }
                            changed = current != old
                            sport = null
                            sleepChanged = listOf("sleepSecs", "sleepScore").any { key ->
                                current.measurements[key]?.let { it != old?.measurements?.get(key) } == true
                            }
                            sleepAvailable = listOf("sleepSecs", "sleepScore").any { it in current.measurements }
                        }
                        if (!changed) continue
                        insert.setString(1, identity.first); insert.setString(2, identity.second)
                        insert.setString(3, identity.third); insert.setString(4, json); insert.executeUpdate()
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
    }

    private fun appendReviewChangeLocked(category: String, identity: Triple<String, String, String>, at: Instant,
                                          sport: String?, sleepChanged: Boolean, sleepAvailable: Boolean) {
        // JSON array encoding is unambiguous even when opaque IDs contain delimiters.
        val tuple = Json.encodeToString(listOf(identity.first, identity.second))
        val digest = MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
        connection.prepareStatement("UPDATE connected_review_snapshot_state SET state='STALE',stale_reason=? WHERE state IN ('IN_FLIGHT','RUNNING','PUBLISHED')").use {
            it.setString(1, reason); it.executeUpdate()
        }
    }

    /** Persist the exact inert packet; this method never invokes an inference provider. */
    @Synchronized
    fun createConnectedReviewSnapshot(binding: ConnectedReviewBinding, at: Instant): ConnectedReviewStatus = transaction {
        require(binding.requestId.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        require(binding.contractVersion == InterpretationContractV1.PROFILE)
        require(binding.providerSelection.storageId.length <= 71)
        val from = LocalDate.parse(binding.coverageFrom); val until = LocalDate.parse(binding.coverageUntil); require(from <= until)
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

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }


    private fun reviewScheduleStateLocked(): ReviewSchedulerState = try {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT singleton,state_json FROM review_schedule").use {
                check(it.next() && it.getInt(1) == 1)
                val state = scheduleJson.decodeFromString<ReviewSchedulerState>(it.getString(2))
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
            queue = ReviewQueueRules.invalidate(previous.queue, "review_configuration_changed", at))
        saveReviewStateLocked(state)
        state
    }

    @Synchronized
    fun evaluateReviewSchedule(evaluate: (ReviewSchedulerState, List<ImportedReviewChange>, Long,
                                         (LocalDate, LocalDate) -> HistoryResponse) -> Pair<ReviewSchedulerState, ReviewScheduleStatus>): ReviewScheduleStatus = transaction {
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
        saveReviewStateLocked(updated)
        status
    }

    @Synchronized
    internal fun <T> updateReviewQueue(operation: (ReviewSchedulerState) -> Pair<ReviewSchedulerState, T>): T = transaction {
        val (state, result) = operation(reviewScheduleStateLocked())
        saveReviewStateLocked(state)
        result
    }

    @Synchronized
    internal fun reviewJobReport(job: ReviewQueueJob, today: LocalDate, latestAllowedDate: LocalDate = today): TrendReport {
        val range = TrendRange(LocalDate.parse(job.intent.oldest), LocalDate.parse(job.intent.newest))
        val records = history(range.previous().oldest, range.newest, 50_000)
        val selected = if (job.intent.scope.kind != "activity") records else records.copy(activities = records.activities.filter { activity ->
            AnalysisClaims.hash(Json.encodeToString(listOf(activity.source, activity.sourceRecordId))) == job.intent.activitySha256
        })
        return Trends.report(selected, range, job.intent.scope.sport, today, statuses(today), latestAllowedDate)
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

    private fun context(rows: java.sql.ResultSet) = AthleteContext(rows.getString(1), rows.getInt(2), rows.getString(3),
        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9),
        rows.getString(10), rows.getString(11), rows.getString(12), rows.getString(13), rows.getInt(14) != 0,
        rows.getString(15), rows.getString(16), rows.getString(17))

    private fun latestContextsLocked(): List<AthleteContext> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT c.context_id,c.revision,c.category,c.source_category,c.author_attribution,c.entered_by,c.observed_on,c.applicable_from,c.applicable_until,c.sport,c.activity_id,c.review_id,c.content,c.retired,c.restriction_kind,c.restriction_value,c.restriction_unit FROM athlete_context c JOIN (SELECT context_id,max(revision) revision FROM athlete_context GROUP BY context_id) latest ON c.context_id=latest.context_id AND c.revision=latest.revision ORDER BY c.context_id LIMIT 1001").use { rows -> buildList {
            while (rows.next()) { require(size < 1000) { "Context list exceeds bound" }; add(context(rows)) }
        } }
    }

    @Synchronized override fun contexts(today: LocalDate): List<AthleteContext> {
        return latestContextsLocked().filter { context -> !context.retired && LocalDate.parse(context.observedOn) <= today &&
            (context.applicableFrom == null || LocalDate.parse(context.applicableFrom) <= today) &&
            (context.applicableUntil == null || LocalDate.parse(context.applicableUntil) >= today) }
            .sortedWith(compareByDescending<AthleteContext> { it.observedOn }.thenBy { it.contextId })
    }

    @Synchronized override fun contextRevisions(id: String): List<AthleteContext> {
        validateContextId(id)
        return connection.prepareStatement("SELECT context_id,revision,category,source_category,author_attribution,entered_by,observed_on,applicable_from,applicable_until,sport,activity_id,review_id,content,retired,restriction_kind,restriction_value,restriction_unit FROM athlete_context WHERE context_id=? ORDER BY revision DESC LIMIT 1001").use {
            it.setString(1, id); it.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    require(size < 1000) { "Context history exceeds bound" }
                    add(context(rows))
                }
            } }
        }.also { if (it.isEmpty()) throw ContextNotFound() }
    }

    @Synchronized override fun retrieveContexts(query: ContextRetrievalQuery): ContextRetrievalResult =
        retrieveAthleteContext(latestContextsLocked(), query)

    private fun saveContext(id: String, revision: Int, request: AthleteContextRequest, retired: Boolean = false): AthleteContext {
        connection.prepareStatement("INSERT INTO athlete_context(context_id,revision,category,author_attribution,entered_by,observed_on,applicable_from,applicable_until,sport,activity_id,review_id,content,retired,source_category,restriction_kind,restriction_value,restriction_unit) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
            it.setString(1,id); it.setInt(2,revision); it.setString(3,request.category); it.setString(4,request.authorAttribution)
            it.setString(5,"authenticated_user"); it.setString(6,request.observedOn); it.setString(7,request.applicableFrom)
            it.setString(8,request.applicableUntil); it.setString(9,request.sport); it.setString(10,request.activityId)
            it.setString(11,request.reviewId); it.setString(12,request.content); it.setInt(13,if(retired) 1 else 0)
            it.setString(14,request.sourceCategory); it.setString(15,request.restrictionKind); it.setString(16,request.restrictionValue); it.setString(17,request.restrictionUnit); it.executeUpdate()
        }
        return AthleteContext(id,revision,request.category,request.sourceCategory,request.authorAttribution,"authenticated_user",request.observedOn,
            request.applicableFrom,request.applicableUntil,request.sport,request.activityId,request.reviewId,request.content,retired,request.restrictionKind,request.restrictionValue,request.restrictionUnit)
    }

    @Synchronized override fun createContext(request: AthleteContextRequest): AthleteContext = transaction {
        val count = connection.createStatement().use { it.executeQuery("SELECT count(DISTINCT context_id) FROM athlete_context").use { r -> r.next(); r.getInt(1) } }
        require(count < 1000) { "Context capacity reached" }
        staleConnectedReviewsLocked("context_changed")
        saveContext(java.util.UUID.randomUUID().toString(),1,request)
    }

    @Synchronized override fun correctContext(id: String, request: AthleteContextRequest): AthleteContext = transaction {
        validateContextId(id)
        val current = connection.prepareStatement("SELECT max(revision) FROM athlete_context WHERE context_id=?").use {
            it.setString(1,id); it.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
        if (current == 0) throw ContextNotFound()
        staleConnectedReviewsLocked("context_changed")
        saveContext(id, Math.addExact(current,1), request)
    }

    @Synchronized override fun retireContext(id: String): Boolean = transaction {
        validateContextId(id)
        val row = connection.prepareStatement("SELECT revision,category,source_category,author_attribution,observed_on,applicable_from,applicable_until,sport,activity_id,review_id,content,retired,restriction_kind,restriction_value,restriction_unit FROM athlete_context WHERE context_id=? ORDER BY revision DESC LIMIT 1").use {
            it.setString(1,id); it.executeQuery().use { r -> if (!r.next()) null else listOf(r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getInt(12),r.getString(13),r.getString(14),r.getString(15)) }
        } ?: return@transaction false
        if (row[11] as Int == 1) return@transaction false
        staleConnectedReviewsLocked("context_changed")
        saveContext(id,(row[0] as Int)+1,AthleteContextRequest(row[1] as String,row[2] as String,row[3] as String,row[4] as String,row[5] as String?,row[6] as String?,row[7] as String?,row[8] as String?,row[9] as String?,row[10] as String,row[12] as String?,row[13] as String?,row[14] as String?),true)
        true
    }

    @Synchronized override fun deleteContext(id: String): Boolean = transaction {
        validateContextId(id)
        val exists = connection.prepareStatement("SELECT 1 FROM athlete_context WHERE context_id=?").use { it.setString(1,id); it.executeQuery().use { r -> r.next() } }
        if (exists) {
            // A context deletion is stronger than staleness: remove dependent immutable
            // snapshots too, so neither their packet text nor published output survives.
            connection.prepareStatement("DELETE FROM athlete_context WHERE category='feedback' AND review_id IN (SELECT snapshot_id FROM connected_review_context_refs WHERE context_id=?)").use {
                it.setString(1, id); it.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM connected_review_snapshots WHERE snapshot_id IN (SELECT snapshot_id FROM connected_review_context_refs WHERE context_id=?)").use {
                it.setString(1, id); it.executeUpdate()
            }
        }
        connection.prepareStatement("DELETE FROM athlete_context WHERE context_id=?").use { it.setString(1,id); it.executeUpdate()>0 }
    }

    @Synchronized override fun recordReviewFeedback(snapshotId: String, request: ReviewFeedbackRequest, observedOn: LocalDate): AthleteContext = transaction {
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
        saveContext(java.util.UUID.randomUUID().toString(), 1, context)
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
    fun trends(range: TrendRange, sport: String?, today: LocalDate): TrendReport =
        Trends.report(history(range.previous().oldest, range.newest, 50_000), range, sport, today, statuses(today))

    @Synchronized
    fun removeImports() {
        transaction {
            val state = reviewScheduleStateLocked()
            staleConnectedReviewsLocked("evidence_removed")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM activities")
                it.executeUpdate("DELETE FROM wellness")
                it.executeUpdate("DELETE FROM sync_status")
                it.executeUpdate("DELETE FROM review_import_changes")
            }
            saveReviewStateLocked(ReviewSchedulerState(configuration = state.configuration, version = Math.addExact(state.version, 1)))
        }
        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        // Events are a separate app-owned table and are deliberately untouched.
        // This is data removal, not a claim of forensic SD-card/backup erasure.
    }

    @Synchronized
    override fun close() { connection.close() }
}
