package com.gtrainer

import java.sql.Connection
import java.time.Instant
import java.util.UUID

internal data class StoredPlanAmendmentApproval(
    val operationId: String,
    val proposalId: String,
    val appliedPlan: PlanRef,
    val appliedUtc: String,
) {
    override fun toString() = "StoredPlanAmendmentApproval([REDACTED])"
}

internal class PlanAmendmentApprovalStore(private val connection: Connection) {
    fun replay(proposalId: String, operationId: String, requestSha256: String): StoredPlanAmendmentApproval? {
        validateId(proposalId); validateId(operationId); requireHash(requestSha256)
        val receipt = loadByOperation(operationId) ?: run {
            if (loadByProposal(proposalId) != null) throw PlanConflict()
            return null
        }
        if (receipt.proposalId != proposalId) throw PlanConflict()
        val proposal = proposalState(proposalId) ?: throw PlanNotFound()
        val binding = loadBinding(operationId) ?: throw PlanNotFound()
        if (binding.first != proposalId || binding.second != requestSha256) throw PlanConflict()
        if (proposal != "applied") throw PlanConflict()
        return receipt
    }

    fun getForProposal(proposalId: String): StoredPlanAmendmentApproval? {
        validateId(proposalId)
        val state = proposalState(proposalId) ?: return null
        val receipt = loadByProposal(proposalId) ?: return null
        val binding = loadBinding(receipt.operationId) ?: throw PlanConflict()
        if (binding.first != proposalId || state != "applied") throw PlanConflict()
        return receipt
    }

    fun insert(receipt: StoredPlanAmendmentApproval, requestSha256: String): StoredPlanAmendmentApproval {
        validate(receipt); requireHash(requestSha256)
        replay(receipt.proposalId, receipt.operationId, requestSha256)?.let { existing ->
            if (existing != receipt) throw PlanConflict()
            return existing
        }
        if (proposalState(receipt.proposalId) != "applied") throw PlanConflict()
        val count = connection.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM plan_amendment_approval_audit").use { it.next(); it.getInt(1) } }
        if (count >= CAPACITY) throw PlanConflict()
        connection.prepareStatement("INSERT INTO plan_amendment_approval_audit(operation_id,proposal_id,applied_plan_id,applied_revision,applied_utc) VALUES(?,?,?,?,?)").use { s ->
            s.setString(1, receipt.operationId); s.setString(2, receipt.proposalId); s.setString(3, receipt.appliedPlan.planId); s.setInt(4, receipt.appliedPlan.revision); s.setString(5, receipt.appliedUtc); s.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO plan_amendment_approval_bindings(operation_id,proposal_id,request_sha256) VALUES(?,?,?)").use { s ->
            s.setString(1, receipt.operationId); s.setString(2, receipt.proposalId); s.setString(3, requestSha256); s.executeUpdate()
        }
        return receipt
    }

    fun auditIds(): List<String> = connection.createStatement().use { s ->
        s.executeQuery("SELECT operation_id FROM plan_amendment_approval_audit ORDER BY operation_id LIMIT ${CAPACITY + 1}").use { rows ->
            buildList { while (rows.next()) { if (size >= CAPACITY) throw PlanConflict(); add(rows.getString(1)) } }
        }
    }

    fun deletionRows(): List<List<String?>> = connection.createStatement().use { s ->
        s.executeQuery("SELECT a.operation_id,b.proposal_id,a.applied_plan_id,a.applied_revision,a.applied_utc,b.request_sha256 FROM plan_amendment_approval_audit a LEFT JOIN plan_amendment_approval_bindings b USING(operation_id) ORDER BY a.operation_id LIMIT ${CAPACITY + 1}").use { rows ->
            buildList { while (rows.next()) { if (size >= CAPACITY) throw PlanConflict(); add((1..6).map(rows::getString)) } }
        }
    }

    private fun loadByOperation(id: String) = load("a.operation_id=?", id)
    private fun loadByProposal(id: String) = load("a.proposal_id=?", id)
    private fun load(where: String, value: String): StoredPlanAmendmentApproval? = connection.prepareStatement("SELECT a.operation_id,a.proposal_id,a.applied_plan_id,a.applied_revision,a.applied_utc FROM plan_amendment_approval_audit a WHERE $where").use { s ->
        s.setString(1, value); s.executeQuery().use { rows -> if (!rows.next()) null else StoredPlanAmendmentApproval(rows.getString(1), rows.getString(2), PlanRef(rows.getString(3), rows.getInt(4)), rows.getString(5).also { validateTime(it) }) }
    }
    private fun loadBinding(operationId: String): Pair<String, String>? = connection.prepareStatement("SELECT proposal_id,request_sha256 FROM plan_amendment_approval_bindings WHERE operation_id=?").use { s ->
        s.setString(1, operationId); s.executeQuery().use { rows -> if (!rows.next()) null else rows.getString(1) to rows.getString(2) }
    }
    private fun proposalState(proposalId: String): String? = connection.prepareStatement("SELECT state FROM plan_amendment_proposal_state WHERE proposal_id=?").use { s -> s.setString(1, proposalId); s.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null } }
    private fun validate(row: StoredPlanAmendmentApproval) { validateId(row.operationId); validateId(row.proposalId); validateId(row.appliedPlan.planId); if (row.appliedPlan.revision !in 1..1000) throw PlanConflict(); validateTime(row.appliedUtc) }
    private fun validateTime(value: String) { try { Instant.parse(value) } catch (_: Exception) { throw PlanConflict() } }
    private fun validateId(value: String) { try { if (UUID.fromString(value).toString() != value) throw PlanConflict() } catch (_: Exception) { throw PlanConflict() } }
    private fun requireHash(value: String) { if (!value.matches(Regex("[0-9a-f]{64}"))) throw PlanConflict() }
    private companion object { const val CAPACITY = 1000 }
}
