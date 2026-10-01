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
                    result.next(); assertEquals(1, result.getInt(1))
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
