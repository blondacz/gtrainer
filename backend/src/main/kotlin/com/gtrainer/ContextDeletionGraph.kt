package com.gtrainer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection

internal data class ContextDeletionGraph(
    val contextIds: List<String>, val deletedContextIds: List<String>, val sourceIds: List<String>,
    val commitmentIds: List<String>, val snapshotIds: List<String>, val receiptIds: List<String>, val executionIds: List<String>,
    val proposalIds: List<String>,val retainedApprovalOperationIds: List<String>,val digest: String
)

/** Read-only, bounded closure and fingerprint for a deletion preview. */
internal fun contextDeletionGraph(connection: Connection, targetId: String): ContextDeletionGraph {
    validateContextId(targetId)
    val budget = Budget()
    val contextRows = linkedMapOf<String, List<List<String?>>>()
    val links = linkedSetOf<List<String?>>()
    val commitments = linkedSetOf<String>()
    val commitmentRows = linkedSetOf<List<String?>>()
    val sources = linkedSetOf<String>()
    val sourceRows = linkedSetOf<List<String?>>()
    val snapshots = linkedSetOf<String>()
    val snapshotRows = linkedSetOf<List<String?>>()
    val snapshotRefs = linkedSetOf<List<String?>>()
    val executions=linkedSetOf<String>()
    val executionRows=linkedSetOf<List<String?>>()
    val deleted = linkedSetOf(targetId)
    val contexts = linkedSetOf(targetId)
    val intakes = linkedSetOf<String>()
    val pendingContexts = ArrayDeque<String>().apply { add(targetId) }
    val pendingIntakes = ArrayDeque<String>()
    val pendingCommitments = ArrayDeque<String>()
    val pendingSnapshots = ArrayDeque<String>()
    val pendingExecutions = ArrayDeque<String>()

    fun query(sql: String, vararg args: Any?): List<List<String?>> = connection.prepareStatement(sql).use { p ->
        args.forEachIndexed { i, v -> p.setObject(i + 1, v) }
        p.executeQuery().use { r -> buildList { while (r.next()) { budget.tick(); add((1..r.metaData.columnCount).map { r.getString(it) }) } } }
    }
    fun addContext(id: String) { if (contexts.add(id)) pendingContexts.add(id) }
    fun addCommitment(id: String) { if (commitments.add(id)) pendingCommitments.add(id) }
    fun addExecutions(predicate: String,id: String) {
        connection.prepareStatement("SELECT b.execution_id,b.binding_json,s.state,s.stale_reason,s.validated_output_json FROM scoped_execution_bindings b JOIN scoped_execution_state s USING(execution_id) WHERE $predicate ORDER BY b.execution_id").use { statement ->
            statement.setString(1,id);statement.executeQuery().use { rows -> while(rows.next()) {
                budget.tick()
                val executionId=rows.getString(1)
                if(executions.add(executionId)) pendingExecutions.add(executionId)
                executionRows.add(listOf(executionId,AnalysisClaims.hash(rows.getString(2)),rows.getString(3),rows.getString(4),
                    rows.getString(5)?.let(AnalysisClaims::hash)))
            } }
        }
    }

    while (pendingContexts.isNotEmpty() || pendingIntakes.isNotEmpty() || pendingCommitments.isNotEmpty() || pendingSnapshots.isNotEmpty() || pendingExecutions.isNotEmpty()) {
        while (pendingContexts.isNotEmpty()) {
            val id = pendingContexts.removeFirst()
            val rows = query("SELECT context_id,revision,category,review_id,retired FROM athlete_context WHERE context_id=? ORDER BY revision", id)
            if (rows.isEmpty()) throw ContextNotFound()
            contextRows[id] = rows
                query("SELECT context_id,context_revision,intake_id,intake_revision FROM context_intake_links WHERE context_id=?", id).forEach { link ->
                    links.add(link); sources.add(link[2]!!); if (intakes.add(link[2]!!)) pendingIntakes.add(link[2]!!)
                }
                query("SELECT commitment_id,revision,intake_id,intake_revision,context_id,context_revision,event_id,definition_json FROM commitment_revisions WHERE context_id=? ORDER BY commitment_id,revision", id).forEach { c -> commitmentRows.add(c); addCommitment(c[0]!!) }
                query("SELECT snapshot_id,context_id,revision FROM connected_review_context_refs WHERE context_id=?", id).forEach { ref ->
                    snapshotRefs.add(listOf("legacy") + ref); if (snapshots.add(ref[0]!!)) pendingSnapshots.add(ref[0]!!)
                }
                query("SELECT snapshot_id,kind,identity,revision FROM scoped_evidence_dependencies WHERE kind='context' AND identity=?", id).forEach { ref ->
                    snapshotRefs.add(listOf("scoped") + ref); if (snapshots.add(ref[0]!!)) pendingSnapshots.add(ref[0]!!)
                }
        }
        while (pendingIntakes.isNotEmpty()) {
            val id = pendingIntakes.removeFirst()
            sources.add(id)
            addExecutions("b.intake_id=?",id)
            query("SELECT context_id,context_revision,intake_id,intake_revision FROM context_intake_links WHERE intake_id=? ORDER BY context_id,context_revision", id).forEach { link -> links.add(link); addContext(link[0]!!) }
            query("SELECT commitment_id,revision,intake_id,intake_revision,context_id,context_revision,event_id,definition_json FROM commitment_revisions WHERE intake_id=? ORDER BY commitment_id,revision", id).forEach { c -> commitmentRows.add(c); addCommitment(c[0]!!) }
            query("SELECT snapshot_id,kind,identity,revision FROM scoped_evidence_dependencies WHERE kind='intake' AND identity=?", id).forEach { ref ->
                snapshotRefs.add(listOf("scoped") + ref); if (snapshots.add(ref[0]!!)) pendingSnapshots.add(ref[0]!!)
            }
        }
        while (pendingCommitments.isNotEmpty()) {
            val id = pendingCommitments.removeFirst()
            query("SELECT commitment_id,revision,intake_id,intake_revision,context_id,context_revision,event_id,definition_json FROM commitment_revisions WHERE commitment_id=? ORDER BY revision", id).forEach { c ->
                commitmentRows.add(c); c[2]?.let { if (intakes.add(it)) pendingIntakes.add(it) }; c[4]?.let(::addContext)
            }
            query("SELECT snapshot_id,kind,identity,revision FROM scoped_evidence_dependencies WHERE kind='commitment' AND identity=?", id).forEach { ref ->
                snapshotRefs.add(listOf("scoped") + ref); if (snapshots.add(ref[0]!!)) pendingSnapshots.add(ref[0]!!)
            }
        }
        while (pendingSnapshots.isNotEmpty()) {
            val id = pendingSnapshots.removeFirst()
            query("SELECT d.snapshot_id,d.kind,d.identity,d.revision FROM scoped_evidence_dependencies d JOIN adaptation_results a ON a.work_sha256=d.identity WHERE d.kind='adaptation_result' AND a.snapshot_id=?",id).forEach { ref ->
                snapshotRefs.add(listOf("scoped")+ref);if(snapshots.add(ref[0]!!)) pendingSnapshots.add(ref[0]!!)
            }
            val row = query("SELECT snapshot_id,packet_sha256 FROM connected_review_snapshots WHERE snapshot_id=?", id)
            if (row.isNotEmpty()) snapshotRows.addAll(row)
            snapshotRows.addAll(query("SELECT snapshot_id,state,stale_reason FROM connected_review_snapshot_state WHERE snapshot_id=?",id))
            val scoped = query("SELECT snapshot_id,binding_sha256,packet_sha256,manifest_json FROM scoped_evidence_snapshots WHERE snapshot_id=?", id)
            addExecutions("b.source_snapshot_id=?",id)
            scoped.forEach { snapshotRows.add(listOf("scoped") + it) }
            query("SELECT snapshot_id,context_id,revision FROM connected_review_context_refs WHERE snapshot_id=?", id).forEach { snapshotRefs.add(listOf("legacy") + it) }
            query("SELECT snapshot_id,kind,identity,revision FROM scoped_evidence_dependencies WHERE snapshot_id=?", id).forEach { snapshotRefs.add(listOf("scoped") + it) }
            query("SELECT context_id FROM athlete_context WHERE category='feedback' AND review_id=? ORDER BY context_id", id).forEach { r ->
                if (deleted.add(r[0]!!)) addContext(r[0]!!)
            }
        }
        while(pendingExecutions.isNotEmpty()) {
            val id=pendingExecutions.removeFirst()
            query("SELECT context_id FROM athlete_context WHERE category='feedback' AND review_id=? ORDER BY context_id",id).forEach { r ->
                if(deleted.add(r[0]!!)) addContext(r[0]!!)
            }
        }
    }
    if (contextRows[targetId].isNullOrEmpty()) throw ContextNotFound()

    // Include all revision edges for every reached identity (including revisions not used in discovery).
    sources.addAll(links.mapNotNull { it[2] })
    sources.sorted().forEach { id -> sourceRows.addAll(query("SELECT intake_id,revision,source_category,content,author_attribution,entered_by,observed_on,recorded_utc FROM context_intakes WHERE intake_id=? ORDER BY revision", id)) }
    val receiptRows = linkedMapOf<String, List<String?>>()
    query("SELECT confirmation_id,intake_id,intake_revision,request_sha256,refs_json FROM context_confirmation_receipts ORDER BY confirmation_id LIMIT 50001").also {
        if (it.size > 50000) throw ContextDeletionLimit()
    }.forEach { row ->
        val matchesSource = row[1] in sources
        val matchesRefs = try {
            val refs = Json.parseToJsonElement(row[4]!!).jsonObject
            listOf("contexts", "commitments").map { key ->
                val members=refs.getValue(key).jsonArray.map { member -> member.jsonObject.getValue("id").jsonPrimitive.content.also(::validateContextId) }
                members.any { it in if(key=="contexts") contexts else commitments }
            }.any { it }
        } catch (_: Exception) { throw IllegalStateException("Invalid stored confirmation references") }
        if (matchesSource || matchesRefs) receiptRows[row[0]!!] = row
    }
    val rows = mutableListOf<List<String?>>()
    contextRows.values.flatten().forEach { rows.add(listOf("context") + it) }
    links.forEach { rows.add(listOf("link") + it) }
    sourceRows.forEach { rows.add(listOf("source") + it) }
    commitmentRows.forEach { rows.add(listOf("commitment") + it) }
    snapshotRows.forEach { rows.add(listOf("snapshot") + it) }
    snapshotRefs.forEach { rows.add(listOf("snapshot_ref") + it) }
    receiptRows.values.forEach { rows.add(listOf("receipt") + it) }
    executionRows.forEach { rows.add(listOf("execution")+it) }
    // Proposal policy bindings include the complete inventory, beyond snapshot references.
    val proposalRows=PlanAmendmentProposalStore(connection).deletionRows()
    proposalRows.forEach { budget.tick();rows.add(listOf("proposal")+it) }
    val approvalRows=PlanAmendmentApprovalStore(connection).deletionRows()
    approvalRows.forEach { budget.tick();rows.add(listOf("retained_approval_audit")+it) }
    rows.add(listOf("constraint_proofs",ConstraintEventStore(connection).deletionBinding()))
    rows.add(listOf("adaptation_reasons",AdaptationReasonStore(connection).deletionBinding()))
    rows.add(listOf("signal_replay",PersonalSignalStateStore(connection).deletionBinding()))
    rows.add(listOf("adaptation_results",AdaptationResultStore(connection).deletionBinding()))
    val canonical = rows.map { tuple -> tuple.joinToString("") { v -> v?.let { "${it.length}:$it" } ?: "-1:" } }.sorted().joinToString("\n")
    val digest = AnalysisClaims.hash(canonical)
    return ContextDeletionGraph(contexts.sorted(), deleted.sorted(), sources.sorted(), commitments.sorted(), snapshots.sorted(), receiptRows.keys.sorted(),executions.sorted(),
        proposalRows.map { requireNotNull(it.first()) },approvalRows.map { requireNotNull(it.first()) }, digest)
}

private class Budget {
    private var count = 0
    fun tick() { if (++count > 50_000) throw ContextDeletionLimit() }
}
