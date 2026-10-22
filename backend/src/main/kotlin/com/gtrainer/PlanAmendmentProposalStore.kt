package com.gtrainer

import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal data class StoredPlanAmendmentProposal(
    val proposalId: String,
    val snapshotId: String,
    val submissionSha256: String,
    val projectionSha256: String,
    val projectionJson: String,
    val createdUtc: String,
    val expiresUtc: String,
    val state: String,
    val reason: String?,
    val rejectedUtc: String?,
) {
    override fun toString() = "StoredPlanAmendmentProposal(proposalId=$proposalId, state=$state, projection=[REDACTED])"
}

internal class PlanAmendmentProposalStore(private val connection: Connection) {
    fun get(id: String): StoredPlanAmendmentProposal {
        validateContextId(id)
        return load("p.proposal_id=?", id) ?: throw PlanNotFound()
    }

    fun findSubmission(digest: String): StoredPlanAmendmentProposal? {
        requireHash(digest)
        return load("p.submission_sha256=?", digest)
    }

    fun insert(row: StoredPlanAmendmentProposal): StoredPlanAmendmentProposal {
        validate(row)
        findSubmission(row.submissionSha256)?.let { return it }
        val count = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM plan_amendment_proposals").use { rows -> rows.next(); rows.getInt(1) }
        }
        if (count >= CAPACITY) throw PlanConflict()
        connection.prepareStatement("INSERT INTO plan_amendment_proposals(proposal_id,source_snapshot_id,submission_sha256,projection_sha256,projection_json,created_utc,expires_utc) VALUES(?,?,?,?,?,?,?)").use { statement ->
            statement.setString(1, row.proposalId); statement.setString(2, row.snapshotId); statement.setString(3, row.submissionSha256)
            statement.setString(4, row.projectionSha256); statement.setString(5, row.projectionJson); statement.setString(6, row.createdUtc); statement.setString(7, row.expiresUtc)
            statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO plan_amendment_proposal_state(proposal_id,state,reason,rejected_utc) VALUES(?,?,?,?)").use { statement ->
            statement.setString(1, row.proposalId); statement.setString(2, row.state); statement.setString(3, row.reason); statement.setString(4, row.rejectedUtc)
            statement.executeUpdate()
        }
        return get(row.proposalId)
    }

    fun reject(id: String, expectedDigest: String, at: Instant): StoredPlanAmendmentProposal {
        requireHash(expectedDigest)
        val current = get(id)
        if (current.projectionSha256 != expectedDigest) throw PlanConflict()
        if (current.state == "rejected" && current.reason == "user_rejected") return current
        if (current.state != "pending") throw PlanConflict()
        val rejectedUtc = at.toString()
        connection.prepareStatement("UPDATE plan_amendment_proposal_state SET state='rejected',reason='user_rejected',rejected_utc=? WHERE proposal_id=? AND state='pending'").use { statement ->
            statement.setString(1, rejectedUtc); statement.setString(2, id)
            if (statement.executeUpdate() != 1) throw PlanConflict()
        }
        return get(id)
    }

    fun purge() { connection.createStatement().use { it.executeUpdate("DELETE FROM plan_amendment_proposals") } }

    fun index(offset: Int,limit: Int): PlanAmendmentProposalIndex {
        require(offset in 0..CAPACITY && limit in 1..50)
        val count=connection.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM plan_amendment_proposals").use { it.next();it.getInt(1) } }
        if(count>CAPACITY) throw PlanConflict()
        val entries=connection.prepareStatement("SELECT p.proposal_id,s.state,p.created_utc,p.expires_utc FROM plan_amendment_proposals p JOIN plan_amendment_proposal_state s USING(proposal_id) ORDER BY p.created_utc DESC,p.proposal_id LIMIT ? OFFSET ?").use {
            it.setInt(1,limit);it.setInt(2,offset)
            it.executeQuery().use { rows -> buildList { while(rows.next()) add(PlanAmendmentProposalIndexEntry(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4))) } }
        }
        return PlanAmendmentProposalIndex(offset,limit,count,entries,if(offset+limit<count)offset+limit else null)
    }

    fun markApplied(id: String,expectedDigest: String) {
        val row=get(id)
        if(row.projectionSha256!=expectedDigest || row.state!="pending") throw PlanConflict()
        connection.prepareStatement("UPDATE plan_amendment_proposal_state SET state='applied' WHERE proposal_id=? AND state='pending'").use {
            it.setString(1,id)
            if(it.executeUpdate()!=1) throw PlanConflict()
        }
    }

    fun ids(): List<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT proposal_id FROM plan_amendment_proposals ORDER BY proposal_id LIMIT ${CAPACITY+1}").use { rows ->
            buildList { while (rows.next()) { if(size>=CAPACITY) throw PlanConflict();add(rows.getString(1)) } }
        }
    }

    fun deletionRows(): List<List<String?>> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT proposal_id,submission_sha256,projection_sha256,state,reason,rejected_utc FROM plan_amendment_proposals JOIN plan_amendment_proposal_state USING(proposal_id) ORDER BY proposal_id LIMIT ${CAPACITY+1}").use { rows ->
            buildList { while (rows.next()) { if(size>=CAPACITY) throw PlanConflict();add((1..6).map(rows::getString)) } }
        }
    }

    private fun load(where: String, value: String): StoredPlanAmendmentProposal? = connection.prepareStatement("SELECT p.proposal_id,p.source_snapshot_id,p.submission_sha256,p.projection_sha256,p.projection_json,p.created_utc,p.expires_utc,s.state,s.reason,s.rejected_utc FROM plan_amendment_proposals p JOIN plan_amendment_proposal_state s USING(proposal_id) WHERE $where").use { statement ->
        statement.setString(1, value)
        statement.executeQuery().use { rows ->
            if (!rows.next()) null else {
                val row = StoredPlanAmendmentProposal(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9), rows.getString(10))
                validate(row)
                row
            }
        }
    }

    private fun validate(row: StoredPlanAmendmentProposal) {
        try { require(UUID.fromString(row.proposalId).toString() == row.proposalId.lowercase()) } catch (_: Exception) { throw PlanConflict() }
        validateContextId(row.snapshotId)
        requireHash(row.submissionSha256); requireHash(row.projectionSha256)
        if (sha256(row.projectionJson) != row.projectionSha256 || row.projectionJson.toByteArray(StandardCharsets.UTF_8).size > MAX_PROJECTION_BYTES) throw PlanConflict()
        val created = try { Instant.parse(row.createdUtc) } catch (_: Exception) { throw PlanConflict() }
        val expires = try { Instant.parse(row.expiresUtc) } catch (_: Exception) { throw PlanConflict() }
        if (!expires.isAfter(created) || expires.isAfter(created.plusSeconds(24 * 60 * 60))) throw PlanConflict()
        if (row.state !in STATES || row.reason !in REASONS && row.reason != null) throw PlanConflict()
        if (row.state == "rejected") {
            if (row.reason == null || row.rejectedUtc == null) throw PlanConflict()
            try { if(Instant.parse(row.rejectedUtc)<created) throw PlanConflict() } catch (_: Exception) { throw PlanConflict() }
        } else if (row.reason != null || row.rejectedUtc != null) throw PlanConflict()
    }

    private fun requireHash(hash: String) { if (!validHash(hash)) throw PlanConflict() }
    private fun validHash(hash: String) = hash.matches(Regex("[0-9a-f]{64}"))
    private fun sha256(value: String) = AnalysisClaims.hash(value)

    private companion object {
        const val CAPACITY = 1000
        const val MAX_PROJECTION_BYTES = 256 * 1024
        val STATES = setOf("pending", "no_change", "needs_confirmation", "rejected", "applied")
        val REASONS = setOf("user_rejected", "invalid_whole_draft", "policy_validation")
    }
}
