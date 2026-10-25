package com.gtrainer

import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HistorySchemaTest {
    private fun withDatabase(block: (java.sql.Connection) -> Unit) {
        val dir = Files.createTempDirectory("history-schema-")
        try { DriverManager.getConnection("jdbc:sqlite:${dir.resolve("fixture.db")}").use(block) }
        finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `fresh canonical schema has immutable markers and validates`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        assertTrue(HistorySchema.createIfEmpty(db))
        db.prepareStatement("INSERT INTO review_schedule VALUES(1,?)").use { it.setString(1, Json { encodeDefaults = true }.encodeToString(ReviewSchedulerState())); it.executeUpdate() }
        db.createStatement().use { it.execute("INSERT INTO scoped_review_configuration VALUES(1,0)") }
        db.createStatement().use { it.execute("INSERT INTO plan_selection VALUES(1,0,NULL,NULL)") }
        db.createStatement().use { it.execute("INSERT INTO scoped_queue_metadata VALUES(1,0,0)") }
        PersonalReviewRuntimeStore(db).seed()
        SignalMutationStore(db).seed()
        db.createStatement().use { it.execute("INSERT INTO constraint_observation_metadata VALUES(1,0,0,1,1)") }
        HistorySchema.validate(db)
        db.createStatement().use { s ->
            s.executeQuery("PRAGMA user_version").use { it.next(); assertEquals(1, it.getInt(1)) }
            s.executeQuery("PRAGMA application_id").use { it.next(); assertEquals(HistorySchema.APPLICATION_ID, it.getInt(1)) }
            s.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name='schema_migrations'").use { it.next(); assertEquals(0, it.getInt(1)) }
            for ((type, count) in listOf("table" to 56, "index" to 16, "trigger" to 10))
                s.executeQuery("SELECT count(*) FROM sqlite_schema WHERE type='$type' AND name NOT LIKE 'sqlite_%'").use { it.next(); assertEquals(count, it.getInt(1)) }
        }
    }

    @Test fun `legacy and unmarked databases are refused rather than repaired`() {
        for (version in 1..8) withDatabase { db ->
            db.createStatement().use { it.execute("PRAGMA user_version=$version"); it.execute("CREATE TABLE fixture(value TEXT)") }
            assertFailsWith<HistorySchemaMismatch> { HistorySchema.createIfEmpty(db) }
            db.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name='fixture'").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) } }
        }
        withDatabase { db ->
            db.createStatement().use { it.execute("CREATE TABLE fixture(value TEXT)") }
            assertFailsWith<HistorySchemaMismatch> { HistorySchema.createIfEmpty(db) }
        }
    }

    @Test fun `wrong application id is refused`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA user_version=1"); it.execute("PRAGMA application_id=123") }
        assertFailsWith<HistorySchemaMismatch> { HistorySchema.createIfEmpty(db) }
    }

    @Test fun `partial current schema and missing singleton state are refused`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        HistorySchema.createIfEmpty(db)
        assertFailsWith<HistorySchemaMismatch> { HistorySchema.validate(db) }
        db.prepareStatement("INSERT INTO review_schedule VALUES(1,?)").use { it.setString(1, Json { encodeDefaults = true }.encodeToString(ReviewSchedulerState())); it.executeUpdate() }
        db.createStatement().use { it.execute("INSERT INTO scoped_review_configuration VALUES(1,0)"); it.execute("INSERT INTO plan_selection VALUES(1,0,NULL,NULL)"); it.execute("INSERT INTO scoped_queue_metadata VALUES(1,0,0)"); it.execute("DROP TABLE events") }
        PersonalReviewRuntimeStore(db).seed()
        SignalMutationStore(db).seed()
        db.createStatement().use { it.execute("INSERT INTO constraint_observation_metadata VALUES(1,0,0,1,1)") }
        assertFailsWith<HistorySchemaMismatch> { HistorySchema.validate(db) }
    }

    @Test fun `creation markers DDL and seeds roll back together on validation failure`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        db.autoCommit = false
        assertFailsWith<HistorySchemaMismatch> {
            HistorySchema.createIfEmpty(db)
            db.createStatement().use {
                it.execute("INSERT INTO review_schedule VALUES(1,'{}')")
                it.execute("INSERT INTO scoped_review_configuration VALUES(1,1)")
            }
            HistorySchema.validate(db)
        }
        db.rollback(); db.autoCommit = true
        db.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'").use { it.next(); assertEquals(0,it.getInt(1)) }
            for (marker in listOf("user_version","application_id")) s.executeQuery("PRAGMA $marker").use { it.next(); assertEquals(0,it.getInt(1)) }
        }
    }

    @Test fun `incomplete scheduler fields and extra views are refused without repair`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        HistorySchema.createIfEmpty(db)
        val complete = Json { encodeDefaults = true }.encodeToString(ReviewSchedulerState())
        db.prepareStatement("INSERT INTO review_schedule VALUES(1,?)").use { it.setString(1,complete); it.executeUpdate() }
        db.createStatement().use { it.execute("INSERT INTO scoped_review_configuration VALUES(1,1)") }
        db.createStatement().use { it.execute("INSERT INTO plan_selection VALUES(1,0,NULL,NULL)") }
        db.createStatement().use { it.execute("INSERT INTO scoped_queue_metadata VALUES(1,0,0)") }
        PersonalReviewRuntimeStore(db).seed()
        SignalMutationStore(db).seed()
        db.createStatement().use { it.execute("INSERT INTO constraint_observation_metadata VALUES(1,0,0,1,1)") }
        HistorySchema.validate(db)
        for (encoded in listOf("{}", complete.replace("\"timeZone\":\"UTC\",", ""), complete.replace("\"cursor\":0,", ""))) {
            db.prepareStatement("UPDATE review_schedule SET state_json=?").use { it.setString(1,encoded); it.executeUpdate() }
            assertFailsWith<HistorySchemaMismatch> { HistorySchema.validate(db) }
        }
        db.prepareStatement("UPDATE review_schedule SET state_json=?").use { it.setString(1,complete); it.executeUpdate() }
        db.createStatement().use { it.execute("CREATE VIEW unexpected AS SELECT singleton FROM review_schedule") }
        assertFailsWith<HistorySchemaMismatch> { HistorySchema.validate(db) }
    }

    @Test fun `missing plan selection is refused without reseeding an existing schema`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        HistorySchema.createIfEmpty(db)
        db.prepareStatement("INSERT INTO review_schedule VALUES(1,?)").use {
            it.setString(1,Json { encodeDefaults=true }.encodeToString(ReviewSchedulerState()));it.executeUpdate()
        }
        db.createStatement().use { it.execute("INSERT INTO scoped_review_configuration VALUES(1,0)") }
        db.createStatement().use { it.execute("INSERT INTO scoped_queue_metadata VALUES(1,0,0)") }
        PersonalReviewRuntimeStore(db).seed()
        SignalMutationStore(db).seed()
        db.createStatement().use { it.execute("INSERT INTO constraint_observation_metadata VALUES(1,0,0,1,1)") }
        assertFailsWith<HistorySchemaMismatch> { HistorySchema.validate(db) }
        assertEquals(false,HistorySchema.createIfEmpty(db))
        db.createStatement().use { it.executeQuery("SELECT count(*) FROM plan_selection").use { row ->row.next();assertEquals(0,row.getInt(1))} }
    }

    @Test fun `missing scoped queue metadata seed is refused without reseeding`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        HistorySchema.createIfEmpty(db)
        db.prepareStatement("INSERT INTO review_schedule VALUES(1,?)").use {
            it.setString(1, Json { encodeDefaults=true }.encodeToString(ReviewSchedulerState())); it.executeUpdate()
        }
        db.createStatement().use {
            it.execute("INSERT INTO scoped_review_configuration VALUES(1,0)")
            it.execute("INSERT INTO plan_selection VALUES(1,0,NULL,NULL)")
        }
        PersonalReviewRuntimeStore(db).seed()
        SignalMutationStore(db).seed()
        db.createStatement().use { it.execute("INSERT INTO constraint_observation_metadata VALUES(1,0,0,1,1)") }
        assertFailsWith<HistorySchemaMismatch> { HistorySchema.validate(db) }
        assertEquals(false, HistorySchema.createIfEmpty(db))
        db.createStatement().use { it.executeQuery("SELECT count(*) FROM scoped_queue_metadata").use { row -> row.next(); assertEquals(0,row.getInt(1)) } }
    }

    @Test fun `missing personal owner seed is refused without inventing a replacement owner`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        HistorySchema.createIfEmpty(db)
        db.prepareStatement("INSERT INTO review_schedule VALUES(1,?)").use {
            it.setString(1,Json { encodeDefaults=true }.encodeToString(ReviewSchedulerState()));it.executeUpdate()
        }
        db.createStatement().use {
            it.execute("INSERT INTO scoped_review_configuration VALUES(1,0)")
            it.execute("INSERT INTO plan_selection VALUES(1,0,NULL,NULL)")
            it.execute("INSERT INTO scoped_queue_metadata VALUES(1,0,0)")
        }
        SignalMutationStore(db).seed()
        db.createStatement().use { it.execute("INSERT INTO constraint_observation_metadata VALUES(1,0,0,1,1)") }
        assertFailsWith<HistorySchemaMismatch> { HistorySchema.validate(db) }
        assertEquals(false,HistorySchema.createIfEmpty(db))
        db.createStatement().use { it.executeQuery("SELECT count(*) FROM personal_review_runtime_owner").use { row -> row.next();assertEquals(0,row.getInt(1)) } }
    }

    @Test fun `missing signal evidence epoch seed is refused without reseeding`() = withDatabase { db ->
        db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        HistorySchema.createIfEmpty(db)
        db.prepareStatement("INSERT INTO review_schedule VALUES(1,?)").use {
            it.setString(1, Json { encodeDefaults=true }.encodeToString(ReviewSchedulerState())); it.executeUpdate()
        }
        db.createStatement().use {
            it.execute("INSERT INTO scoped_review_configuration VALUES(1,0)")
            it.execute("INSERT INTO plan_selection VALUES(1,0,NULL,NULL)")
            it.execute("INSERT INTO scoped_queue_metadata VALUES(1,0,0)")
        }
        PersonalReviewRuntimeStore(db).seed()
        db.createStatement().use { it.execute("INSERT INTO constraint_observation_metadata VALUES(1,0,0,1,1)") }
        assertFailsWith<HistorySchemaMismatch> { HistorySchema.validate(db) }
        assertEquals(false, HistorySchema.createIfEmpty(db))
        db.createStatement().use { it.executeQuery("SELECT count(*) FROM signal_evidence_epoch").use { row -> row.next(); assertEquals(0, row.getInt(1)) } }
    }

    @Test fun `constraint observation metadata is required and never recreated during reopen`() {
        val dir=Files.createTempDirectory("constraint-metadata-schema-")
        val path=dir.resolve("history.db")
        try {
            HistoryStore(path).close()
            DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
                db.createStatement().use { it.execute("DELETE FROM constraint_observation_metadata") }
            }
            assertFailsWith<HistorySchemaMismatch> { HistoryStore(path) }
            DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
                db.createStatement().use { it.executeQuery("SELECT count(*) FROM constraint_observation_metadata").use { row -> row.next();assertEquals(0,row.getInt(1)) } }
            }
        } finally { Files.list(dir).use { files -> files.forEach(Files::deleteIfExists) };Files.deleteIfExists(dir) }
    }
}
