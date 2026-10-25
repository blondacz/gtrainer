package com.gtrainer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.sql.Connection

/** SQL-only persistence helper. HistoryStore owns synchronization and transactions. */
internal class ScopedExecutionStore(private val connection: Connection) {
    private val json = Json { encodeDefaults = true }

    private object SQL {
        const val COUNT = "SELECT count(*) FROM scoped_execution_bindings"
        const val ACTIVE = "SELECT active_generation FROM connected_review_requests WHERE request_id=?"
        const val ADVANCE = "INSERT INTO connected_review_requests(request_id,active_generation) VALUES(?,?) ON CONFLICT(request_id) DO UPDATE SET active_generation=excluded.active_generation"
        const val STALE_OLD = "UPDATE scoped_execution_state SET state='STALE',stale_reason='new_generation' WHERE execution_id IN (SELECT execution_id FROM scoped_execution_bindings WHERE request_id=?) AND state IN ('IN_FLIGHT','RUNNING','PUBLISHED')"
        const val INSERT = "INSERT INTO scoped_execution_bindings(execution_id,request_id,generation,profile,source_snapshot_id,intake_id,intake_revision,binding_json,created_utc) VALUES(?,?,?,?,?,?,?,?,?)"
        const val STATE = "INSERT INTO scoped_execution_state(execution_id,state,stale_reason,validated_output_json) VALUES(?,'IN_FLIGHT',NULL,NULL)"
        const val GET = "SELECT b.request_id,b.generation,b.profile,b.source_snapshot_id,b.intake_id,b.intake_revision,b.binding_json,b.created_utc,s.state,s.stale_reason,s.validated_output_json FROM scoped_execution_bindings b JOIN scoped_execution_state s USING(execution_id) WHERE b.execution_id=?"
        const val CLAIM = "UPDATE scoped_execution_state SET state='RUNNING' WHERE execution_id=? AND state='IN_FLIGHT'"
        const val PUBLISH = "UPDATE scoped_execution_state SET state='PUBLISHED',validated_output_json=? WHERE execution_id=? AND state='RUNNING'"
        const val FAIL = "UPDATE scoped_execution_state SET state='FAILED',stale_reason=? WHERE execution_id=? AND state IN ('IN_FLIGHT','RUNNING')"
        const val DELETE = "DELETE FROM scoped_execution_bindings WHERE execution_id=?"
        const val RECOVER = "UPDATE scoped_execution_state SET state='INTERRUPTED',stale_reason='restart_requires_rearming',validated_output_json=NULL WHERE state='RUNNING'"
    }

    fun generation(requestId: String): Long = connection.prepareStatement(SQL.ACTIVE).use { st ->
        st.setString(1, requestId); st.executeQuery().use { r -> if (r.next()) r.getLong(1) else 0L }
    }

    fun history(profile: String,snapshotId: String?,offset: Int,limit: Int): ScopedExecutionHistory {
        require(profile in ScopedProfileAdmission.profiles && offset in 0..1000 && limit in 1..25)
        snapshotId?.let(::requireUuid)
        val ids=connection.prepareStatement("SELECT execution_id FROM scoped_execution_bindings WHERE profile=? " +
            "AND (? IS NULL OR source_snapshot_id=?) ORDER BY created_utc DESC,execution_id DESC LIMIT ? OFFSET ?").use { st ->
            st.setString(1,profile);st.setString(2,snapshotId);st.setString(3,snapshotId);st.setInt(4,limit+1);st.setInt(5,offset)
            st.executeQuery().use { r -> buildList { while(r.next()) add(r.getString(1)) } }
        }
        val summaries=ids.take(limit).map { id ->
            val stored=get(id);val b=stored.binding
            ScopedExecutionSummary(id,b.source,b.profile,b.providerSelection,b.coverageFrom,b.coverageUntil,b.createdUtc,
                stored.state,stored.staleReason,stored.output?.let(AnalysisClaims::hash))
        }
        return ScopedExecutionHistory(summaries,if(ids.size>limit) offset+limit else null)
    }

