package com.gtrainer

import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlanAmendmentProposalStoreTest {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fixture(block: (java.sql.Connection, PlanAmendmentProposalStore, String) -> Unit) {
        val dir = Files.createTempDirectory("proposal-store-")
        try {
            DriverManager.getConnection("jdbc:sqlite:${dir.resolve("fixture.db")}").use { db ->
                db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
                HistorySchema.createIfEmpty(db)
                val snapshot = UUID.randomUUID().toString()
                db.prepareStatement("INSERT INTO scoped_evidence_snapshots(snapshot_id,request_json,binding_sha256,manifest_json,created_utc) VALUES(?,'{}',?,'{}',?)").use {
                    it.setString(1, snapshot); it.setString(2, "a".repeat(64)); it.setString(3, "2026-10-15T12:00:00Z"); it.executeUpdate()
                }
                block(db, PlanAmendmentProposalStore(db), snapshot)
            }
        } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    private fun row(snapshot: String, id: String = UUID.randomUUID().toString(), submission: String = hash(UUID.randomUUID().toString()), projection: String = "{\"safe\":true}", state: String = "pending", reason: String? = null) =
        StoredPlanAmendmentProposal(id, snapshot, submission, hash(projection), projection, "2026-10-15T12:00:00Z", "2026-10-16T12:00:00Z", state, reason, null)

    @Test fun `duplicate submission returns existing before capacity and preserves state`() = fixture { _, store, snapshot ->
        val initial = row(snapshot)
        store.insert(initial)
        val rejected = store.reject(initial.proposalId, initial.projectionSha256, Instant.parse("2026-10-15T13:00:00Z"))
        val duplicate = store.insert(row(snapshot, submission = initial.submissionSha256))
        assertEquals(rejected, duplicate)
        assertEquals(rejected, store.reject(initial.proposalId, initial.projectionSha256, Instant.parse("2026-10-15T14:00:00Z")))
        assertFailsWith<PlanConflict> { store.reject(initial.proposalId, "f".repeat(64), Instant.EPOCH) }
        assertEquals(listOf(initial.proposalId), store.ids())
        assertEquals(1, store.deletionRows().size)
        assertTrue(rejected.toString().contains("[REDACTED]"))
    }

    @Test fun `proposal content immutable and source deletion cascades state`() = fixture { db, store, snapshot ->
        val row = store.insert(row(snapshot))
        assertFailsWith<java.sql.SQLException> {
            db.prepareStatement("UPDATE plan_amendment_proposals SET projection_json='changed' WHERE proposal_id=?").use { it.setString(1, row.proposalId); it.executeUpdate() }
        }
        db.prepareStatement("DELETE FROM scoped_evidence_snapshots WHERE snapshot_id=?").use { it.setString(1, snapshot); it.executeUpdate() }
        assertEquals(emptyList(), store.ids())
        db.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM plan_amendment_proposal_state").use { it.next(); assertEquals(0, it.getInt(1)) }
            s.executeQuery("SELECT count(*) FROM plan_amendment_proposals").use { it.next(); assertEquals(0, it.getInt(1)) }
        }
    }

    @Test fun `capacity fails closed and projection digest is checked`() = fixture { _, store, snapshot ->
        repeat(1000) { index -> store.insert(row(snapshot, submission = hash("submission-$index"))) }
        val duplicate = store.insert(row(snapshot, submission = hash("submission-0")))
        assertEquals(hash("submission-0"), duplicate.submissionSha256)
        assertFailsWith<PlanConflict> { store.insert(row(snapshot)) }
        assertEquals(1000, store.ids().size)
    }

    @Test fun `index is bounded metadata only and makes no freshness claim`() = fixture { _, store, snapshot ->
        repeat(3) { store.insert(row(snapshot)) }
        val page=store.index(0,2)
        assertEquals(3,page.total);assertEquals(2,page.entries.size);assertEquals(2,page.nextOffset)
        assertEquals("uninspected_open_exact_record_for_current_status",page.freshness)
        val last=store.index(2,2)
        assertEquals(1,last.entries.size);assertEquals(null,last.nextOffset)
        assertEquals(3,(page.entries+last.entries).map { it.proposalId }.distinct().size)
        assertFailsWith<IllegalArgumentException> { store.index(-1,2) }
        assertFailsWith<IllegalArgumentException> { store.index(0,51) }
    }

    @Test fun `stored projection digest corruption fails on read and bounds are enforced`() = fixture { db, store, snapshot ->
        val row = store.insert(row(snapshot))
        db.createStatement().use { it.execute("DROP TRIGGER plan_amendment_proposal_immutable") }
        db.prepareStatement("UPDATE plan_amendment_proposals SET projection_sha256=? WHERE proposal_id=?").use { it.setString(1, "0".repeat(64)); it.setString(2, row.proposalId); it.executeUpdate() }
        assertFailsWith<PlanConflict> { store.get(row.proposalId) }
        assertFailsWith<PlanConflict> { store.insert(row(snapshot, projection = "x".repeat(256 * 1024 + 1))) }
        assertNotEquals(row.projectionSha256, "0".repeat(64))
    }
}
