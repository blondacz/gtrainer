package com.gtrainer

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.encodeToString

/** SQL only; HistoryStore owns the monitor, write fences and whole confirmation transaction. */
internal class PlanReconciliationStore(private val connection: Connection) {
    private val json=ScopedEvidencePreparation.json

    fun confirmations(planId: String): List<PlanMatchConfirmation> = connection.prepareStatement(
        "SELECT confirmation_json FROM plan_reconciliation_confirmations WHERE plan_id=? ORDER BY sequence LIMIT 10001").use { statement ->
        statement.setString(1,planId)
        statement.executeQuery().use { rows -> buildList {
            while(rows.next()) { if(size==10_000) throw ScopedEvidenceInspectionLimit();add(json.decodeFromString<PlanMatchConfirmation>(rows.getString(1))) }
        } }
    }

    fun receipt(request: PlanReconciliationConfirmationRequest): PlanReconciliationReceipt? = connection.prepareStatement(
        "SELECT request_json,confirmation_json FROM plan_reconciliation_confirmations WHERE confirmation_id=?").use { statement ->
        statement.setString(1,request.confirmationId)
        statement.executeQuery().use { row ->
            if(!row.next()) null else {
                if(json.decodeFromString<PlanReconciliationConfirmationRequest>(row.getString(1))!=request) throw PlanConflict()
                PlanReconciliationReceipt(json.decodeFromString(row.getString(2)),true)
            }
        }
    }

    fun insert(request: PlanReconciliationConfirmationRequest,activity: ReconciliationActivity?,at: Instant): PlanReconciliationReceipt {
        connection.createStatement().use { statement -> statement.executeQuery("SELECT count(*) FROM plan_reconciliation_confirmations").use { row ->
            row.next();require(row.getInt(1)<10_000) { "Plan confirmation capacity reached" }
        } }
        val result=PlanMatchConfirmation(request.confirmationId,request.plan,request.sessionId,request.action,activity,"authenticated_user",at.toString())
        connection.prepareStatement("INSERT INTO plan_reconciliation_confirmations(confirmation_id,plan_id,plan_revision,session_id,request_json,confirmation_json) VALUES(?,?,?,?,?,?)").use { statement ->
            statement.setString(1,request.confirmationId);statement.setString(2,request.plan.planId);statement.setInt(3,request.plan.revision)
            statement.setString(4,request.sessionId);statement.setString(5,json.encodeToString(request));statement.setString(6,json.encodeToString(result));statement.executeUpdate()
        }
        return PlanReconciliationReceipt(result,false)
    }

    /** Remove whole dependent lineages, never just match heads that could expose an older skip. */
    fun purgeImportDependentHistories() {
        val lineages=connection.createStatement().use { statement ->
            statement.executeQuery("SELECT confirmation_json FROM plan_reconciliation_confirmations ORDER BY sequence LIMIT 10001").use { rows ->
                var count=0
                buildSet { while(rows.next()) {
                    check(++count<=10_000)
                    val confirmation=json.decodeFromString<PlanMatchConfirmation>(rows.getString(1))
                    if(confirmation.action=="match") add(confirmation.plan.planId to confirmation.sessionId)
                } }
            }
        }
        connection.prepareStatement("DELETE FROM plan_reconciliation_confirmations WHERE plan_id=? AND session_id=?").use { statement ->
            lineages.forEach { (plan,session) -> statement.setString(1,plan);statement.setString(2,session);statement.addBatch() }
            statement.executeBatch()
        }
    }
}
