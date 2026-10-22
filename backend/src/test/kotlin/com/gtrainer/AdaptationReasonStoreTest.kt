package com.gtrainer

import java.sql.DriverManager
import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AdaptationReasonStoreTest {
    private fun connection()=DriverManager.getConnection("jdbc:sqlite::memory:").also { db ->
        db.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys=ON")
            s.execute("CREATE TABLE adaptation_reason_heads(reason_id TEXT PRIMARY KEY,head_json TEXT NOT NULL,support_json TEXT)")
            s.execute("CREATE TABLE adaptation_admissions(reason_id TEXT NOT NULL,episode INTEGER NOT NULL,admission_json TEXT NOT NULL,fulfilled INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(reason_id,episode),FOREIGN KEY(reason_id) REFERENCES adaptation_reason_heads(reason_id) ON DELETE RESTRICT)")
        }
    }
    private fun observation(condition:String,material:String="a")=AdaptationReasonObservation("a".repeat(64),"extra_workload","b".repeat(64),condition,material.repeat(64).take(64),"c".repeat(64))
    private fun changes(db:java.sql.Connection)=db.createStatement().use { s -> s.executeQuery("SELECT total_changes()").use { r -> r.next();r.getLong(1) } }

    @Test fun `head CAS stale and identical writes are inert`()=connection().use { db ->
        val store=AdaptationReasonStore(db);val first=AdaptationReasons.project(null,observation("active"))
        assertTrue(store.save(first,0));val count=changes(db)
        assertFalse(store.save(first,first.revision));assertFailsWith<PlanConflict> { store.save(first.copy(revision=2),0) };assertEquals(count,changes(db))
        assertEquals(first,store.get(first.reasonId));assertEquals(listOf(first),store.heads())
        assertFailsWith<PlanConflict> { store.save(first.copy(revision=2),0) }
    }

    @Test fun `admission replays original receipt only for current active head and survives episode clear`()=connection().use { db ->
        val store=AdaptationReasonStore(db);val first=AdaptationReasons.project(null,observation("active"));store.save(first,0)
        val time=Instant.parse("2025-01-01T00:00:00Z");val job="11111111-1111-4111-8111-111111111111"
        val receipt=store.admit(first,job,time)
        assertEquals(receipt,store.admit(first,"22222222-2222-4222-8222-222222222222",time.plusSeconds(1)))
        assertEquals(listOf(receipt),store.admissions(setOf(first.reasonId)))
        assertFalse(AdaptationReasons.requiresAdmission(first,store.admissions(setOf(first.reasonId))))
        val changed=AdaptationReasons.project(first,observation("active","d"));store.save(changed,first.revision)
        assertFalse(AdaptationReasons.requiresAdmission(changed,store.admissions(setOf(first.reasonId))))
        val clear=AdaptationReasons.project(changed,observation("clear"));store.save(clear,changed.revision)
        val retrigger=AdaptationReasons.project(clear,observation("active"));store.save(retrigger,clear.revision)
        assertTrue(AdaptationReasons.requiresAdmission(retrigger,store.admissions(setOf(first.reasonId))))
        assertEquals(retrigger.episode,store.admit(retrigger,"33333333-3333-4333-8333-333333333333",time.plusSeconds(2)).episode)
        assertEquals(2,store.admissions(setOf(first.reasonId)).size)
    }

    @Test fun `caller rollback undoes head and receipt and stale unknown heads cannot admit`()=connection().use { db ->
        val store=AdaptationReasonStore(db);val first=AdaptationReasons.project(null,observation("active"));db.autoCommit=false
        store.save(first,0)
        assertFailsWith<PlanConflict> { store.admit(first.copy(dependencySha256="d".repeat(64)),"11111111-1111-4111-8111-111111111111",Instant.EPOCH) }
        assertFailsWith<PlanConflict> { store.admit(first.copy(reasonId="e".repeat(64)),"11111111-1111-4111-8111-111111111111",Instant.EPOCH) }
        assertFailsWith<IllegalArgumentException> { store.save(first.copy(revision=2,episode=2),first.revision) }
        assertFailsWith<IllegalArgumentException> { store.save(first.copy(revision=2,open=false),first.revision) }
        assertEquals(first,store.get(first.reasonId))
        store.admit(first,"11111111-1111-4111-8111-111111111111",Instant.EPOCH)
        db.rollback();assertNull(store.get(first.reasonId));assertTrue(store.admissions(setOf(first.reasonId)).isEmpty())
    }

    @Test fun `privacy redaction preserves restrictive admission FK and deduplication at revision exhaustion`()=connection().use { db ->
        val store=AdaptationReasonStore(db)
        val first=AdaptationReasons.project(null,observation("active"));store.save(first,0)
        val receipt=store.admit(first,"11111111-1111-4111-8111-111111111111",Instant.EPOCH)
        val exhausted=first.copy(revision=Long.MAX_VALUE)
        db.prepareStatement("UPDATE adaptation_reason_heads SET head_json=? WHERE reason_id=?").use { p ->
            p.setString(1,kotlinx.serialization.json.Json.encodeToString(exhausted));p.setString(2,first.reasonId);p.executeUpdate()
        }
        store.redact()
        val redacted=store.get(first.reasonId)!!
        assertEquals(Long.MAX_VALUE,redacted.revision);assertEquals("unevaluable",redacted.condition)
        assertFalse(redacted.materialSha256==first.materialSha256);assertFalse(redacted.dependencySha256==first.dependencySha256)
        assertEquals(first.episode,redacted.episode);assertEquals(first.open,redacted.open)
        assertEquals(listOf(receipt),store.admissions(setOf(first.reasonId)))
        assertFalse(AdaptationReasons.requiresAdmission(redacted,listOf(receipt)))
        val changes=changes(db);store.redact();assertEquals(changes,changes(db))
    }
}
