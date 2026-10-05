package com.gtrainer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun syntheticActivity(): ActivityRecord = requireNotNull(IntervalsNormalizer.activity(
    Json.parseToJsonElement("""{"id":"synthetic-store-a1","type":"Ride","start_date_local":"2020-06-01T10:00:00Z","moving_time":1200,"elapsed_time":1500,"calories":123}""") as JsonObject)).record

internal fun syntheticWellness(): WellnessRecord = requireNotNull(IntervalsNormalizer.wellness(
    Json.parseToJsonElement("""{"id":"2020-06-01","weight":70,"hrv":42,"sleepSecs":28800}""") as JsonObject)).record

internal class SyntheticSource : HistorySource {
    override val sourceId = "intervals.icu"
    var calls = 0
    var activityResult = ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity()), rejected = 1, incomplete = 1)
    var wellnessResult = ReadResult(ReadStatus.SUCCESS, listOf(syntheticWellness()), incomplete = 1)
    override suspend fun activities(range: ReadRange): ReadResult<ActivityRecord> { calls++; return activityResult }
    override suspend fun wellness(range: ReadRange): ReadResult<WellnessRecord> { calls++; return wellnessResult }
}

class HistoryStoreTest {
    private val date = LocalDate.parse("2020-06-10")
    private val clock = Clock.fixed(Instant.parse("2020-06-10T12:00:00Z"), ZoneOffset.UTC)
    private val range = ReadRange(LocalDate.parse("2020-06-01"), date)

