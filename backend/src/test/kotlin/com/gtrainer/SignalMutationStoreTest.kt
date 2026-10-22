package com.gtrainer

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SignalMutationStoreTest {
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        db.createStatement().use {
            it.execute("CREATE TABLE signal_evidence_mutations(sequence INTEGER PRIMARY KEY AUTOINCREMENT,source TEXT NOT NULL,identity_sha256 TEXT NOT NULL,old_date TEXT,new_date TEXT NOT NULL,fields_json TEXT NOT NULL)")
            it.execute("CREATE TABLE signal_evidence_epoch(singleton INTEGER PRIMARY KEY CHECK(singleton=1),removal_generation INTEGER NOT NULL)")
        }
        SignalMutationStore(db).seed()
    }

    private fun insert(db: Connection, sequence: Long, source: String, old: String?, new: String, fields: String) {
        db.prepareStatement("INSERT INTO signal_evidence_mutations(sequence,source,identity_sha256,old_date,new_date,fields_json) VALUES(?,?,?,?,?,?)").use { s ->
            s.setLong(1, sequence); s.setString(2, source); s.setString(3, "a".repeat(64)); s.setString(4, old)
            s.setString(5, new); s.setString(6, fields); s.executeUpdate()
        }
    }

    private fun changes(db: Connection) = db.createStatement().use { s -> s.executeQuery("SELECT total_changes()").use { r -> r.next(); r.getLong(1) } }

    @Test fun `capture is contiguous inert and watches dated field ancestry`() = connection().use { db ->
        insert(db, 1, "garmin", "2025-01-01", "2025-01-02", "[\"hrv\"]")
        insert(db, 2, "garmin", null, "2025-01-04", "[\"sleepSecs\"]")
        val store = SignalMutationStore(db)
        val before = changes(db)
        val coverage = store.capture(0,0)
        assertTrue(coverage.complete())
        assertTrue(coverage.affects("hrv", "garmin", "2025-01-01", "2025-01-01"))
        assertTrue(coverage.affects("hrv", "garmin", "2025-01-02", "2025-01-02"))
        assertFalse(coverage.affects("hrv", "garmin", "2025-01-04", "2025-01-04"))
        assertFalse(coverage.affects("hrv", "other", "2025-01-01", "2025-01-04"))
        assertTrue(coverage.affects("sleepSecs", "garmin", "2025-01-04", "2025-01-04"))
        assertEquals(before, changes(db))
        assertTrue(store.capture(2,0).complete())
        assertFalse(store.capture(3,0).complete())
    }

    @Test fun `missing retained sequence and replay overflow fail closed`() = connection().use { db ->
        insert(db, 1, "garmin", null, "2025-01-01", "[\"hrv\"]")
        insert(db, 2, "garmin", "2025-01-01", "2025-01-02", "[\"hrv\"]")
        db.createStatement().use { it.executeUpdate("DELETE FROM signal_evidence_mutations WHERE sequence=1") }
        assertFalse(SignalMutationStore(db).capture(0,0).complete())
        insert(db, 50_001, "garmin", null, "2025-01-01", "[\"hrv\"]")
        insert(db, 50_002, "garmin", null, "2025-01-02", "[\"hrv\"]")
        assertFalse(SignalMutationStore(db).capture(0,0).complete())
    }

    @Test fun `record validates before writes and deleted ring never rewinds ancestry`() = connection().use { db ->
        val store=SignalMutationStore(db)
        val before=changes(db)
        store.record("synthetic","a".repeat(64),null,"2025-01-01",emptySet())
        assertEquals(before,changes(db))
        assertFailsWith<IllegalArgumentException> { store.record("synthetic","bad",null,"2025-01-01",setOf("hrv")) }
        assertFailsWith<IllegalArgumentException> { store.record("synthetic","a".repeat(64),null,"2025-01-01",setOf("readiness")) }
        assertEquals(before,changes(db))
        assertFailsWith<IllegalArgumentException> { store.record("s".repeat(65),"a".repeat(64),null,"2025-01-01",setOf("hrv")) }
        store.record("s".repeat(64),"a".repeat(64),null,"2025-01-01",setOf("sleepSecs","hrv"))
        val first=store.capture(0,0)
        assertTrue(first.complete());assertEquals(listOf("hrv","sleepSecs"),first.mutations.single().fields)
        store.remove()
        val removed=store.capture(0,0)
        assertEquals(1L,removed.through);assertFalse(removed.complete())
        store.record("synthetic","a".repeat(64),null,"2025-01-01",setOf("hrv"))
        assertEquals(2L,store.capture(1,1).through);assertTrue(store.capture(1,1).complete())
        assertFalse(store.capture(1,0).complete());assertFalse(store.capture(0,1).complete())
        assertTrue(SignalMutationCoverage(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,emptyList(),0,0).complete())
    }

    @Test fun `ring retains exactly last fifty thousand changes and reports gaps`() = connection().use { db ->
        db.autoCommit=false
        db.prepareStatement("INSERT INTO signal_evidence_mutations(source,identity_sha256,old_date,new_date,fields_json) VALUES('synthetic',?,NULL,'2025-01-01','[\"hrv\"]')").use { statement ->
            statement.setString(1,"a".repeat(64))
            repeat(50_000) { statement.addBatch() };statement.executeBatch()
        }
        val store=SignalMutationStore(db)
        store.record("synthetic","a".repeat(64),null,"2025-01-01",setOf("hrv"))
        val bounded=store.capture(1,0)
        assertEquals(50_000,bounded.mutations.size);assertEquals(2L,bounded.oldestRetained)
        assertTrue(bounded.complete());assertFalse(store.capture(0,0).complete())
        // A malformed over-cap ring must be refused rather than returning a truncated proof.
        insert(db,1,"synthetic",null,"2025-01-01","[\"hrv\"]")
        assertFailsWith<IllegalArgumentException> { store.capture(0,0) }
        db.rollback()
    }
}
