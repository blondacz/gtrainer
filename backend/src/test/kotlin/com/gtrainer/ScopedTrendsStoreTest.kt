package com.gtrainer

import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class ScopedTrendsStoreTest {
    @Test fun `fresh schema removal invalidates scoped confirmation until timezone is explicitly resaved`() {
        val dir = Files.createTempDirectory("scoped-confirmation-")
        val path = dir.resolve("history.sqlite3")
        val asOf = Instant.parse("2020-06-10T12:00:00Z")
        try {
            var version: Long
            HistoryStore(path).use { store ->
                store.removeImports()
                version = store.reviewScheduleState().version
                assertEquals(1L, version) // legacy schedule version still advances on removal
                val options = store.scopedReviewOptions(asOf, null, null, 0)
                assertNull(options.timeZone); assertNull(options.today)
                assertTrue(options.activities.isEmpty()); assertNull(options.browseRange)
                assertEquals(version, options.configurationVersion)
                assertFailsWith<IllegalArgumentException> {
                    store.scopedTrends(ScopedReviewRequest(scope = "custom", oldest = "2020-06-01", newest = "2020-06-10"), asOf)
                }
                store.updateReviewSchedule(version, ReviewScheduleConfiguration(timeZone = "Europe/Prague"), asOf, null, null)
                assertEquals("Europe/Prague", store.scopedReviewOptions(asOf, null, null, 0).timeZone)
                store.removeImports()
                assertEquals("Europe/Prague", store.scopedReviewOptions(asOf, null, null, 0).timeZone)
            }
            HistoryStore(path).use { reopened ->
                assertEquals("Europe/Prague", reopened.scopedReviewOptions(asOf, null, null, 0).timeZone)
            }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `version seven databases are refused rather than migrated`() {
        val dir = Files.createTempDirectory("scoped-identity-migration-")
        val path = dir.resolve("history.sqlite3")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use {
                it.execute("PRAGMA user_version=7")
                it.execute("CREATE TABLE fixture(value TEXT)")
                it.execute("INSERT INTO fixture VALUES ('preserve-me')")
            } }
            assertFailsWith<HistorySchemaMismatch> { HistoryStore(path).close() }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use {
                it.executeQuery("SELECT value FROM fixture").use { r -> r.next(); assertEquals("preserve-me", r.getString(1)) }
            } }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `selected lookup ignores unrelated malformed payloads beyond the period budget`() {
        val dir = Files.createTempDirectory("scoped-selected-index-")
        val path = dir.resolve("history.sqlite3")
        try {
            val record = syntheticActivity()
            val id = scopedActivityId(record)
            HistoryStore(path).use { store ->
                store.begin("activities", Instant.parse("2020-06-10T12:00:00Z"))
                store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(record)), Instant.parse("2020-06-10T12:00:00Z"))
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
                db.autoCommit = false
                db.prepareStatement("INSERT INTO activities(source,source_id,observed_date,record_json) VALUES(?,?,?,?)").use { activity ->
                    db.prepareStatement("INSERT INTO scoped_activity_identities(identity_sha256,source,source_id) VALUES(?,?,?)").use { identity ->
                        repeat(50_100) { n ->
                            val source = "decoy"
                            val sourceId = "unrelated-$n"
                            activity.setString(1, source); activity.setString(2, sourceId); activity.setString(3, "2020-06-01")
                            activity.setString(4, "not-a-record"); activity.addBatch()
                            identity.setString(1, "%064x".format(n + 1)); identity.setString(2, source); identity.setString(3, sourceId); identity.addBatch()
                            if (n % 1000 == 999) { activity.executeBatch(); identity.executeBatch() }
                        }
                        activity.executeBatch(); identity.executeBatch()
                    }
                }
                db.commit()
            }
            HistoryStore(path).use { store ->
                store.updateReviewSchedule(0, ReviewScheduleConfiguration(timeZone = "Europe/Prague"), Instant.parse("2020-06-10T12:00:00Z"), null, null)
                assertEquals(record, ScopedHistoryReads.selectedForTest(store, id))
            }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `import upsert retains stable identity as record dates change`() {
        val dir = Files.createTempDirectory("scoped-identity-upsert-")
        try {
            val path = dir.resolve("history.sqlite3")
            val original = syntheticActivity()
            val amended = original.copy(startLocal = "2020-06-02T10:00:00Z", calories = Measurement(234.0, "kcal"))
            HistoryStore(path).use { store ->
                store.begin("activities", Instant.parse("2020-06-10T12:00:00Z"))
                store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(original)), Instant.parse("2020-06-10T12:00:00Z"))
                store.begin("activities", Instant.parse("2020-06-11T12:00:00Z"))
                store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(amended)), Instant.parse("2020-06-11T12:00:00Z"))
                assertEquals(scopedActivityId(original), scopedActivityId(amended))
                store.updateReviewSchedule(0, ReviewScheduleConfiguration(timeZone = "Europe/Prague"), Instant.parse("2020-06-11T12:00:00Z"), null, null)
                assertEquals(amended, ScopedHistoryReads.selectedForTest(store, scopedActivityId(original)))
                assertEquals(amended, store.history(LocalDate.parse("2020-06-02"), LocalDate.parse("2020-06-02")).activities.single())
                val before = store.history(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-02")).activities
                assertFailsWith<IllegalArgumentException> {
                    store.completeActivities(ReadResult(ReadStatus.SUCCESS, listOf(
                        amended.copy(sourceRecordId = "would-be-rolled-back"), amended.copy(source = "invalid;source"))), Instant.parse("2020-06-12T12:00:00Z"))
                }
                assertEquals(before, store.history(LocalDate.parse("2020-06-01"), LocalDate.parse("2020-06-02")).activities)
                DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use {
                    it.executeQuery("SELECT count(*) FROM scoped_activity_identities WHERE source_id='would-be-rolled-back'").use { r -> r.next(); assertEquals(0, r.getInt(1)) }
                } }
            }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `populated unmarked schema is refused without deleting rows`() {
        val dir = Files.createTempDirectory("scoped-v1-migration-")
        val path = dir.resolve("history.sqlite3")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use { s ->
                s.execute("CREATE TABLE fixture(value TEXT)")
                s.execute("INSERT INTO fixture VALUES('preserve-me')")
            } }
            assertFailsWith<HistorySchemaMismatch> { HistoryStore(path).close() }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.createStatement().use {
                it.executeQuery("SELECT value FROM fixture").use { r -> r.next(); assertEquals("preserve-me", r.getString(1)) }
            } }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `explicit distant period windows do not decode intervening rows`() {
        val dir = Files.createTempDirectory("scoped-period-union-")
        val path = dir.resolve("history.sqlite3")
        try {
            HistoryStore(path).use { store ->
                store.updateReviewSchedule(0, ReviewScheduleConfiguration(timeZone = "UTC"), Instant.parse("2020-04-01T12:00:00Z"), null, null)
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db -> db.prepareStatement(
                "INSERT INTO activities(source,source_id,observed_date,record_json) VALUES('synthetic','unselected-middle','2020-02-15','malformed-json')").use { it.executeUpdate() } }
            HistoryStore(path).use { store ->
                val report = store.scopedTrends(ScopedReviewRequest(scope = "custom", oldest = "2020-02-01", newest = "2020-02-02",
                    comparison = ScopedDateRange("2020-03-01", "2020-03-02"), wellnessPolicy = "off"), Instant.parse("2020-04-01T12:00:00Z"))
                assertEquals("explicit", report.scope.comparison.policy)
                assertEquals(ScopedDateRange("2020-03-01", "2020-03-02"), report.scope.comparison.comparablePrevious)
                assertTrue(report.selected != null)
                assertTrue(report.wellnessContext.isEmpty())
            }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }
}

private fun ScopedHistoryReads.selectedForTest(store: HistoryStore, id: String): ActivityRecord =
    store.scopedTrends(ScopedReviewRequest(mode = "activities", scope = "activities", intent = "review", activityIds = listOf(id)), Instant.parse("2020-06-10T12:00:00Z")).sessions.single().record
