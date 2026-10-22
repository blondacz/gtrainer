package com.gtrainer

import java.nio.file.Files
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanAmendmentApprovalStoreTest {
    private fun fixture(block: (java.sql.Connection, PlanAmendmentApprovalStore, String, String) -> Unit) {
        val dir = Files.createTempDirectory("approval-store-")
        try { DriverManager.getConnection("jdbc:sqlite:${dir.resolve("fixture.db")}").use { db ->
            db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            HistorySchema.createIfEmpty(db)
            val snapshot = UUID.randomUUID().toString()
            db.prepareStatement("INSERT INTO scoped_evidence_snapshots(snapshot_id,request_json,binding_sha256,manifest_json,created_utc) VALUES(?,'{}',?,'{}',?)").use {
                it.setString(1, snapshot); it.setString(2, "a".repeat(64)); it.setString(3, "2026-10-15T12:00:00Z"); it.executeUpdate()
            }
            val proposal = UUID.randomUUID().toString()
            db.prepareStatement("INSERT INTO plan_amendment_proposals VALUES(?,?,?,?,?,?,?)").use {
                it.setString(1, proposal); it.setString(2, snapshot); it.setString(3, "b".repeat(64)); it.setString(4, "c".repeat(64)); it.setString(5, "{}"); it.setString(6, "2026-10-15T12:00:00Z"); it.setString(7, "2026-10-16T00:00:00Z"); it.executeUpdate()
            }
            db.prepareStatement("INSERT INTO plan_amendment_proposal_state VALUES(?,'applied',NULL,NULL)").use { it.setString(1, proposal); it.executeUpdate() }
            db.prepareStatement("INSERT INTO plan_revisions VALUES(?,1,NULL,'{}','authenticated_user','2026-10-15T13:00:00Z')").use { it.setString(1, UUID.randomUUID().toString()); it.executeUpdate() }
            val planId = db.createStatement().use { s -> s.executeQuery("SELECT plan_id FROM plan_revisions").use { it.next(); it.getString(1) } }
            block(db, PlanAmendmentApprovalStore(db), proposal, planId)
        } } finally { Files.list(dir).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(dir) }
    }

    @Test fun `operation replay binds proposal and request and survives proposal purge without authority`() = fixture { db, store, proposal, plan ->
        val operation = UUID.randomUUID().toString(); val hash = "d".repeat(64)
        val receipt = StoredPlanAmendmentApproval(operation, proposal, PlanRef(plan, 1), "2026-10-15T14:00:00Z")
        store.insert(receipt, hash)
        assertEquals(receipt, store.replay(proposal, operation, hash))
        assertFailsWith<PlanConflict> { store.replay(proposal, operation, "e".repeat(64)) }
        assertFailsWith<PlanConflict> { store.replay(UUID.randomUUID().toString(), operation, hash) }
        assertFailsWith<PlanConflict> { store.insert(receipt.copy(appliedUtc="2026-10-15T15:00:00Z"), hash) }
        assertEquals(listOf(operation), store.auditIds())
        assertTrue(store.deletionRows().single().last() == hash)
        db.createStatement().use { it.execute("DELETE FROM scoped_evidence_snapshots") }
        assertNull(store.getForProposal(proposal))
        assertEquals(null, store.deletionRows().single()[1])
        assertFailsWith<PlanNotFound> { store.replay(proposal, operation, hash) }
        assertFailsWith<PlanNotFound> { store.insert(receipt, hash) }
        assertFailsWith<PlanConflict> { store.replay(UUID.randomUUID().toString(), operation, hash) }
        assertTrue(store.toString().isNotEmpty())
    }

    @Test fun `receipt and binding reject mutation and foreign-key violations`() = fixture { db, store, proposal, plan ->
        val receipt = StoredPlanAmendmentApproval(UUID.randomUUID().toString(), proposal, PlanRef(plan, 1), "2026-10-15T14:00:00Z")
        store.insert(receipt, "d".repeat(64))
        assertFailsWith<java.sql.SQLException> { db.createStatement().use { it.execute("UPDATE plan_amendment_approval_audit SET applied_utc='2026-10-15T15:00:00Z'") } }
        assertFailsWith<java.sql.SQLException> { db.createStatement().use { it.execute("UPDATE plan_amendment_approval_bindings SET request_sha256='${"e".repeat(64)}'") } }
        assertFailsWith<java.sql.SQLException> { db.createStatement().use { it.execute("INSERT INTO plan_amendment_approval_audit VALUES('bad','bad','bad',1,'bad')") } }
    }

    @Test fun `permanent audit capacity never evicts consumed operation identities`() = fixture { db, store, proposal, plan ->
        db.prepareStatement("INSERT INTO plan_amendment_approval_audit VALUES(?,?,?,?,?)").use { insert ->
            repeat(1000) {
                insert.setString(1,UUID.randomUUID().toString());insert.setString(2,UUID.randomUUID().toString())
                insert.setString(3,plan);insert.setInt(4,1);insert.setString(5,"2026-10-15T13:00:00Z");insert.addBatch()
            }
            insert.executeBatch()
        }
        val ids=store.auditIds()
        assertEquals(1000,ids.size)
        assertFailsWith<PlanConflict> { store.insert(StoredPlanAmendmentApproval(UUID.randomUUID().toString(),proposal,PlanRef(plan,1),"2026-10-15T14:00:00Z"),"d".repeat(64)) }
        assertEquals(ids,store.auditIds())
    }
}