    private fun fixture(test: (Path, HistoryStore) -> Unit) {
        val directory = Files.createTempDirectory("gtrainer-synthetic-store-")
        val path = directory.resolve("synthetic.sqlite3")
        try { HistoryStore(path).use { test(path, it) } } finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private fun emptyInput() = AnalysisInput(1, "a".repeat(64), "2020-06-10T12:00:00Z", null,
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun binding(request: String, contexts: List<AthleteContext> = emptyList(), provider: String = "local", model: String = "synthetic") =
        ConnectedReviewBinding(request, emptyInput(), retrieveAthleteContext(contexts,
            ContextRetrievalQuery(LocalDate.parse("2020-06-10"), optionalLimit = 20, hardPacketLimit = 100)),
            "2020-06-01", "2020-06-10", null,
            ConnectedReviewProviderSelection(if (provider == "hosted") "synthetic-hosted" else "synthetic-local", model, provider),
            InterpretationContractV1.PROFILE)

    @Test
    fun `connected snapshots survive restart and bind generations context evidence and provider`() = fixture { path, store ->
        val entry = store.createContext(AthleteContextRequest("note", "user_report", "synthetic user", "2020-06-01", content = "synthetic context"))
        val first = store.createConnectedReviewSnapshot(binding("lineage-a", listOf(entry)), clock.instant())
        assertEquals(listOf(ContextRevisionRefV1(entry.contextId, entry.revision)), first.context)
        assertEquals("2020-06-01", first.coverageFrom)
        HistoryStore(path).use { reopened ->
            assertEquals(first, reopened.connectedReviewStatus(first.snapshotId))
            val otherLineage = reopened.createConnectedReviewSnapshot(binding("lineage-b"), clock.instant())
            val second = reopened.createConnectedReviewSnapshot(binding("lineage-a", listOf(entry), "hosted", "synthetic-model"), clock.instant())
            assertEquals("STALE", reopened.connectedReviewStatus(first.snapshotId).state)
            assertEquals("new_generation", reopened.connectedReviewStatus(first.snapshotId).staleReason)
            assertEquals("local:synthetic-local", first.provider)
            assertEquals("hosted:synthetic-hosted", second.provider)
            assertEquals(listOf(entry), reopened.contexts(date))
            assertEquals("2020-06-01", reopened.connectedReviewStatus(first.snapshotId).coverageFrom)
            assertEquals("2020-06-10", reopened.connectedReviewStatus(first.snapshotId).coverageUntil)
            assertEquals(64, first.packetDigest.length)
            assertEquals("IN_FLIGHT", reopened.connectedReviewStatus(otherLineage.snapshotId).state)
            assertEquals(2, second.generation)
            val published = reopened.publishConnectedReview(second.snapshotId, ValidatedConnectedReviewOutput("synthetic validated"),
                second.evidenceDigest, second.context, second.provider, second.model, second.contractVersion)
            assertEquals("PUBLISHED", published.state)
            val corrected = reopened.correctContext(entry.contextId,
                AthleteContextRequest("note", "user_report", "synthetic user", "2020-06-02", content = "synthetic correction"))
            assertEquals(entry.revision + 1, corrected.revision)
            assertEquals("STALE", reopened.connectedReviewStatus(second.snapshotId).state)
            val refused = reopened.publishConnectedReview(second.snapshotId, ValidatedConnectedReviewOutput("must not publish"),
                second.evidenceDigest, second.context, second.provider, second.model, second.contractVersion)
            assertEquals("STALE", refused.state)
            assertEquals("synthetic validated", refused.publishedOutput)
        }
    }

    @Test
    fun `failed connected request is terminal across restart and cannot reuse attempt budget`() = fixture { path, store ->
        val snapshot = store.createConnectedReviewSnapshot(binding("terminal-request"), clock.instant())
        val failed = store.failConnectedReview(snapshot.snapshotId, "attempt_timeout")
        assertEquals("FAILED", failed.state)
        assertEquals("attempt_timeout", failed.staleReason)
        HistoryStore(path).use { reopened ->
            val restored = reopened.connectedReviewStatus(snapshot.snapshotId)
            assertEquals("FAILED", restored.state)
            val refused = reopened.publishConnectedReview(snapshot.snapshotId, ValidatedConnectedReviewOutput("late output"),
                restored.evidenceDigest, restored.context, restored.provider, restored.model, restored.contractVersion)
            assertEquals("FAILED", refused.state)
            assertEquals(null, refused.publishedOutput)
        }
    }

    @Test
    fun `connected snapshot execution claim is atomic and terminal across restart`() = fixture { path, store ->
        val snapshot = store.createConnectedReviewSnapshot(binding("claim-request"), clock.instant())
        val claimed = store.claimConnectedReview(snapshot.snapshotId)
        assertEquals("RUNNING", claimed?.state)
        assertEquals(null, store.claimConnectedReview(snapshot.snapshotId))
        HistoryStore(path).use { reopened ->
            assertEquals("RUNNING", reopened.connectedReviewStatus(snapshot.snapshotId).state)
            assertEquals(null, reopened.claimConnectedReview(snapshot.snapshotId))
            assertEquals("FAILED", reopened.failConnectedReview(snapshot.snapshotId, "cancelled").state)
        }
    }

    @Test
    fun `changed imported evidence stales connected output without affecting unrelated queue records`() = fixture { _, store ->
        store.begin("activities", clock.instant())
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), clock.instant())
        val queueBefore = store.reviewScheduleState().queue
        val snapshot = store.createConnectedReviewSnapshot(binding("evidence-lineage"), clock.instant())
        store.begin("activities", clock.instant())
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity().copy(calories = Measurement(999.0, "kcal")))), clock.instant())
        assertEquals("STALE", store.connectedReviewStatus(snapshot.snapshotId).state)
        assertEquals("evidence_changed", store.connectedReviewStatus(snapshot.snapshotId).staleReason)
        assertEquals(1, store.history(range.oldest, range.newest).activities.size)
        assertEquals(queueBefore, store.reviewScheduleState().queue)
    }

    @Test
    fun `deleting context purges dependent packet and review output but preserves unrelated data`() = fixture { path, store ->
        store.begin("activities", clock.instant())
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), clock.instant())
        val queueBefore = store.reviewScheduleState().queue
        val entry = store.createContext(AthleteContextRequest("note", "user_report", "synthetic user", "2020-06-01",
            content = "delete-me-private-marker"))
        val dependent = store.createConnectedReviewSnapshot(binding("dependent", listOf(entry)), clock.instant())
        val unrelated = store.createConnectedReviewSnapshot(binding("unrelated"), clock.instant())
        store.publishConnectedReview(dependent.snapshotId, ValidatedConnectedReviewOutput("delete-me-private-marker-output"),
            dependent.evidenceDigest, dependent.context, dependent.provider, dependent.model, dependent.contractVersion)
        store.recordReviewFeedback(dependent.snapshotId, ReviewFeedbackRequest(rating = "useful"), date)
        store.publishConnectedReview(unrelated.snapshotId, ValidatedConnectedReviewOutput("unrelated-output"),
            unrelated.evidenceDigest, unrelated.context, unrelated.provider, unrelated.model, unrelated.contractVersion)

        assertTrue(store.deleteContext(entry.contextId))
        assertTrue(store.contexts(date).none { it.contextId == entry.contextId })
        assertTrue(store.contexts(date).none { it.reviewId == dependent.snapshotId })
        assertFailsWith<ConnectedReviewNotFound> { store.connectedReviewStatus(dependent.snapshotId) }
        assertEquals("PUBLISHED", store.connectedReviewStatus(unrelated.snapshotId).state)
        assertEquals("unrelated-output", store.connectedReviewStatus(unrelated.snapshotId).publishedOutput)
        assertEquals(queueBefore, store.reviewScheduleState().queue)
        assertEquals(1, store.history(range.oldest, range.newest).activities.size)
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM connected_review_snapshots WHERE packet_json LIKE '%delete-me-private-marker%'").use { rows ->
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
                }
                statement.executeQuery("SELECT count(*) FROM connected_review_snapshot_state WHERE published_output LIKE '%delete-me-private-marker%'").use { rows ->
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
                }
                statement.executeQuery("SELECT count(*) FROM connected_review_context_refs WHERE context_id='${entry.contextId}'").use { rows ->
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `review usefulness and correction persist as attributed context linked to immutable snapshot`() = fixture { path, store ->
        val snapshot = store.createConnectedReviewSnapshot(binding("feedback-lineage"), clock.instant())
        store.publishConnectedReview(snapshot.snapshotId, ValidatedConnectedReviewOutput("synthetic review text"),
            snapshot.evidenceDigest, snapshot.context, snapshot.provider, snapshot.model, snapshot.contractVersion)
        val feedback = store.recordReviewFeedback(snapshot.snapshotId,
            ReviewFeedbackRequest(rating = "not_useful", correction = "synthetic correction"), date)

        assertEquals("feedback", feedback.category)
        assertEquals("review_feedback", feedback.sourceCategory)
        assertEquals("athlete", feedback.authorAttribution)
        assertEquals("authenticated_user", feedback.enteredBy)
        assertEquals(date.toString(), feedback.observedOn)
        assertEquals(snapshot.snapshotId, feedback.reviewId)
        assertTrue(feedback.content.contains("not_useful"))
        assertTrue(feedback.content.contains("synthetic correction"))
        assertFalse(feedback.toString().contains("synthetic correction"))
        assertFailsWith<IllegalArgumentException> { ReviewFeedbackRequest(rating = "five_stars") }
        HistoryStore(path).use { reopened ->
            val restored = reopened.contexts(date).single { it.contextId == feedback.contextId }
            assertEquals(feedback, restored)
            assertEquals(snapshot.snapshotId, restored.reviewId)
        }
    }

    @Test
    fun `migration persists and repeated corrected imports do not duplicate records`() = fixture { path, store ->
        store.begin("activities", clock.instant())
        val record = syntheticActivity()
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(record, record)), clock.instant())
        assertEquals(1, store.statuses(date).first().recordCount)
        val corrected = record.copy(calories = Measurement(234.0, "kcal"))
        store.begin("activities", clock.instant())
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(corrected)), clock.instant())
        assertEquals(234.0, store.history(range.oldest, range.newest).activities.single().calories?.value)
        HistoryStore(path).use { reopened ->
            assertEquals(1, reopened.statuses(date).first().recordCount)
            assertEquals(234.0, reopened.history(range.oldest, range.newest).activities.single().calories?.value)
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
            database.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM schema_migrations").use { result ->
                    result.next(); assertEquals(7, result.getInt(1))
                }
            }
        }
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
        for (suffix in listOf("-wal", "-shm")) {
            val file = Path.of(path.toString() + suffix)
            if (Files.exists(file)) assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        }
    }

    @Test
    fun `schema v2 migration preserves imported identities and event identity`() {
        val directory = Files.createTempDirectory("gtrainer-v2-migration-")
        val path = directory.resolve("synthetic.sqlite3")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                database.createStatement().use { statement ->
                    statement.execute("PRAGMA user_version=2")
                    statement.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_utc TEXT NOT NULL)")
                    statement.execute("INSERT INTO schema_migrations VALUES (1,'2020-01-01T00:00:00Z'),(2,'2020-01-02T00:00:00Z')")
                    statement.execute("CREATE TABLE activities (source TEXT NOT NULL, source_id TEXT NOT NULL, observed_date TEXT NOT NULL, record_json TEXT NOT NULL, PRIMARY KEY(source,source_id))")
                    statement.execute("CREATE TABLE wellness (source TEXT NOT NULL, source_id TEXT NOT NULL, observed_date TEXT NOT NULL, record_json TEXT NOT NULL, PRIMARY KEY(source,source_id))")
                    statement.execute("CREATE INDEX activity_dates ON activities(observed_date)")
                    statement.execute("CREATE INDEX wellness_dates ON wellness(observed_date)")
                    statement.execute("CREATE TABLE events (id TEXT PRIMARY KEY, start_date TEXT NOT NULL, end_date TEXT NOT NULL, sport TEXT NOT NULL, goal TEXT NOT NULL, notes TEXT)")
                    statement.execute("CREATE TABLE sync_status (category TEXT PRIMARY KEY, last_attempt_utc TEXT, last_success_utc TEXT, read_status TEXT NOT NULL, rejected INTEGER NOT NULL DEFAULT 0, incomplete INTEGER NOT NULL DEFAULT 0)")
                    statement.execute("CREATE TABLE review_schedule (singleton INTEGER PRIMARY KEY CHECK(singleton=1), state_json TEXT NOT NULL)")
                    statement.execute("CREATE TABLE review_import_changes (revision INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT NOT NULL, observed_date TEXT NOT NULL, received_utc TEXT NOT NULL, identity_sha256 TEXT NOT NULL, sport TEXT, sleep_changed INTEGER NOT NULL, sleep_available INTEGER NOT NULL)")
                    val activity = syntheticActivity()
                    val wellness = syntheticWellness()
                    statement.execute("INSERT INTO activities VALUES ('${activity.source}','${activity.sourceRecordId}','${activity.startLocal.take(10)}','${Json.encodeToString(activity)}')")
                    statement.execute("INSERT INTO wellness VALUES ('${wellness.source}','${wellness.sourceRecordId}','${wellness.date}','${Json.encodeToString(wellness)}')")
                    statement.execute("INSERT INTO events VALUES ('stable-event-id','2020-06-01','2020-06-02','Run','synthetic','note')")
                    statement.execute("INSERT INTO review_schedule VALUES (1,'${Json.encodeToString(ReviewSchedulerState())}')")
                }
            }
            HistoryStore(path).use { migrated ->
                assertEquals("synthetic-store-a1", migrated.history(range.oldest, range.newest).activities.single().sourceRecordId)
                assertEquals("2020-06-01", migrated.history(range.oldest, range.newest).wellness.single().sourceRecordId)
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                database.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM events").use { rows -> rows.next(); assertEquals("stable-event-id", rows.getString(1)) }
                    statement.executeQuery("PRAGMA user_version").use { rows -> rows.next(); assertEquals(7, rows.getInt(1)) }
                    statement.executeQuery("SELECT count(*) FROM schema_migrations WHERE version=7").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
                }
            }
        } finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `independent category failure retains history and distinguishes last success from unknown upstream`() = fixture { _, store ->
        runBlocking {
            val source = SyntheticSource()
            val service = HistoryService(store, source, clock)
            val first = service.sync(range)
            assertEquals(2, source.calls)
            assertEquals(1, first[0].recordCount)
            assertEquals(1, first[0].rejected)
            assertEquals(1, first[0].incomplete)
            assertEquals(9, first[0].latestObservedAgeDays)
            assertEquals("unknown", first[0].upstreamFreshness)
            val lastSuccess = first[0].lastSuccessUtc
            source.activityResult = ReadResult(ReadStatus.KEY_REJECTED)
            val failed = service.sync(range)
            assertEquals("KEY_REJECTED", failed[0].readStatus)
            assertEquals(lastSuccess, failed[0].lastSuccessUtc)
            assertEquals(1, failed[0].recordCount)
            assertEquals("SUCCESS", failed[1].readStatus)
            assertEquals(1, failed[1].recordCount)
            assertEquals("unknown", failed[1].upstreamFreshness)
            source.activityResult = ReadResult(ReadStatus.SUCCESS)
            service.sync(range)
            assertEquals(1, store.history(range.oldest, range.newest).activities.size)
        }
    }

    @Test
    fun `local import removal keeps app events and makes no upstream calls`() = fixture { path, store ->
        runBlocking {
            val source = SyntheticSource()
            val service = HistoryService(store, source, clock)
            service.sync(range)
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                database.createStatement().use { statement ->
                    statement.executeUpdate("INSERT INTO events VALUES ('synthetic-event','2020-06-01','2020-06-02','Ride','synthetic trip, not personal data',NULL)")
                }
            }
            service.removeImports()
            assertEquals(2, source.calls)
            assertTrue(service.history(range.oldest, range.newest).activities.isEmpty())
            assertTrue(service.history(range.oldest, range.newest).wellness.isEmpty())
            assertEquals(listOf(0, 0), service.statuses().map { it.recordCount })
            assertEquals(listOf("NEVER_READ", "NEVER_READ"), service.statuses().map { it.readStatus })
            DriverManager.getConnection("jdbc:sqlite:$path").use { database ->
                database.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM events").use { result -> result.next(); assertEquals(1, result.getInt(1)) }
                }
            }
        }
    }

    @Test
    fun `opaque IDs are parameterized and distinct source identities remain distinct`() = fixture { path, store ->
        store.begin("activities", clock.instant())
        val first = syntheticActivity().copy(sourceRecordId = "synthetic'quoted-id")
        val second = first.copy(source = "synthetic.other")
        store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(first, second)), clock.instant())
        assertEquals(2, store.history(range.oldest, range.newest).activities.size)
        assertEquals(2, store.statuses(date).first().recordCount)
        assertFailsWith<IllegalArgumentException> { store.begin("activities; DROP TABLE events", clock.instant()) }
        assertFailsWith<TrendSizeLimit> { store.history(range.oldest, range.newest, maximumRecords = 1) }
    }

    @Test
    fun `concurrent sync or deletion is rejected rather than racing imports`() = fixture { _, store ->
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            val source = object : HistorySource {
                override val sourceId = "synthetic"
                override suspend fun activities(range: ReadRange): ReadResult<ActivityRecord> {
                    started.complete(Unit); proceed.await(); return ReadResult(ReadStatus.SUCCESS)
                }
                override suspend fun wellness(range: ReadRange): ReadResult<WellnessRecord> = ReadResult(ReadStatus.SUCCESS)
            }
            val service = HistoryService(store, source, clock)
            val first = async { service.sync(range) }
            started.await()
            assertFailsWith<SyncBusy> { service.sync(range) }
            assertFailsWith<SyncBusy> { service.removeImports() }
            proceed.complete(Unit)
            assertEquals(2, first.await().size)
        }
    }

    @Test
    fun `interrupted reads are not left apparently running after restart`() = fixture { path, store ->
        store.begin("wellness", clock.instant())
        HistoryStore(path).use { reopened ->
            val status = reopened.statuses(date)[1]
            assertEquals("INTERRUPTED", status.readStatus)
            assertNotNull(status.lastAttemptUtc)
            assertEquals(null, status.lastSuccessUtc)
        }
    }
}
