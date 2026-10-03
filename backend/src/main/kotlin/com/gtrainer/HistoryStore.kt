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

class HistoryStore(path: Path) : AutoCloseable, ManualEventRepository {
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
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=FULL")
                statement.execute("PRAGMA secure_delete=ON")
                val version = statement.executeQuery("PRAGMA user_version").use { result -> result.next(); result.getInt(1) }
                require(version in 0..2) { "Database schema is not compatible with this app" }
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
