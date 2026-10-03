package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewSchedulePersistenceTest {
    private val at = Instant.parse("2020-06-10T12:00:00Z")
    private val oldest = LocalDate.parse("2020-06-01")
    private val newest = LocalDate.parse("2020-06-10")

    private fun fixture(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("gtrainer-synthetic-review-schedule-")
        try { test(directory.resolve("synthetic.sqlite3")) } finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private fun database(path: Path, operation: (Connection) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite:$path").use(operation)
    }

    private fun status(state: ReviewSchedulerState) = ReviewScheduleStatus(state.configuration, state.version,
        "synthetic", emptyList(), emptyList())

    private fun changes(store: HistoryStore): List<ImportedReviewChange> {
        var result = emptyList<ImportedReviewChange>()
        store.evaluateReviewSchedule { state, changes, _, _ ->
            result = changes
            state to status(state)
        }
        return result
    }

    private fun digest(source: String, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(listOf(source, id)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun seedVersionOne(path: Path) = database(path) { connection ->
        connection.createStatement().use {
            it.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_utc TEXT NOT NULL)")
            it.execute("INSERT INTO schema_migrations VALUES (1,'2020-06-01T00:00:00Z')")
            for (table in listOf("activities", "wellness")) {
                it.execute("CREATE TABLE $table(source TEXT NOT NULL, source_id TEXT NOT NULL, observed_date TEXT NOT NULL, record_json TEXT NOT NULL, PRIMARY KEY(source,source_id))")
                it.execute("CREATE INDEX ${table}_dates ON $table(observed_date)")
            }
            it.execute("CREATE TABLE events(id TEXT PRIMARY KEY, start_date TEXT NOT NULL, end_date TEXT NOT NULL, sport TEXT NOT NULL, goal TEXT NOT NULL, notes TEXT)")
            it.execute("INSERT INTO events VALUES ('synthetic-event','2020-06-01','2020-06-02','Ride','synthetic',NULL)")
            it.execute("CREATE TABLE sync_status(category TEXT PRIMARY KEY, last_attempt_utc TEXT, last_success_utc TEXT, read_status TEXT NOT NULL, rejected INTEGER NOT NULL DEFAULT 0, incomplete INTEGER NOT NULL DEFAULT 0)")
            it.execute("INSERT INTO sync_status VALUES ('activities','2020-06-01T00:00:00Z','2020-06-01T00:00:00Z','SUCCESS',2,3)")
            it.execute("PRAGMA user_version=1")
        }
        connection.prepareStatement("INSERT INTO activities VALUES (?,?,?,?)").use {
            val record = syntheticActivity()
            it.setString(1, record.source); it.setString(2, record.sourceRecordId)
            it.setString(3, record.startLocal.take(10)); it.setString(4, Json.encodeToString(record)); it.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO wellness VALUES (?,?,?,?)").use {
            val record = syntheticWellness()
            it.setString(1, record.source); it.setString(2, record.sourceRecordId)
            it.setString(3, record.date); it.setString(4, Json.encodeToString(record)); it.executeUpdate()
        }
    }

    @Test
    fun `version one migration preserves history events and sync without enabling or replaying imports`() = fixture { path ->
        seedVersionOne(path)
        HistoryStore(path).use { store ->
            assertEquals(listOf(syntheticActivity()), store.history(oldest, newest).activities)
            assertEquals(listOf(syntheticWellness()), store.history(oldest, newest).wellness)
            assertEquals(ReviewSchedulerState(), store.reviewScheduleState())
            assertTrue(changes(store).isEmpty())
            val sync = store.statuses(newest).first()
            assertEquals("SUCCESS", sync.readStatus)
            assertEquals("2020-06-01T00:00:00Z", sync.lastSuccessUtc)
            assertEquals(2, sync.rejected)
            assertEquals(3, sync.incomplete)
            database(path) { connection -> connection.createStatement().use {
                it.executeQuery("PRAGMA user_version").use { rows -> rows.next(); assertEquals(2, rows.getInt(1)) }
                it.executeQuery("SELECT version FROM schema_migrations ORDER BY version").use { rows ->
                    assertTrue(rows.next()); assertEquals(1, rows.getInt(1))
                    assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertFalse(rows.next())
                }
                it.executeQuery("SELECT goal FROM events").use { rows -> rows.next(); assertEquals("synthetic", rows.getString(1)) }
            } }
        }
        HistoryStore(path).use { assertEquals(ReviewSchedulerState(), it.reviewScheduleState()) }
    }

    @Test
    fun `fresh schema stores complete disabled defaults and rejects future schemas`() = fixture { path ->
        HistoryStore(path).use { store ->
            val state = store.reviewScheduleState()
            assertFalse(state.configuration.enabled)
            assertTrue(state.configuration.presets.all { !it.enabled })
            assertNull(state.armedUtc)
            database(path) { connection -> connection.createStatement().use {
                it.executeQuery("SELECT state_json FROM review_schedule").use { rows ->
                    rows.next()
                    val json = Json.parseToJsonElement(rows.getString(1)).jsonObject
                    assertTrue(json.keys.containsAll(setOf("configuration", "version", "cursor", "modelVersion", "modelId", "armedUtc", "memories")))
                    assertTrue(json.getValue("configuration").jsonObject.keys.containsAll(setOf("enabled", "paused", "timeZone", "presets")))
                }
            } }
        }
        database(path) { it.createStatement().use { statement -> statement.execute("PRAGMA user_version=3") } }
        assertFailsWith<IllegalStateException> { HistoryStore(path).close() }
    }

    @Test
    fun `duplicates final values and reordered maps and sets do not create extra deltas`() = fixture { path ->
        HistoryStore(path).use { store ->
            val activity = syntheticActivity().copy(upstreamSources = linkedSetOf("GARMIN", "STRAVA"))
            val wellness = syntheticWellness().copy(upstreamSources = linkedSetOf("GARMIN", "STRAVA"))
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(activity, activity)), at)
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(wellness, wellness)), at)
            assertEquals(2, changes(store).size)
            val corrected = activity.copy(calories = Measurement(234.0, "kcal"))
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(corrected, activity.copy(upstreamSources = linkedSetOf("STRAVA", "GARMIN")))), at.plusSeconds(1))
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(wellness.copy(
                upstreamSources = linkedSetOf("STRAVA", "GARMIN"), measurements = wellness.measurements.entries.reversed().associate { it.toPair() }))), at.plusSeconds(1))
            assertEquals(2, changes(store).size)
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(activity, corrected, corrected)), at.plusSeconds(2))
            val deltas = changes(store)
            assertEquals(3, deltas.size)
            assertEquals(activity.sport, deltas.last().sport)
            assertEquals(at.plusSeconds(2).toString(), deltas.last().receivedUtc)
            assertEquals(corrected, store.history(oldest, newest).activities.single())
        }
    }

    @Test
    fun `journal contains only safe metadata and encode safe identity hashes`() = fixture { path ->
        HistoryStore(path).use { store ->
            val first = syntheticActivity().copy(source = "synthetic", sourceRecordId = "a:b\"quoted")
            val second = first.copy(source = "synthetic.a", sourceRecordId = "b\"quoted")
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(first, second)), at)
            val deltas = changes(store)
            assertEquals(2, deltas.size)
            assertEquals(digest(first.source, first.sourceRecordId), deltas.first().identitySha256)
            assertEquals(digest(second.source, second.sourceRecordId), deltas.last().identitySha256)
            assertNotEquals(deltas.first().identitySha256, deltas.last().identitySha256)
            assertTrue(deltas.all { it.identitySha256.matches(Regex("[0-9a-f]{64}")) && !it.sleepChanged })
            database(path) { connection -> connection.createStatement().use {
                it.executeQuery("PRAGMA table_info(review_import_changes)").use { rows ->
                    val columns = buildList { while (rows.next()) add(rows.getString("name")) }
                    assertEquals(listOf("revision", "category", "observed_date", "received_utc", "identity_sha256", "sport", "sleep_changed", "sleep_available"), columns)
                }
            } }
        }
    }

    @Test
    fun `sleep corrections and arrivals count but removals and unrelated wellness changes do not`() = fixture { path ->
        HistoryStore(path).use { store ->
            val original = syntheticWellness()
            val noSleep = original.copy(measurements = original.measurements - "sleepSecs")
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(noSleep)), at)
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(original)), at.plusSeconds(1))
            val corrected = original.copy(measurements = original.measurements + ("sleepSecs" to Measurement(30000.0, "seconds")))
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(corrected)), at.plusSeconds(2))
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(corrected.copy(
                measurements = corrected.measurements + ("steps" to Measurement(1000.0, "count"))))), at.plusSeconds(3))
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(noSleep)), at.plusSeconds(4))
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(noSleep.copy(
                measurements = noSleep.measurements + ("sleepScore" to Measurement(80.0, "source sleep score"))))), at.plusSeconds(5))
            assertEquals(listOf(false, true, true, false, false, true), changes(store).map { it.sleepChanged })
            assertTrue(changes(store).all { it.category == "wellness" && it.sport == null })
        }
    }

    @Test
    fun `failed empty and interrupted reads never append deltas or erase imported records`() = fixture { path ->
        HistoryStore(path).use { store ->
            val original = syntheticActivity()
            store.begin("activities", at)
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(original)), at)
            store.begin("activities", at.plusSeconds(1))
            store.interrupted("activities")
            for (failure in ReadStatus.entries.filter { it != ReadStatus.SUCCESS }) {
                store.completeActivities(ReadResult(failure, listOf(original.copy(calories = Measurement(999.0, "kcal")))), at.plusSeconds(2))
            }
            store.completeActivities(ReadResult(ReadStatus.SUCCESS), at.plusSeconds(3))
            assertEquals(listOf(original), store.history(oldest, newest).activities)
            assertEquals(1, changes(store).size)
        }
    }

    @Test
    fun `invalid batch including overwritten invalid input rolls back history journal and sync`() = fixture { path ->
        HistoryStore(path).use { store ->
            val original = syntheticActivity()
            store.begin("activities", at)
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(original)), at)
            val before = store.statuses(newest)
            val correction = original.copy(calories = Measurement(234.0, "kcal"))
            val invalid = original.copy(sourceRecordId = "synthetic-invalid", startLocal = "not-a-dateT10:00:00")
            assertFailsWith<Exception> {
                store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(correction, invalid)), at.plusSeconds(1))
            }
            assertFailsWith<Exception> {
                store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(invalid, invalid.copy(startLocal = original.startLocal))), at.plusSeconds(1))
            }
            assertEquals(listOf(original), store.history(oldest, newest).activities)
            assertEquals(1, changes(store).size)
            assertEquals(before, store.statuses(newest))
            // A journal write failure after a record upsert also rolls back both tables.
            database(path) { connection -> connection.createStatement().use {
                it.execute("CREATE TRIGGER synthetic_reject_delta BEFORE INSERT ON review_import_changes BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            } }
            assertFailsWith<Exception> { store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(correction)), at.plusSeconds(2)) }
            assertEquals(listOf(original), store.history(oldest, newest).activities)
            assertEquals(1, changes(store).size)
            assertEquals(before, store.statuses(newest))
        }
    }

    @Test
    fun `configuration CAS resets memories skips historical revisions and persists across reopen`() = fixture { path ->
        var saved = ReviewSchedulerState()
        HistoryStore(path).use { store ->
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity())), at)
            val configuration = ReviewScheduleConfiguration(enabled = true, timeZone = "Europe/Prague")
            val updated = store.updateReviewSchedule(0, configuration, at, "synthetic-model", 7)
            assertEquals(1L, updated.version)
            assertEquals(1L, updated.cursor)
            assertEquals(at.toString(), updated.armedUtc)
            assertEquals("synthetic-model", updated.modelId)
            assertEquals(7L, updated.modelVersion)
            assertTrue(changes(store).isEmpty())
            assertFailsWith<ReviewScheduleConflict> { store.updateReviewSchedule(0, ReviewScheduleConfiguration(), at, null, null) }
            assertEquals(updated, store.reviewScheduleState())
            store.evaluateReviewSchedule { state, _, revision, history ->
                assertEquals(listOf(syntheticActivity()), history(oldest, newest).activities)
                saved = state.copy(cursor = revision, memories = mapOf("synthetic" to ReviewTriggerMemory(count = 3, changed = true)))
                saved to status(saved)
            }
            assertEquals(saved, store.reviewScheduleState())
        }
        HistoryStore(path).use { store ->
            assertEquals(saved, store.reviewScheduleState())
            val reset = store.updateReviewSchedule(saved.version, saved.configuration.copy(paused = true), at.plusSeconds(1), null, null)
            assertEquals(2L, reset.version)
            assertEquals(1L, reset.cursor)
            assertNull(reset.armedUtc)
            assertNull(reset.modelId)
            assertTrue(reset.memories.isEmpty())
        }
    }

    @Test
    fun `evaluation rollback retains pending revisions and scheduler state`() = fixture { path ->
        HistoryStore(path).use { store ->
            store.completeWellness(ReadResult(ReadStatus.SUCCESS, listOf(syntheticWellness())), at)
            val before = store.reviewScheduleState()
            assertFailsWith<IllegalStateException> {
                store.evaluateReviewSchedule { _, changes, _, _ ->
                    assertEquals(1, changes.size)
                    error("synthetic evaluation failure")
                }
            }
            assertEquals(before, store.reviewScheduleState())
            assertEquals(1, changes(store).size)
            store.evaluateReviewSchedule { state, changes, revision, _ ->
                assertEquals(1, changes.size)
                assertEquals(1L, revision)
                val updated = state.copy(cursor = revision)
                updated to status(updated)
            }
            assertEquals(1L, store.reviewScheduleState().cursor)
            assertTrue(changes(store).isEmpty())
        }
    }

    @Test
    fun `journal overflow fails before evaluating without advancing cursor`() = fixture { path ->
        HistoryStore(path).use { store ->
            database(path) { connection ->
                connection.autoCommit = false
                connection.prepareStatement("INSERT INTO review_import_changes(category,observed_date,received_utc,identity_sha256,sport,sleep_changed,sleep_available) VALUES ('wellness','2020-06-01',?,'synthetic',NULL,0,0)").use {
                    it.setString(1, at.toString())
                    repeat(50_001) { _ -> it.addBatch() }
                    it.executeBatch()
                }
                connection.commit()
            }
            var evaluated = false
            assertFailsWith<TrendSizeLimit> {
                store.evaluateReviewSchedule { state, _, _, _ -> evaluated = true; state to status(state) }
            }
            assertFalse(evaluated)
            assertEquals(ReviewSchedulerState(), store.reviewScheduleState())
        }
    }

    @Test
    fun `evaluation history function is bounded and overflow leaves state unchanged`() = fixture { path ->
        HistoryStore(path).use { store ->
            database(path) { connection ->
                connection.autoCommit = false
                connection.prepareStatement("INSERT INTO activities VALUES ('synthetic',?,'2020-06-01',?)").use {
                    it.setString(2, Json.encodeToString(syntheticActivity()))
                    repeat(50_001) { index -> it.setString(1, "synthetic-$index"); it.addBatch() }
                    it.executeBatch()
                }
                connection.commit()
            }
            assertFailsWith<TrendSizeLimit> {
                store.evaluateReviewSchedule { state, _, revision, history ->
                    history(oldest, newest)
                    state.copy(cursor = revision) to status(state)
                }
            }
            assertEquals(ReviewSchedulerState(), store.reviewScheduleState())
        }
    }

    @Test
    fun `removing imports preserves configuration and events but disarms and clears scheduler state`() = fixture { path ->
        seedVersionOne(path)
        HistoryStore(path).use { store ->
            val configuration = ReviewScheduleConfiguration(enabled = true, paused = true, timeZone = "Europe/Prague")
            store.updateReviewSchedule(0, configuration, at, "synthetic-model", 4)
            store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(syntheticActivity().copy(calories = Measurement(234.0, "kcal")))), at)
            store.evaluateReviewSchedule { state, _, revision, _ ->
                state.copy(cursor = revision, memories = mapOf("synthetic" to ReviewTriggerMemory(count = 3))) to status(state)
            }
            store.removeImports()
            assertEquals(ReviewSchedulerState(configuration = configuration, version = 2), store.reviewScheduleState())
            assertTrue(changes(store).isEmpty())
            assertTrue(store.history(oldest, newest).activities.isEmpty())
            assertTrue(store.history(oldest, newest).wellness.isEmpty())
            assertTrue(store.statuses(newest).all { it.readStatus == "NEVER_READ" })
            store.evaluateReviewSchedule { state, _, revision, _ -> assertEquals(0L, revision); state to status(state) }
            database(path) { connection -> connection.createStatement().use {
                it.executeQuery("SELECT count(*) FROM events").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
                it.executeQuery("SELECT count(*) FROM review_import_changes").use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
            } }
        }
        HistoryStore(path).use { assertEquals(2L, it.reviewScheduleState().version) }
    }

    @Test
    fun `corrupt or missing persisted state never falls back to defaults`() = fixture { path ->
        HistoryStore(path).use { store ->
            database(path) { it.createStatement().use { statement -> statement.executeUpdate("UPDATE review_schedule SET state_json='synthetic-corrupt'") } }
            assertFailsWith<IllegalStateException> { store.reviewScheduleState() }
            assertFailsWith<IllegalStateException> { store.updateReviewSchedule(0, ReviewScheduleConfiguration(), at, null, null) }
            assertFailsWith<IllegalStateException> { store.removeImports() }
        }
        assertFailsWith<IllegalStateException> { HistoryStore(path).close() }
        database(path) { it.createStatement().use { statement -> statement.executeUpdate("DELETE FROM review_schedule") } }
        assertFailsWith<IllegalStateException> { HistoryStore(path).close() }
    }
}