    /** Caller computes generation=active+1 and invokes this in its encompassing transaction. */
    fun insert(prepared: PreparedScopedExecution) {
        val binding=prepared.binding
        require(binding.generation == Math.addExact(generation(binding.requestId), 1L)) { "Execution generation changed" }
        val encoded = prepared.bindingJson
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_SCOPED_EXECUTION_STORAGE_BYTES) { "Execution binding exceeds storage limit" }
        check(json.decodeFromString<ScopedExecutionBinding>(encoded)==binding)
        connection.createStatement().use { it.executeQuery(SQL.COUNT).use { r -> r.next(); require(r.getInt(1) < 1000) { "Execution binding capacity reached" } } }
        connection.prepareStatement(SQL.STALE_OLD).use { it.setString(1, binding.requestId); it.executeUpdate() }
        val source = binding.source
        connection.prepareStatement(SQL.INSERT).use { st ->
            st.setString(1,binding.executionId); st.setString(2,binding.requestId); st.setLong(3,binding.generation); st.setString(4,binding.profile)
            st.setString(5,source.snapshotId); st.setString(6,source.intakeId)
            if(source.intakeRevision==null) st.setNull(7,java.sql.Types.INTEGER) else st.setInt(7,source.intakeRevision)
            st.setString(8,encoded); st.setString(9,binding.createdUtc); st.executeUpdate()
        }
        connection.prepareStatement(SQL.STATE).use { it.setString(1,binding.executionId); it.executeUpdate() }
        connection.prepareStatement(SQL.ADVANCE).use { it.setString(1,binding.requestId); it.setLong(2,binding.generation); it.executeUpdate() }
    }

    fun get(id: String): StoredScopedExecution {
        requireUuid(id)
        return connection.prepareStatement(SQL.GET).use { st -> st.setString(1,id); st.executeQuery().use { r ->
            if(!r.next()) throw ConnectedReviewNotFound()
            val binding=json.decodeFromString<ScopedExecutionBinding>(r.getString(7))
            check(binding.executionId==id && binding.requestId==r.getString(1) && binding.generation==r.getLong(2) && binding.profile==r.getString(3) &&
                binding.source.snapshotId==r.getString(4) && binding.source.intakeId==r.getString(5) && binding.source.intakeRevision==r.getObject(6)?.let { (it as Number).toInt() } && binding.createdUtc==r.getString(8)) { "Scoped execution storage mismatch" }
            StoredScopedExecution(binding,r.getString(9),r.getString(10),r.getString(11))
        } }
    }

    fun claim(id: String, expectedGeneration: Long): StoredScopedExecution? {
        requireUuid(id)
        val before=get(id)
        if(before.binding.generation!=expectedGeneration || generation(before.binding.requestId)!=expectedGeneration) return null
        if(connection.prepareStatement(SQL.CLAIM).use { it.setString(1,id); it.executeUpdate() }!=1) return null
        return get(id)
    }

    fun publish(id: String, output: ValidatedConnectedReviewOutput): StoredScopedExecution {
        requireUuid(id)
        require(output.text.toByteArray(StandardCharsets.UTF_8).size<=12*1024) { "Execution output exceeds storage limit" }
        val current=get(id)
        if(current.state!="RUNNING" || generation(current.binding.requestId)!=current.binding.generation) return current
        if(connection.prepareStatement(SQL.PUBLISH).use { it.setString(1,output.text);it.setString(2,id);it.executeUpdate() }!=1) return get(id)
        return get(id)
    }

    fun fail(id: String, reason: String): StoredScopedExecution {
        requireUuid(id); require(reason.matches(Regex("[a-z][a-z0-9_]{1,63}")))
        if(connection.prepareStatement(SQL.FAIL).use { it.setString(1,reason);it.setString(2,id);it.executeUpdate() }!=1) return get(id)
        return get(id)
    }

    fun delete(id: String): Boolean { requireUuid(id); return connection.prepareStatement(SQL.DELETE).use { it.setString(1,id);it.executeUpdate()==1 } }
    fun recoverInterrupted() { connection.createStatement().use { it.executeUpdate(SQL.RECOVER) } }

    private fun requireUuid(id: String) = validateContextId(id)
}
