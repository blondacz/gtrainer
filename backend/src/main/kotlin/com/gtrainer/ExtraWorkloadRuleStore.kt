package com.gtrainer

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.encodeToString

/** SQL-only immutable rule history. Connection ownership, locking and transactions belong to caller. */
internal class ExtraWorkloadRuleStore(private val connection: Connection) {
    private val json=ScopedEvidencePreparation.json

    private fun latest(id: String): ExtraWorkloadRule? = connection.prepareStatement(
        "SELECT rule_json FROM extra_workload_rule_revisions WHERE rule_id=? ORDER BY revision DESC LIMIT 1").use { s ->
        s.setString(1,id);s.executeQuery().use { r -> if(r.next()) json.decodeFromString(r.getString(1)) else null }
    }

    fun list(): List<ExtraWorkloadRule> = connection.createStatement().use { s ->
        s.executeQuery("SELECT r.rule_json FROM extra_workload_rule_revisions r WHERE r.revision=(SELECT max(h.revision) FROM extra_workload_rule_revisions h WHERE h.rule_id=r.rule_id) ORDER BY r.rule_id LIMIT 21").use { r ->
            buildList { while(r.next()) { if(size==20) throw IllegalArgumentException("Extra workload rule limit reached");add(json.decodeFromString(r.getString(1))) } }
        }
    }

    fun get(id: String, revision: Int?=null): ExtraWorkloadRule {
        validateContextId(id)
        val sql=if(revision==null) "SELECT rule_json FROM extra_workload_rule_revisions WHERE rule_id=? ORDER BY revision DESC LIMIT 1" else "SELECT rule_json FROM extra_workload_rule_revisions WHERE rule_id=? AND revision=?"
        return connection.prepareStatement(sql).use { s ->
            s.setString(1,id);if(revision!=null)s.setInt(2,revision)
            s.executeQuery().use { r -> if(r.next()) json.decodeFromString(r.getString(1)) else throw PlanNotFound() }
        }
    }

    fun receipt(request: ExtraWorkloadRuleRequest): ExtraWorkloadRuleReceipt? = connection.prepareStatement(
        "SELECT request_json,rule_id,revision FROM extra_workload_rule_operations WHERE operation_id=?").use { s ->
        s.setString(1,request.operationId);s.executeQuery().use { r ->
            if(!r.next()) null else {
                if(json.decodeFromString<ExtraWorkloadRuleRequest>(r.getString(1))!=request) throw PlanConflict()
                ExtraWorkloadRuleReceipt(get(r.getString(2),r.getInt(3)),true)
            }
        }
    }

    fun save(request: ExtraWorkloadRuleRequest,at: Instant): ExtraWorkloadRuleReceipt {
        receipt(request)?.let { return it }
        val previous=latest(request.ruleId)
        if((previous?.revision?:0)!=request.expectedRevision) throw PlanConflict()
        val ruleCount=connection.createStatement().use { s -> s.executeQuery("SELECT count(DISTINCT rule_id) FROM extra_workload_rule_revisions").use { r -> r.next();r.getInt(1) } }
        if(previous==null && ruleCount>=20) throw IllegalArgumentException("Extra workload rule limit reached")
        require(!request.enabled || request.expectedRevision<999) { "Final extra workload revision is reserved for disabling" }
        val rule=ExtraWorkloadRule(request.ruleId,request.expectedRevision+1,request.enabled,request.configuration,at.toString())
        val encodedRule=json.encodeToString(rule);require(encodedRule.toByteArray(Charsets.UTF_8).size<=8192) { "Extra workload rule exceeds storage limit" }
        val encodedRequest=json.encodeToString(request);require(encodedRequest.toByteArray(Charsets.UTF_8).size<=8192) { "Extra workload request exceeds storage limit" }
        connection.prepareStatement("INSERT INTO extra_workload_rule_revisions(rule_id,revision,rule_json) VALUES(?,?,?)").use { s -> s.setString(1,rule.ruleId);s.setInt(2,rule.revision);s.setString(3,encodedRule);s.executeUpdate() }
        connection.prepareStatement("INSERT INTO extra_workload_rule_operations(operation_id,request_json,rule_id,revision) VALUES(?,?,?,?)").use { s -> s.setString(1,request.operationId);s.setString(2,encodedRequest);s.setString(3,rule.ruleId);s.setInt(4,rule.revision);s.executeUpdate() }
        return ExtraWorkloadRuleReceipt(rule,false)
    }
}
